"""KIS API client with OAuth authentication and rate limiting."""
import asyncio
import logging
import os
from datetime import datetime, timedelta, date
from pathlib import Path
from typing import Optional, Dict, List
import aiohttp
import pandas as pd
from dotenv import load_dotenv

from config.constants import KIS_MAX_REQUESTS_PER_SECOND, KIS_REQUEST_DELAY

logger = logging.getLogger(__name__)


class KISUnavailableError(RuntimeError):
    """
    KIS API 에 접속조차 하지 못해 시장 개장 여부를 판정할 수 없는 경우.

    점검/네트워크(방화벽·타임아웃) 등으로 응답을 받지 못한 상황을 가리키며,
    'KIS 가 명시적으로 휴장이라고 답한 것'(opnd_yn='N')과 구분하기 위해 사용한다.
    """


class KISClient:
    """
    KIS Open API client with async support and rate limiting.

    Features:
    - OAuth token caching (24-hour TTL)
    - TR_ID auto-conversion (VIRTUAL/REAL mode)
    - Rate limiting (5 requests/second)
    - Comprehensive error handling
    """

    def __init__(self):
        # Load credentials directly from .env to avoid pydantic-settings parsing issues
        # (pydantic-settings truncates AppSecret with '=' character)
        # Find .env file in ai-agent directory
        env_path = Path(__file__).parent.parent / '.env'
        load_dotenv(dotenv_path=env_path, override=True)

        self.app_key = os.getenv('KIS_APP_KEY')
        self.app_secret = os.getenv('KIS_APP_SECRET')
        self.mode = os.getenv('KIS_MODE', 'VIRTUAL')
        self.base_url = os.getenv('KIS_BASE_URL', 'https://openapi.koreainvestment.com:9443')

        if not self.app_key or not self.app_secret:
            raise ValueError("KIS_APP_KEY and KIS_APP_SECRET must be set in .env file")

        self.access_token: Optional[str] = None
        self.token_expires_at: Optional[datetime] = None
        self.token_lock = asyncio.Lock()  # Prevent concurrent token requests

        # Rate limiting semaphore
        self.semaphore = asyncio.Semaphore(KIS_MAX_REQUESTS_PER_SECOND)

        logger.info(f"KISClient initialized in {self.mode} mode")
        logger.debug(f"AppKey length: {len(self.app_key)}, AppSecret length: {len(self.app_secret)}")

    async def get_access_token(self) -> str:
        """
        Get OAuth access token (cached for 24 hours).

        Thread-safe with asyncio.Lock to prevent concurrent token requests.
        KIS API limits: 1 token request per minute.

        Returns:
            str: Access token for API authorization
        """
        # Return cached token if still valid (fast path without lock)
        if self.access_token and self.token_expires_at and datetime.now() < self.token_expires_at:
            logger.debug("Using cached access token")
            return self.access_token

        # Acquire lock to prevent concurrent token requests
        async with self.token_lock:
            # Double-check after acquiring lock (another coroutine may have fetched token)
            if self.access_token and self.token_expires_at and datetime.now() < self.token_expires_at:
                logger.debug("Using cached access token (acquired after lock)")
                return self.access_token

            logger.info("Fetching new access token from KIS API")
            url = f'{self.base_url}/oauth2/tokenP'

            # KIS API requires form-encoded data, not JSON
            data = {
                'grant_type': 'client_credentials',
                'appkey': self.app_key,
                'appsecret': self.app_secret
            }

            headers = {
                'Content-Type': 'application/json; charset=utf-8'
            }

            logger.debug(f"OAuth request URL: {url}")
            logger.debug(f"OAuth request appkey length: {len(self.app_key) if self.app_key else 0}")
            logger.debug(f"OAuth request appsecret length: {len(self.app_secret) if self.app_secret else 0}")

            try:
                async with aiohttp.ClientSession() as session:
                    # Use json= parameter but with proper content-type header
                    async with session.post(url, json=data, headers=headers) as response:
                        logger.debug(f"OAuth response status: {response.status}")

                        if response.status != 200:
                            error_text = await response.text()
                            logger.error(f"OAuth failed with status {response.status}: {error_text}")
                            raise RuntimeError(f"KIS OAuth failed: HTTP {response.status} - {error_text}")

                        result = await response.json()

                        self.access_token = result['access_token']
                        # KIS tokens are valid for 24 hours
                        self.token_expires_at = datetime.now() + timedelta(hours=24)

                        logger.info("Access token acquired successfully")
                        return self.access_token

            except aiohttp.ClientError as e:
                logger.error(f"Failed to get access token: {e}")
                raise RuntimeError(f"KIS OAuth failed: {e}")

    def convert_tr_id(self, base_tr_id: str) -> str:
        """
        Convert TR_ID based on mode (VIRTUAL/REAL).

        VIRTUAL: VTTC* (모의투자)
        REAL: TTTC* (실전투자)

        Args:
            base_tr_id: Base TR_ID (e.g., 'VTTC8434R')

        Returns:
            str: Converted TR_ID based on mode
        """
        if base_tr_id is None or len(base_tr_id) < 4:
            return base_tr_id

        suffix = base_tr_id[4:]  # Extract suffix (e.g., '8434R')

        if self.mode == 'REAL' and base_tr_id.startswith('VTTC'):
            converted = f'TTTC{suffix}'
            logger.debug(f"TR_ID converted: {base_tr_id} → {converted}")
            return converted
        elif self.mode == 'VIRTUAL' and base_tr_id.startswith('TTTC'):
            converted = f'VTTC{suffix}'
            logger.debug(f"TR_ID converted: {base_tr_id} → {converted}")
            return converted

        return base_tr_id

    async def request(
        self,
        method: str,
        endpoint: str,
        tr_id: str,
        params: Optional[Dict] = None,
        json_data: Optional[Dict] = None
    ) -> Dict:
        """
        Make KIS API request with rate limiting and authentication.

        Args:
            method: HTTP method ('GET' or 'POST')
            endpoint: API endpoint path
            tr_id: Transaction ID (will be auto-converted)
            params: Query parameters for GET requests
            json_data: JSON body for POST requests

        Returns:
            Dict: API response JSON
        """
        async with self.semaphore:
            token = await self.get_access_token()
            tr_id_converted = self.convert_tr_id(tr_id)

            headers = {
                'authorization': f'Bearer {token}',
                'appkey': self.app_key,
                'appsecret': self.app_secret,
                'tr_id': tr_id_converted,
                'custtype': 'P',  # Personal account
                'content-type': 'application/json'
            }

            url = f'{self.base_url}{endpoint}'

            try:
                async with aiohttp.ClientSession() as session:
                    if method == 'GET':
                        async with session.get(url, headers=headers, params=params) as response:
                            response.raise_for_status()
                            result = await response.json()
                    elif method == 'POST':
                        async with session.post(url, headers=headers, json=json_data) as response:
                            response.raise_for_status()
                            result = await response.json()
                    else:
                        raise ValueError(f"Unsupported HTTP method: {method}")

                    # Check KIS API response code
                    if result.get('rt_cd') != '0':
                        error_msg = result.get('msg1', 'Unknown error')
                        logger.error(f"KIS API error: {error_msg}")
                        raise RuntimeError(f"KIS API error: {error_msg}")

                    return result

            except aiohttp.ClientError as e:
                logger.error(f"KIS API request failed: {method} {endpoint} - {e}")
                raise RuntimeError(f"KIS API request failed: {e}")

            finally:
                # 실패한 요청도 KIS 쪽에서는 1건으로 집계되므로, 성공 경로에만 지연을
                # 두면 오류율이 높을수록 실제 요청률이 올라간다. 세마포어를 놓기 전에
                # 항상 간격을 확보한다.
                await asyncio.sleep(KIS_REQUEST_DELAY)

    async def is_market_open(self, trade_date: Optional[datetime.date] = None) -> bool:
        """
        Check if market is open for the given date.

        Strategy:
        1. Weekend check (fast path, avoids the API call for obvious cases)
        2. KIS domestic holiday-check API (date-aware; works for any date,
           not just "today" — unlike real-time quote endpoints)

        Args:
            trade_date: Date to check (defaults to today if not provided)

        Returns:
            True if market is open, False if closed/holiday
        """
        from datetime import datetime, date
        import pytz

        # Check 1: Weekend check
        kst = pytz.timezone('Asia/Seoul')

        # Determine which date to check
        if trade_date:
            if isinstance(trade_date, date) and not isinstance(trade_date, datetime):
                check_date = datetime.combine(trade_date, datetime.min.time())
            else:
                check_date = trade_date
        else:
            check_date = datetime.now(kst)

        weekday = check_date.weekday()
        date_str = check_date.strftime('%A')

        if weekday >= 5:  # Saturday=5, Sunday=6
            logger.info(f"🚫 Market is closed (weekend: {date_str})")
            return False

        # Check 2: KIS domestic holiday-check API (국내휴장일조회).
        # Real-time quote APIs (e.g. inquire-investor) only reflect whether the
        # market is open *right now*, not whether `trade_date` was a market day —
        # so they can't be used to check a date other than today.
        bass_dt = check_date.strftime('%Y%m%d')
        endpoint = '/uapi/domestic-stock/v1/quotations/chk-holiday'
        tr_id = 'CTCA0903R'

        params = {
            'BASS_DT': bass_dt,
            'CTX_AREA_NK': '',
            'CTX_AREA_FK': ''
        }

        try:
            result = await self.request('GET', endpoint, tr_id, params=params)
            output = result.get('output', [])

            # API 는 응답했지만 해당 날짜 레코드가 없음 → 개장 여부를 확정할 수 없는 상태.
            # 이 경우도 '휴장'으로 단정하지 않고 판정 불가로 올린다.
            if not output:
                raise KISUnavailableError(
                    f"KIS 휴장일 조회 응답에 데이터가 없습니다 (BASS_DT={bass_dt})"
                )

            record = next((row for row in output if row.get('bass_dt') == bass_dt), output[0])
            # opnd_yn 은 KIS 가 명시적으로 알려주는 개장일 여부(Y/N)다.
            # 여기서의 return False 는 '진짜 휴장일'을 의미한다(연결 실패와 구분).
            is_open = record.get('opnd_yn') == 'Y'

            if is_open:
                logger.info(f"✅ Market is open ({check_date.strftime('%Y-%m-%d')})")
            else:
                logger.info(f"🚫 Market is closed (holiday: {check_date.strftime('%Y-%m-%d')})")

            return is_open

        except KISUnavailableError:
            raise

        except RuntimeError as e:
            error_str = str(e)

            # OAuth rate limit (403) - weekday check already passed, assume open
            if '403' in error_str or 'Forbidden' in error_str:
                logger.warning(f"⚠️ OAuth rate limit hit during market check. Assuming market is OPEN (weekday verified).")
                return True

            # 그 외 모든 오류(타임아웃/연결거부/rt_cd 오류 등)는 개장 여부를 판정할 수
            # 없는 상태다. '휴장일'로 삼키지 말고 접속 실패로 구분해서 올린다.
            logger.warning(f"⚠️ KIS 접속 실패로 개장 여부 판정 불가 (점검/네트워크 가능성): {e}")
            raise KISUnavailableError(
                f"KIS API 접속 실패로 개장 여부를 확인할 수 없습니다 (점검 또는 네트워크 문제일 수 있음): {e}"
            ) from e

    async def get_supply_demand(self, stock_code: str) -> Dict[str, int]:
        """
        Get foreign and institutional net buy amounts.

        API: FHKST01010900 (외국인·기관 순매수)

        Args:
            stock_code: 6-digit stock code

        Returns:
            Dict with keys:
                - foreign_net_buy: 외국인 순매수 금액 (원)
                - institutional_net_buy: 기관 순매수 금액 (원)

        Note:
            Returns zeros on market holidays (HTTP 500 errors)
        """
        endpoint = '/uapi/domestic-stock/v1/quotations/inquire-investor'
        tr_id = 'FHKST01010900'

        params = {
            'FID_COND_MRKT_DIV_CODE': 'J',
            'FID_INPUT_ISCD': stock_code
        }

        try:
            result = await self.request('GET', endpoint, tr_id, params=params)
            output = result.get('output', [])

            # Output is a list of daily data (30 days), use most recent (index 0)
            if not output or not isinstance(output, list):
                logger.warning(f"Empty or invalid output for {stock_code}")
                return {'foreign_net_buy': 0, 'institutional_net_buy': 0}

            # Get most recent day's data (index 0)
            output_data = output[0]

            # Correct key names: *_tr_pbmn (거래대금백만원, trading amount in millions of KRW)
            # NOT *_ntby_amt (그런 키는 존재하지 않음)
            foreign_net_buy = int(output_data.get('frgn_ntby_tr_pbmn', 0) or 0) * 1_000_000  # 백만원 → 원
            institutional_net_buy = int(output_data.get('orgn_ntby_tr_pbmn', 0) or 0) * 1_000_000  # 백만원 → 원

            logger.debug(f"Supply demand for {stock_code}: foreign={foreign_net_buy:,}원, institutional={institutional_net_buy:,}원")

            return {
                'foreign_net_buy': foreign_net_buy,
                'institutional_net_buy': institutional_net_buy
            }

        except RuntimeError as e:
            # Check if error is due to market holiday (HTTP 500)
            error_str = str(e)
            if '500' in error_str or 'Internal Server Error' in error_str:
                logger.warning(f"Market holiday detected for {stock_code}, using zero supply/demand data")
                return {'foreign_net_buy': 0, 'institutional_net_buy': 0}
            else:
                # Re-raise non-holiday errors
                raise

    async def get_daily_ohlcv(self, stock_code: str, days: int = 30) -> pd.DataFrame:
        """
        Get daily OHLCV data for volume and volatility calculation.

        API: FHKST01010400 (일봉 조회)

        Args:
            stock_code: 6-digit stock code
            days: Number of trading days to fetch (default: 30 for 20-day MA)

        Returns:
            DataFrame with columns:
                - trade_date: 거래일 (YYYYMMDD)
                - open: 시가
                - high: 고가
                - low: 저가
                - close: 종가
                - volume: 거래량

        Note:
            Returns empty DataFrame on market holidays or API errors
        """
        endpoint = '/uapi/domestic-stock/v1/quotations/inquire-daily-price'
        tr_id = 'FHKST01010400'

        params = {
            'FID_COND_MRKT_DIV_CODE': 'J',
            'FID_INPUT_ISCD': stock_code,
            'FID_PERIOD_DIV_CODE': 'D',  # Daily
            'FID_ORG_ADJ_PRC': '0'  # Not adjusted for rights
        }

        try:
            result = await self.request('GET', endpoint, tr_id, params=params)
            output_list = result.get('output', [])

            if not output_list:
                logger.warning(f"No daily data for {stock_code}")
                return pd.DataFrame()

            # Parse daily data (KIS returns newest first)
            data = []
            for item in output_list[:days]:
                data.append({
                    'trade_date': item['stck_bsop_date'],
                    'open': int(item['stck_oprc']),
                    'high': int(item['stck_hgpr']),
                    'low': int(item['stck_lwpr']),
                    'close': int(item['stck_clpr']),
                    'volume': int(item['acml_vol'])
                })

            df = pd.DataFrame(data)
            df = df.sort_values('trade_date').reset_index(drop=True)  # Oldest first

            logger.debug(f"Fetched {len(df)} days of OHLCV for {stock_code}")

            return df

        except RuntimeError as e:
            # Return empty DataFrame on API errors (including market holidays)
            error_str = str(e)
            if '500' in error_str or 'Internal Server Error' in error_str:
                logger.warning(f"Market holiday detected for {stock_code}, skipping OHLCV data")
            else:
                logger.error(f"Failed to fetch OHLCV for {stock_code}: {e}")
            return pd.DataFrame()

    async def get_daily_ohlcv_period(
        self,
        stock_code: str,
        days: int = 120
    ) -> pd.DataFrame:
        """
        기간별 일봉 OHLCV 조회 (장기 시계열용, 최근 ~100거래일).

        FHKST01010400(inquire-daily-price)은 한 번에 최대 ~30건만 반환하므로
        Prophet 학습에는 부족하다. 본 메서드는 기간 지정이 가능한
        FHKST03010100(inquire-daily-itemchartprice, [국내주식] 기본시세 >
        국내주식기간별시세(일/주/월/년) v1_국내주식-016)을 사용한다.

        설계 정정(6/12 장애 대응):
        과거 구현은 달력일 윈도우를 여러 번 쪼개 페이지네이션했고, 그 결과
        `20251208~20260110` 같은 어긋난 구간을 만들어 KIS가 500을 반복 반환했다.
        공식 문서/샘플에 따르면 본 엔드포인트는 단일 호출에서 [DATE_1, DATE_2] 구간 중
        "가장 최근 최대 100건"을 반환한다(예시: 20220101~20220809 → 최근 100건).
        따라서 충분히 넓은 단일 윈도우(오늘로부터 충분한 달력일 전 ~ 오늘)로 1회만
        호출하여 최근 100거래일을 안전하게 확보한다. FID_INPUT_DATE_2는 결코 미래일이
        될 수 없도록 항상 '오늘'로 고정한다.

        Args:
            stock_code: 6-digit stock code
            days: 확보 희망 거래일 수 (기본 120, 실제 상한은 엔드포인트 한도 ~100건)

        Returns:
            get_daily_ohlcv와 동일한 컬럼 구조의 DataFrame (oldest first):
                - trade_date: 거래일 (YYYYMMDD)
                - open: 시가
                - high: 고가
                - low: 저가
                - close: 종가
                - volume: 거래량

            API 오류·휴장 시 빈 DataFrame 반환(상위에서 get_daily_ohlcv로 폴백).

        Note:
            종목당 API 호출 비용: 1회.
        """
        endpoint = '/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice'
        tr_id = 'FHKST03010100'

        from datetime import datetime as _dt, timedelta as _td

        # 단일 윈도우: 오늘 ~ (오늘 − 충분한 달력일). 거래일 ≈ 0.7 × 달력일이므로
        # 100거래일(엔드포인트 한도)을 덮으려면 ~150 달력일이면 충분하다. 미래일 방지를
        # 위해 종료일은 항상 오늘로 고정한다.
        end_dt = _dt.now()
        total_calendar_days_needed = int(max(days, 100) / 0.7) + 20
        start_dt = end_dt - _td(days=total_calendar_days_needed)

        params = {
            'FID_COND_MRKT_DIV_CODE': 'J',
            'FID_INPUT_ISCD': stock_code,
            'FID_INPUT_DATE_1': start_dt.strftime('%Y%m%d'),
            'FID_INPUT_DATE_2': end_dt.strftime('%Y%m%d'),  # 항상 오늘 (미래일 금지)
            'FID_PERIOD_DIV_CODE': 'D',  # Daily
            'FID_ORG_ADJ_PRC': '0'  # 0:수정주가
        }

        try:
            result = await self.request('GET', endpoint, tr_id, params=params)
        except RuntimeError as e:
            error_str = str(e)
            if '500' in error_str or 'Internal Server Error' in error_str:
                logger.warning(f"Market holiday/server error for {stock_code} (period OHLCV)")
            else:
                logger.error(f"Failed to fetch period OHLCV for {stock_code}: {e}")
            return pd.DataFrame()

        # 기간별 시세는 일자별 배열을 output2로 반환한다
        output_list = result.get('output2', []) or []

        if not output_list:
            logger.warning(f"No period OHLCV rows for {stock_code}")
            return pd.DataFrame()

        collected: Dict[str, Dict] = {}
        for item in output_list:
            trade_date = item.get('stck_bsop_date')
            # 빈 행(주말 등) 또는 결측 행 스킵
            if not trade_date or not item.get('stck_clpr'):
                continue
            if trade_date in collected:
                continue
            try:
                collected[trade_date] = {
                    'trade_date': trade_date,
                    'open': int(item['stck_oprc']),
                    'high': int(item['stck_hgpr']),
                    'low': int(item['stck_lwpr']),
                    'close': int(item['stck_clpr']),
                    'volume': int(item['acml_vol'])
                }
            except (ValueError, KeyError, TypeError) as parse_err:
                logger.debug(f"Skipping malformed period OHLCV row for {stock_code}: {parse_err}")
                continue

        if not collected:
            logger.warning(f"No period OHLCV data assembled for {stock_code}")
            return pd.DataFrame()

        df = pd.DataFrame(list(collected.values()))
        df = df.sort_values('trade_date').reset_index(drop=True)  # Oldest first

        # 최근 days 거래일만 유지
        if len(df) > days:
            df = df.tail(days).reset_index(drop=True)

        logger.debug(f"Assembled {len(df)} trading days of period OHLCV for {stock_code}")

        return df

    async def get_daily_trade_volume(
        self,
        stock_code: str,
        days: int = 120
    ) -> pd.DataFrame:
        """
        종목별 일별 매수/매도 체결량 조회 (순매수 비율 계산용).

        FHKST03010800(inquire-daily-trade-volume, [국내주식] 시세분석 >
        종목별일별매수매도체결량 v1_국내주식-056)은 일자별 총 매수체결량
        ·총 매도체결량을 반환하며, 한 번의 호출에 최대 100건(거래일)을 조회한다.

        이 값으로 일별 순매수 비율(= 매수체결량 / 총체결량)을 계산하면,
        총 거래량만으로는 알 수 없는 "매수 주도 vs 매도 주도" 방향성을 확보한다.

        파라미터 정정(6/12 장애 대응):
        과거 구현은 FID_INPUT_DATE_1/2(기간 지정) + 직접 페이지네이션을 사용했고,
        KIS가 `ERROR INPUT FIELD NOT FOUND [FID_COND_MRKT_DIV_CODE_1]` 또는 500을
        반환했다. 공식 샘플(open-trading-api/.../inquire_daily_trade_volume.py)은
        FID_COND_MRKT_DIV_CODE / FID_INPUT_ISCD / FID_PERIOD_DIV_CODE 3개 필수 필드만으로
        호출하며 날짜는 선택(미지정 시 최근 100건 반환)이다. 본 구현은 그 정식 형태를
        따른다(단일 호출, 날짜 미지정). 100건이면 Prophet 학습(>=60)에 충분하다.

        출력 필드(공식 COLUMN_MAPPING 기준):
        - output2(array, 일자별): stck_bsop_date, total_shnu_qty(총 매수 수량),
          total_seln_qty(총 매도 수량)
        - 일부 응답은 shnu_cnqn_smtn/seln_cnqn_smtn(체결량 합계)로 내려오므로
          파서를 두 변형 모두 허용하도록 관대하게(tolerant) 처리한다.

        Args:
            stock_code: 6-digit stock code
            days: 확보할 거래일 수 (기본 120, 실제는 엔드포인트 한도인 ~100건까지)

        Returns:
            DataFrame (oldest first), 컬럼:
                - trade_date: 거래일 (YYYYMMDD)
                - buy_volume: 매수 체결량
                - sell_volume: 매도 체결량
                - total_volume: 총 체결량 (buy_volume + sell_volume)

            API 오류·휴장 시 빈 DataFrame 반환(상위에서 가격기반 프록시로 폴백).

        Note:
            종목당 API 호출 비용: 1회.
        """
        endpoint = '/uapi/domestic-stock/v1/quotations/inquire-daily-trade-volume'
        tr_id = 'FHKST03010800'

        # 공식 샘플과 동일한 필수 3필드만 전송 (날짜 미지정 → 최근 100건 반환).
        # _1 접미사 필드는 KIS 내부 오류 메시지일 뿐 실제 요구 파라미터가 아니다.
        params = {
            'FID_COND_MRKT_DIV_CODE': 'J',  # J:KRX
            'FID_INPUT_ISCD': stock_code,
            'FID_PERIOD_DIV_CODE': 'D'  # Daily
        }

        try:
            result = await self.request('GET', endpoint, tr_id, params=params)
        except RuntimeError as e:
            error_str = str(e)
            if '500' in error_str or 'Internal Server Error' in error_str:
                logger.warning(f"Market holiday/server error for {stock_code} (trade volume)")
            else:
                logger.error(f"Failed to fetch daily trade volume for {stock_code}: {e}")
            return pd.DataFrame()

        output_list = result.get('output2', []) or []

        if not output_list:
            logger.warning(f"No daily trade-volume rows for {stock_code}")
            return pd.DataFrame()

        def _to_int(item: Dict, *keys: str) -> int:
            """여러 후보 키 중 처음으로 존재하는 값을 int로 변환(없으면 0)."""
            for key in keys:
                if key in item and item.get(key) not in (None, ''):
                    try:
                        return int(item.get(key))
                    except (ValueError, TypeError):
                        continue
            return 0

        collected: Dict[str, Dict] = {}
        for item in output_list:
            trade_date = item.get('stck_bsop_date')
            if not trade_date or trade_date in collected:
                continue
            # 총 매수/매도 수량: total_shnu_qty/total_seln_qty 우선,
            # 변형 응답(shnu_cnqn_smtn/seln_cnqn_smtn)도 허용
            buy_volume = _to_int(item, 'total_shnu_qty', 'shnu_cnqn_smtn')
            sell_volume = _to_int(item, 'total_seln_qty', 'seln_cnqn_smtn')

            collected[trade_date] = {
                'trade_date': trade_date,
                'buy_volume': buy_volume,
                'sell_volume': sell_volume,
                'total_volume': buy_volume + sell_volume
            }

        if not collected:
            logger.warning(f"No daily trade-volume data assembled for {stock_code}")
            return pd.DataFrame()

        df = pd.DataFrame(list(collected.values()))
        df = df.sort_values('trade_date').reset_index(drop=True)  # Oldest first

        if len(df) > days:
            df = df.tail(days).reset_index(drop=True)

        logger.debug(f"Assembled {len(df)} trading days of trade volume for {stock_code}")

        return df

    async def get_minute_data(self, stock_code: str, date: str) -> pd.DataFrame:
        """
        Get minute-level price data for morning_return calculation (09:00-10:00).

        API: FHKST03010200 (주식당일분봉조회, inquire-time-itemchartprice)

        TR 정정(morning_return=0 장애 대응):
        과거 구현은 TR_ID 'FHKST01010600' 을 사용했는데, 이는 분봉이 아니라
        "주식현재가 회원사"(inquire-member) 조회용 TR 이다. 그 응답에는 output2
        (분봉 배열)가 존재하지 않아 항상 빈 DataFrame 을 반환했고, 결과적으로
        morning_return 이 모든 종목·모든 날짜에서 0.0 으로 저장됐다.
        본 엔드포인트(inquire-time-itemchartprice)의 정식 TR 은 'FHKST03010200'
        이며, output2 에 분봉 배열을 반환한다.

        창(window) 구성:
        본 TR 은 FID_INPUT_HOUR_1 을 "조회 종료 시각"으로 해석하여 그 시각까지의
        최근 ~30개 분봉만 반환한다(한 호출당 최대 30건). 따라서 09:00~10:00 전체
        (60분)를 확보하려면 두 번 호출해 합친다:
          - H=093000 → 09:01~09:30
          - H=100000 → 09:31~10:00
        가장 이른 분봉(≈09:01)의 stck_oprc 가 당일 시가(09:00 시가)에 해당한다.

        한계(정직한 명시):
        본 TR 은 FID_INPUT_DATE_1 을 무시하고 "가장 최근 거래일"의 분봉만 반환한다
        (과거 임의 일자 분봉 조회 불가). 따라서 date 인자는 호환을 위해 받지만
        실제로는 KIS 가 제공하는 최신 세션의 09:00~10:00 데이터가 반환된다.

        Args:
            stock_code: 6-digit stock code
            date: Target date in YYYYMMDD format (KIS 가 무시; 최신 세션 반환)

        Returns:
            DataFrame with columns (chronological order):
                - time: 시각 (HHMMSS)
                - open_price: 시가
                - high_price: 고가
                - low_price: 저가
                - close_price: 종가
                - volume: 거래량
            빈 응답/오류 시 빈 DataFrame.
        """
        endpoint = '/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice'
        tr_id = 'FHKST03010200'

        async def _fetch_window(end_hour: str) -> List[Dict]:
            """end_hour 까지의 최근 ~30개 분봉(raw output2 rows)을 반환."""
            params = {
                'FID_ETC_CLS_CODE': '',
                'FID_COND_MRKT_DIV_CODE': 'J',
                'FID_INPUT_ISCD': stock_code,
                'FID_INPUT_HOUR_1': end_hour,  # 조회 종료 시각 (HHMMSS)
                'FID_PW_DATA_INCU_YN': 'Y'  # 과거 데이터 포함
            }
            try:
                result = await self.request('GET', endpoint, tr_id, params=params)
            except RuntimeError as e:
                logger.warning(f"Minute data fetch failed for {stock_code} (end={end_hour}): {e}")
                return []
            return result.get('output2', []) or []

        # 09:00~10:00 전체를 두 번의 호출로 확보 (각 ~30건, 종료시각 기준)
        rows = await _fetch_window('093000')
        rows += await _fetch_window('100000')

        if not rows:
            logger.warning(f"No minute data for {stock_code} (latest session)")
            return pd.DataFrame()

        # 09:00~10:00 구간만, 시각별 중복 제거하며 파싱
        seen_times = set()
        data = []
        for item in rows:
            time_str = item.get('stck_cntg_hour')
            if not time_str or len(time_str) < 4:
                continue

            # 09:00:00 ~ 10:00:00 윈도우 (10:00 포함)
            hhmm = time_str[:4]
            if hhmm < '0900' or hhmm > '1000':
                continue
            if time_str in seen_times:
                continue
            seen_times.add(time_str)

            try:
                data.append({
                    'time': time_str,
                    'open_price': int(item['stck_oprc']),
                    'high_price': int(item['stck_hgpr']),
                    'low_price': int(item['stck_lwpr']),
                    'close_price': int(item['stck_prpr']),
                    'volume': int(item.get('cntg_vol', 0) or 0)
                })
            except (ValueError, KeyError, TypeError) as parse_err:
                logger.debug(f"Skipping malformed minute row for {stock_code}: {parse_err}")
                continue

        if not data:
            logger.warning(f"No 09:00-10:00 minute rows assembled for {stock_code}")
            return pd.DataFrame()

        df = pd.DataFrame(data)
        df = df.sort_values('time').reset_index(drop=True)  # Chronological order

        logger.debug(f"Fetched {len(df)} minutes (09:00-10:00) for {stock_code}")

        return df

    @staticmethod
    def _parse_kis_float(value: Optional[str]) -> Optional[float]:
        """
        KIS 시세 응답의 숫자 문자열을 float 로 안전 변환.

        KIS inquire-price 응답의 per/pbr/eps 등은 문자열이며, 적자/결측 종목은
        ''(빈 문자열) 또는 '0' / '0.00' 으로 내려온다. 이런 값은 의미 있는 지표가
        아니므로 None 으로 정규화한다 (PER=0 은 현실적으로 존재하지 않음).

        Args:
            value: KIS output 의 문자열 값

        Returns:
            float 값, 또는 빈값/0/파싱 실패 시 None
        """
        if value is None:
            return None
        try:
            cleaned = str(value).replace(',', '').strip()
            if cleaned == '':
                return None
            parsed = float(cleaned)
            # 0 은 적자/미산출을 의미하는 placeholder → None 으로 처리
            if parsed == 0.0:
                return None
            return parsed
        except (ValueError, TypeError):
            return None

    async def get_current_price(self, stock_code: str) -> Dict[str, Optional[float]]:
        """
        Get current stock price (and valuation metrics) from KIS.

        Args:
            stock_code: 6-digit stock code

        Returns:
            Dict with:
                - current_price (현재가, int; 실패 시 0)
                - per (주가수익비율, float 또는 None)
                - pbr (주가순자산비율, float 또는 None)
                - eps (주당순이익, float 또는 None)

        API: FHKST01010100 (주식현재가 시세).
        output 에 per/pbr/eps 가 함께 포함되므로 별도 호출 없이 밸류에이션을 얻는다.
        적자/결측 종목의 per/eps 는 '0'/'' 로 내려오며 None 으로 정규화한다.
        """
        logger.debug(f"Fetching current price for {stock_code}")

        endpoint = '/uapi/domestic-stock/v1/quotations/inquire-price'
        tr_id = 'FHKST01010100'

        params = {
            'FID_COND_MRKT_DIV_CODE': 'J',
            'FID_INPUT_ISCD': stock_code
        }

        try:
            # self.request() 가 이미 self.semaphore 로 rate limiting 을 건다.
            # 여기서 다시 감싸면 같은(재진입 불가) semaphore 를 한 호출이 두 번 점유해
            # 유효 동시성이 반토막 나고, 동시 호출 수가 세마포어 크기에 도달하면
            # 모든 호출이 서로의 두 번째 획득을 기다리며 교착된다.
            result = await self.request('GET', endpoint, tr_id, params)

            # request() 는 rt_cd != '0' 이면 RuntimeError 를 던지므로 여기서는 성공만 온다.
            output = result['output']
            current_price = int(output['stck_prpr'])  # 주식 현재가

            per = self._parse_kis_float(output.get('per'))
            pbr = self._parse_kis_float(output.get('pbr'))
            eps = self._parse_kis_float(output.get('eps'))

            logger.debug(
                f"Price/valuation for {stock_code}: {current_price:,}원, "
                f"PER={per}, PBR={pbr}, EPS={eps}"
            )

            return {
                'current_price': current_price,
                'per': per,
                'pbr': pbr,
                'eps': eps
            }

        except Exception as e:
            logger.exception(f"Exception while fetching current price for {stock_code}: {e}")
            return {'current_price': 0, 'per': None, 'pbr': None, 'eps': None}

    async def get_valuation(self, stock_code: str) -> Dict[str, Optional[float]]:
        """
        Fetch valuation metrics (PER/PBR/EPS) for a stock from KIS inquire-price.

        get_current_price 의 얇은 래퍼. 재무지표(stock_financial.per) 보강 단계에서
        의도를 명확히 드러내기 위해 제공한다.

        Args:
            stock_code: 6-digit stock code

        Returns:
            Dict with keys per/pbr/eps (각각 float 또는 None)
        """
        price_data = await self.get_current_price(stock_code)
        return {
            'per': price_data.get('per'),
            'pbr': price_data.get('pbr'),
            'eps': price_data.get('eps')
        }

    async def get_valuations_for_stocks(
        self,
        stock_codes: List[str]
    ) -> Dict[str, Optional[float]]:
        """
        Fetch PER for multiple stocks (rate-limited, sequential).

        stock_financial.per 보강용. Stage 2-1(재무) 단계에서 DART 가 채우지 못하는
        PER 을 KIS 시세로 채우기 위해 사용한다. 각 호출은 get_current_price 의
        Semaphore + 0.2s 지연을 통해 5 req/sec 제한을 준수한다.

        Args:
            stock_codes: 6-digit 종목코드 리스트

        Returns:
            {stock_code: per(float|None)} 매핑
        """
        per_map: Dict[str, Optional[float]] = {}
        for stock_code in stock_codes:
            try:
                valuation = await self.get_valuation(stock_code)
                per_map[stock_code] = valuation.get('per')
            except Exception as e:
                logger.warning(f"Valuation fetch failed for {stock_code}: {e}")
                per_map[stock_code] = None
        return per_map

    async def get_kospi_index(self, trade_date: Optional[str] = None) -> Dict[str, float]:
        """
        Get KOSPI index data for market daily summary.

        API: FHKUP03500100 (업종/지수 조회)

        Args:
            trade_date: Trading date in YYYYMMDD format (optional, defaults to today)

        Returns:
            Dict with keys:
                - kospi_index: KOSPI 지수값
                - kospi_change_rate: 전일 대비 등락률 (%)
                - kospi_volume: 거래량
                - kospi_trade_value: 거래대금 (백만원)
        """
        logger.info("Fetching KOSPI index data")

        # Default to today if no trade_date provided
        if trade_date is None:
            from datetime import datetime
            trade_date = datetime.now().strftime('%Y%m%d')

        endpoint = '/uapi/domestic-stock/v1/quotations/inquire-index-price'
        tr_id = 'FHKUP03500100'

        params = {
            'FID_COND_MRKT_DIV_CODE': 'U',  # 업종
            'FID_INPUT_ISCD': '0001',  # KOSPI 코드
            'FID_INPUT_DATE_1': trade_date,  # 조회 시작일 (YYYYMMDD)
            'FID_INPUT_DATE_2': trade_date,  # 조회 종료일 (YYYYMMDD)
            'FID_PERIOD_DIV_CODE': 'D'  # Daily (일별)
        }

        try:
            result = await self.request('GET', endpoint, tr_id, params=params)

            # IMPORTANT: API returns 'output1' (not 'output')
            output = result.get('output1', {})

            kospi_index = float(output.get('bstp_nmix_prpr', 0))  # 업종 지수
            kospi_change_rate = float(output.get('bstp_nmix_prdy_ctrt', 0))  # 전일 대비 등락률
            kospi_volume = int(output.get('acml_vol', 0))  # 누적 거래량
            kospi_trade_value = int(output.get('acml_tr_pbmn', 0))  # 누적 거래대금 (백만원)

            logger.info(f"KOSPI Index: {kospi_index:.2f}, Change: {kospi_change_rate:.2f}%, Volume: {kospi_volume:,}")

            return {
                'kospi_index': kospi_index,
                'kospi_change_rate': kospi_change_rate,
                'kospi_volume': kospi_volume,
                'kospi_trade_value': kospi_trade_value
            }

        except Exception as e:
            logger.error(f"Error fetching KOSPI index: {e}")
            return {
                'kospi_index': 0.0,
                'kospi_change_rate': 0.0,
                'kospi_volume': 0,
                'kospi_trade_value': 0
            }

    async def get_holdings(self) -> List[str]:
        """
        Get list of currently held stock codes from KIS API.

        API: VTTC8434R (모의투자 잔고조회) / TTTC8434R (실전투자 잔고조회)

        Returns:
            List of 6-digit stock codes currently in portfolio
            Empty list if error or no holdings
        """
        logger.info("Fetching holdings from KIS API")

        endpoint = '/uapi/domestic-stock/v1/trading/inquire-balance'
        tr_id = 'VTTC8434R'  # Auto-converted to TTTC8434R in REAL mode

        params = {
            'CANO': os.getenv('KIS_ACCOUNT_NUMBER', ''),  # 종합계좌번호
            'ACNT_PRDT_CD': os.getenv('KIS_ACCOUNT_PRODUCT_CODE', '01'),  # 계좌상품코드 (01: 종합)
            'AFHR_FLPR_YN': 'N',  # 시간외단일가여부 (N: 정규장)
            'OFL_YN': '',  # 오프라인여부
            'INQR_DVSN': '01',  # 조회구분 (01: 대출일별, 02: 종목별)
            'UNPR_DVSN': '01',  # 단가구분 (01: 기본)
            'FUND_STTL_ICLD_YN': 'N',  # 펀드결제분포함여부 (N: 미포함)
            'FNCG_AMT_AUTO_RDPT_YN': 'N',  # 융자금액자동상환여부 (N: 미상환)
            'PRCS_DVSN': '01',  # 처리구분 (01: 전일매매포함)
            'CTX_AREA_FK100': '',  # 연속조회검색조건100
            'CTX_AREA_NK100': ''  # 연속조회키100
        }

        try:
            result = await self.request('GET', endpoint, tr_id, params=params)

            # output1: 보유 종목 리스트
            holdings_list = result.get('output1', [])

            if not holdings_list:
                logger.info("No holdings found in portfolio")
                return []

            # Extract stock codes (6-digit) from holdings
            stock_codes = []
            for holding in holdings_list:
                stock_code = holding.get('pdno', '')  # 상품번호 (종목코드)
                quantity = int(holding.get('hldg_qty', 0))  # 보유수량

                # Only include holdings with positive quantity
                if stock_code and quantity > 0:
                    stock_codes.append(stock_code)

            logger.info(f"Found {len(stock_codes)} holdings: {stock_codes}")
            return stock_codes

        except Exception as e:
            logger.error(f"Error fetching holdings: {e}")
            logger.warning("Continuing without holdings (will only use Top 30 filtered stocks)")
            return []

    async def fetch_stock_data_parallel(self, stock_codes: List[str]) -> pd.DataFrame:
        """
        Fetch supply/demand and OHLCV data for multiple stocks.

        이름과 달리 **순차 처리**한다. 종목을 하나씩 돌며 수급 → OHLCV 순으로
        요청하고 종목 사이에 0.1초를 쉰다. Semaphore(5) 로 동시 요청을 열어도 KIS
        API 성공률이 떨어져 순차로 되돌린 것이며(:1106 주석 참조), 이름은 호출부
        호환을 위해 유지한다.

        Args:
            stock_codes: List of 6-digit stock codes

        Returns:
            DataFrame with columns:
                - stock_code
                - foreign_net_buy
                - institutional_net_buy
                - volume_ratio (전날 거래량 / 20일 평균 거래량)
                - price_volatility ((고가-저가) / 저가)
        """
        logger.info(f"Fetching data for {len(stock_codes)} stocks in batches")

        async def fetch_single_stock(stock_code: str) -> Optional[Dict]:
            try:
                # Fetch supply/demand and OHLCV SEQUENTIALLY (not concurrently)
                # This matches individual test pattern that achieves 80/100 success
                # Concurrent requests (even with Semaphore) overwhelm KIS API

                # Step 1: Fetch supply/demand first
                try:
                    supply_demand = await self.get_supply_demand(stock_code)
                except Exception as e:
                    logger.warning(f"Supply/demand fetch failed for {stock_code}: {e}")
                    return None

                # Step 2: Fetch OHLCV second (after supply/demand completes)
                try:
                    ohlcv_df = await self.get_daily_ohlcv(stock_code, days=21)  # 20-day MA + 1 latest
                except Exception as e:
                    logger.warning(f"OHLCV fetch failed for {stock_code}: {e}")
                    return None

                if ohlcv_df.empty or len(ohlcv_df) < 2:
                    logger.warning(f"Insufficient OHLCV data for {stock_code}: {len(ohlcv_df)} rows")
                    return None

                # Calculate volume ratio (전날 거래량 / 20일 평균 거래량)
                if len(ohlcv_df) >= 21:
                    volume_ma_20 = ohlcv_df['volume'].iloc[:-1].tail(20).mean()
                    latest_volume = ohlcv_df['volume'].iloc[-1]
                    volume_ratio = latest_volume / volume_ma_20 if volume_ma_20 > 0 else 1.0
                else:
                    # Fallback if less than 21 days
                    volume_ratio = 1.0

                # Calculate price volatility ((고가-저가) / 저가)
                latest_day = ohlcv_df.iloc[-1]
                price_volatility = (latest_day['high'] - latest_day['low']) / latest_day['low'] if latest_day['low'] > 0 else 0.0

                # Calculate close_position ((종가-저가)/(고가-저가)) for Stage 2 reuse
                high_low_diff = latest_day['high'] - latest_day['low']
                close_position = (latest_day['close'] - latest_day['low']) / high_low_diff if high_low_diff > 0 else 0.5

                return {
                    'stock_code': stock_code,
                    'foreign_net_buy': supply_demand['foreign_net_buy'],
                    'institutional_net_buy': supply_demand['institutional_net_buy'],
                    'volume_ratio': volume_ratio,
                    'price_volatility': price_volatility,
                    'close_position': close_position  # Add for Stage 2 reuse
                }

            except Exception as e:
                logger.error(f"Error fetching data for {stock_code}: {e}")
                return None

        # Process stocks SEQUENTIALLY (one at a time) to match individual test success rate
        # Individual test achieves 80/100 success, pipeline should match this
        # Root cause: Concurrent requests overwhelm KIS API despite Semaphore
        # Solution: Process one stock at a time with 0.1s delay (matching individual test)
        valid_results = []
        total_stocks = len(stock_codes)

        logger.info(f"===== COLLECTION DEBUG START: {total_stocks} stocks =====")

        for idx, stock_code in enumerate(stock_codes, 1):
            logger.info(f"[{idx}/{total_stocks}] Processing: {stock_code}")

            # Fetch single stock data
            result = await fetch_single_stock(stock_code)

            if result is not None:
                valid_results.append(result)
                logger.info(f"[{idx}/{total_stocks}] {stock_code}: ✅ SUCCESS (Total: {len(valid_results)})")
            else:
                logger.warning(f"[{idx}/{total_stocks}] {stock_code}: ❌ FAILED (Total success: {len(valid_results)})")

            # Rate limiting: 0.1s delay between stocks (matching individual test exactly)
            if idx < total_stocks:  # Don't delay after last stock
                await asyncio.sleep(0.1)

        if not valid_results:
            logger.error("No valid stock data fetched")
            return pd.DataFrame()

        df = pd.DataFrame(valid_results)
        logger.info(f"Successfully fetched data for {len(df)}/{len(stock_codes)} stocks (success rate: {len(df)/len(stock_codes)*100:.1f}%)")

        return df
