"""Unit tests for collectors.kis_client (KISClient).

네트워크 차단 전략:
  - KISClient.__init__ 이 .env 를 load_dotenv(override=True) 로 다시 읽으므로,
    load_dotenv 를 no-op 으로 패치한 뒤 테스트용 자격증명을 환경변수로 주입한다.
    (그렇지 않으면 실제 KIS 키가 로드되고, 실수로 실 API 를 때릴 위험이 있다.)
  - aiohttp.ClientSession 을 가짜 세션으로 대체하거나, 상위 메서드 테스트에서는
    KISClient.request 자체를 AsyncMock 으로 대체한다.
"""
import asyncio
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch

import aiohttp
import pandas as pd
import pytest

from collectors import kis_client as kis_module
from collectors.kis_client import KISClient, KISUnavailableError


class FakeResponse:
    """aiohttp 응답 스텁."""

    def __init__(self, status=200, json_data=None, text_data='', raise_exc=None, on_enter=None):
        self.status = status
        self._json = json_data
        self._text = text_data
        self._raise_exc = raise_exc
        self._on_enter = on_enter

    async def __aenter__(self):
        if self._on_enter is not None:
            await self._on_enter()
        return self

    async def __aexit__(self, *args):
        return False

    def raise_for_status(self):
        if self._raise_exc is not None:
            raise self._raise_exc

    async def json(self, **kwargs):
        if isinstance(self._json, Exception):
            raise self._json
        return self._json

    async def text(self):
        return self._text


class FakeSession:
    """aiohttp.ClientSession 스텁. 호출 인자를 기록하고 준비된 응답을 순서대로 반환."""

    def __init__(self, responses=None, exc=None):
        if responses is None:
            responses = []
        elif not isinstance(responses, list):
            responses = [responses]
        self.responses = responses
        self.exc = exc
        self.requests = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    def _handle(self, method, url, kwargs):
        self.requests.append({'method': method, 'url': url, **kwargs})
        if self.exc is not None:
            raise self.exc
        if len(self.responses) == 1:
            return self.responses[0]
        return self.responses.pop(0)

    def get(self, url, **kwargs):
        return self._handle('GET', url, kwargs)

    def post(self, url, **kwargs):
        return self._handle('POST', url, kwargs)


def patch_session(session):
    return patch.object(kis_module.aiohttp, 'ClientSession', MagicMock(return_value=session))


def http_error(status, message='error'):
    return aiohttp.ClientResponseError(
        request_info=MagicMock(), history=(), status=status, message=message
    )


def ok(result=None):
    """rt_cd='0' 인 KIS 성공 응답 본문."""
    body = {'rt_cd': '0', 'msg_cd': 'MCA00000', 'msg1': '정상처리 되었습니다.'}
    body.update(result or {})
    return body


@pytest.fixture(autouse=True)
def no_rate_limit_delay():
    """request() 의 0.2s 레이트리밋 지연을 제거해 테스트를 빠르게 유지."""
    with patch.object(kis_module, 'KIS_REQUEST_DELAY', 0):
        yield


@pytest.fixture
def client(monkeypatch):
    """실 .env 를 읽지 않는 테스트용 KISClient (VIRTUAL 모드)."""
    monkeypatch.setattr(kis_module, 'load_dotenv', lambda *a, **kw: None)
    monkeypatch.setenv('KIS_APP_KEY', 'test-app-key')
    monkeypatch.setenv('KIS_APP_SECRET', 'test-app-secret')
    monkeypatch.setenv('KIS_MODE', 'VIRTUAL')
    monkeypatch.setenv('KIS_BASE_URL', 'https://mock.kis.test')
    c = KISClient()
    # 토큰 조회를 유발하지 않도록 유효한 캐시 토큰을 심어 둔다.
    c.access_token = 'cached-token'
    c.token_expires_at = datetime.now() + timedelta(hours=1)
    return c


class TestInit:
    def test_loads_credentials_and_mode(self, client):
        assert client.app_key == 'test-app-key'
        assert client.app_secret == 'test-app-secret'
        assert client.mode == 'VIRTUAL'
        assert client.base_url == 'https://mock.kis.test'

    def test_semaphore_allows_five_concurrent_requests(self, client):
        assert client.semaphore._value == kis_module.KIS_MAX_REQUESTS_PER_SECOND == 5

    @pytest.mark.parametrize('missing', ['KIS_APP_KEY', 'KIS_APP_SECRET'])
    def test_missing_credentials_raise(self, monkeypatch, missing):
        monkeypatch.setattr(kis_module, 'load_dotenv', lambda *a, **kw: None)
        monkeypatch.setenv('KIS_APP_KEY', 'k')
        monkeypatch.setenv('KIS_APP_SECRET', 's')
        monkeypatch.delenv(missing, raising=False)

        with pytest.raises(ValueError, match='KIS_APP_KEY and KIS_APP_SECRET'):
            KISClient()

    def test_defaults_to_virtual_mode(self, monkeypatch):
        monkeypatch.setattr(kis_module, 'load_dotenv', lambda *a, **kw: None)
        monkeypatch.setenv('KIS_APP_KEY', 'k')
        monkeypatch.setenv('KIS_APP_SECRET', 's')
        monkeypatch.delenv('KIS_MODE', raising=False)

        assert KISClient().mode == 'VIRTUAL'


class TestConvertTrId:
    @pytest.mark.parametrize('base,expected', [
        ('TTTC8434R', 'VTTC8434R'),   # 실전 → 모의 변환
        ('VTTC8434R', 'VTTC8434R'),   # 이미 모의
        ('FHKST01010900', 'FHKST01010900'),  # 시세 TR 은 변환 대상 아님
    ])
    def test_virtual_mode(self, client, base, expected):
        assert client.convert_tr_id(base) == expected

    @pytest.mark.parametrize('base,expected', [
        ('VTTC8434R', 'TTTC8434R'),
        ('TTTC8434R', 'TTTC8434R'),
        ('FHKST01010900', 'FHKST01010900'),
    ])
    def test_real_mode(self, client, base, expected):
        client.mode = 'REAL'
        assert client.convert_tr_id(base) == expected

    @pytest.mark.parametrize('value', [None, '', 'ABC'])
    def test_short_or_none_passthrough(self, client, value):
        assert client.convert_tr_id(value) == value


class TestGetAccessToken:
    async def test_fetches_and_caches_token(self, client):
        client.access_token = None
        client.token_expires_at = None
        session = FakeSession(FakeResponse(200, {'access_token': 'fresh-token'}))

        with patch_session(session):
            token = await client.get_access_token()

        assert token == 'fresh-token'
        assert client.access_token == 'fresh-token'
        assert client.token_expires_at > datetime.now() + timedelta(hours=23)
        req = session.requests[0]
        assert req['url'] == 'https://mock.kis.test/oauth2/tokenP'
        assert req['json'] == {
            'grant_type': 'client_credentials',
            'appkey': 'test-app-key',
            'appsecret': 'test-app-secret',
        }

    async def test_valid_cached_token_skips_http(self, client):
        session = FakeSession(FakeResponse(200, {'access_token': 'should-not-be-used'}))

        with patch_session(session):
            token = await client.get_access_token()

        assert token == 'cached-token'
        assert session.requests == []

    async def test_expired_token_is_refreshed(self, client):
        client.token_expires_at = datetime.now() - timedelta(seconds=1)
        session = FakeSession(FakeResponse(200, {'access_token': 'renewed'}))

        with patch_session(session):
            assert await client.get_access_token() == 'renewed'

    async def test_concurrent_calls_fetch_token_once(self, client):
        """토큰 요청은 1분 1회 제한이 있어 asyncio.Lock 으로 직렬화되어야 한다."""
        client.access_token = None
        client.token_expires_at = None

        async def slow_enter():
            await asyncio.sleep(0.01)

        session = FakeSession(FakeResponse(200, {'access_token': 'once'}, on_enter=slow_enter))

        with patch_session(session):
            tokens = await asyncio.gather(*[client.get_access_token() for _ in range(5)])

        assert tokens == ['once'] * 5
        assert len(session.requests) == 1

    @pytest.mark.parametrize('status', [401, 403, 429, 500])
    async def test_non_200_raises_runtime_error(self, client, status):
        client.access_token = None
        session = FakeSession(FakeResponse(status, text_data='denied'))

        with patch_session(session):
            with pytest.raises(RuntimeError, match=f'KIS OAuth failed: HTTP {status}'):
                await client.get_access_token()

    async def test_client_error_raises_runtime_error(self, client):
        client.access_token = None
        session = FakeSession(exc=aiohttp.ClientConnectionError('refused'))

        with patch_session(session):
            with pytest.raises(RuntimeError, match='KIS OAuth failed'):
                await client.get_access_token()


class TestRequest:
    async def test_get_success_sends_auth_headers_and_converted_tr_id(self, client):
        session = FakeSession(FakeResponse(200, ok({'output': {'a': 1}})))

        with patch_session(session):
            result = await client.request('GET', '/uapi/test', 'TTTC8434R', params={'k': 'v'})

        assert result['output'] == {'a': 1}
        req = session.requests[0]
        assert req['url'] == 'https://mock.kis.test/uapi/test'
        assert req['params'] == {'k': 'v'}
        assert req['headers']['authorization'] == 'Bearer cached-token'
        assert req['headers']['appkey'] == 'test-app-key'
        assert req['headers']['tr_id'] == 'VTTC8434R'  # VIRTUAL 모드 변환
        assert req['headers']['custtype'] == 'P'

    async def test_post_sends_json_body(self, client):
        session = FakeSession(FakeResponse(200, ok()))

        with patch_session(session):
            await client.request('POST', '/uapi/order', 'VTTC0802U', json_data={'qty': 1})

        assert session.requests[0]['method'] == 'POST'
        assert session.requests[0]['json'] == {'qty': 1}

    async def test_non_zero_rt_cd_raises_with_message(self, client):
        session = FakeSession(FakeResponse(200, {'rt_cd': '1', 'msg1': '초당 거래건수를 초과하였습니다'}))

        with patch_session(session):
            with pytest.raises(RuntimeError, match='초당 거래건수를 초과하였습니다'):
                await client.request('GET', '/uapi/test', 'FHKST01010900')

    async def test_rt_cd_error_without_message_uses_unknown(self, client):
        session = FakeSession(FakeResponse(200, {'rt_cd': '7'}))

        with patch_session(session):
            with pytest.raises(RuntimeError, match='Unknown error'):
                await client.request('GET', '/uapi/test', 'FHKST01010900')

    async def test_unsupported_method_raises_value_error(self, client):
        with patch_session(FakeSession(FakeResponse(200, ok()))):
            with pytest.raises(ValueError, match='Unsupported HTTP method'):
                await client.request('PUT', '/uapi/test', 'FHKST01010900')

    @pytest.mark.parametrize('status', [429, 500, 503])
    async def test_http_error_becomes_runtime_error_preserving_status(self, client, status):
        session = FakeSession(FakeResponse(status, raise_exc=http_error(status)))

        with patch_session(session):
            with pytest.raises(RuntimeError) as excinfo:
                await client.request('GET', '/uapi/test', 'FHKST01010900')

        assert str(status) in str(excinfo.value)
        assert 'KIS API request failed' in str(excinfo.value)

    async def test_timeout_becomes_runtime_error(self, client):
        session = FakeSession(exc=aiohttp.ServerTimeoutError('timeout'))

        with patch_session(session):
            with pytest.raises(RuntimeError, match='KIS API request failed'):
                await client.request('GET', '/uapi/test', 'FHKST01010900')

    async def test_applies_rate_limit_delay(self, client):
        session = FakeSession(FakeResponse(200, ok()))

        with patch.object(kis_module, 'KIS_REQUEST_DELAY', 0.2), \
             patch.object(kis_module.asyncio, 'sleep', new_callable=AsyncMock) as mock_sleep, \
             patch_session(session):
            await client.request('GET', '/uapi/test', 'FHKST01010900')

        mock_sleep.assert_awaited_once_with(0.2)

    @pytest.mark.parametrize('session_factory', [
        lambda: FakeSession(FakeResponse(200, {'rt_cd': '1', 'msg1': 'rate limited'})),
        lambda: FakeSession(FakeResponse(429, raise_exc=http_error(429))),
        lambda: FakeSession(exc=aiohttp.ServerTimeoutError('timeout')),
    ], ids=['rt_cd_error', 'http_error', 'client_error'])
    async def test_rate_limit_delay_also_applies_on_failure(self, client, session_factory):
        """실패한 요청도 KIS 쪽에서는 1건이다 — 간격을 건너뛰면 오류율이 높을수록 폭주한다."""
        with patch.object(kis_module, 'KIS_REQUEST_DELAY', 0.2), \
             patch.object(kis_module.asyncio, 'sleep', new_callable=AsyncMock) as mock_sleep, \
             patch_session(session_factory()):
            with pytest.raises(RuntimeError):
                await client.request('GET', '/uapi/test', 'FHKST01010900')

        mock_sleep.assert_awaited_once_with(0.2)

    async def test_semaphore_caps_concurrency_at_five(self, client):
        """5 req/sec 세마포어: 동시 진행 요청이 5개를 넘지 않아야 한다."""
        state = {'current': 0, 'peak': 0}

        async def track():
            state['current'] += 1
            state['peak'] = max(state['peak'], state['current'])
            await asyncio.sleep(0.02)
            state['current'] -= 1

        session = FakeSession([FakeResponse(200, ok(), on_enter=track)])

        with patch_session(session):
            await asyncio.gather(*[
                client.request('GET', '/uapi/test', 'FHKST01010900') for _ in range(12)
            ])

        assert state['peak'] <= 5
        assert len(session.requests) == 12


class TestIsMarketOpen:
    @pytest.mark.parametrize('day', [datetime(2026, 6, 13), datetime(2026, 6, 14)])  # 토, 일
    async def test_weekend_returns_false_without_api_call(self, client, day):
        with patch.object(client, 'request', new_callable=AsyncMock) as mock_request:
            assert await client.is_market_open(day) is False
        mock_request.assert_not_called()

    async def test_open_weekday(self, client):
        response = ok({'output': [{'bass_dt': '20260612', 'opnd_yn': 'Y'}]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response) as mock_request:
            assert await client.is_market_open(datetime(2026, 6, 12)) is True

        params = mock_request.call_args.kwargs['params']
        assert params['BASS_DT'] == '20260612'
        assert mock_request.call_args.args[1] == '/uapi/domestic-stock/v1/quotations/chk-holiday'

    async def test_holiday_weekday_returns_false(self, client):
        response = ok({'output': [{'bass_dt': '20260101', 'opnd_yn': 'N'}]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            assert await client.is_market_open(datetime(2026, 1, 1)) is False

    async def test_picks_matching_date_row(self, client):
        response = ok({'output': [
            {'bass_dt': '20260610', 'opnd_yn': 'N'},
            {'bass_dt': '20260612', 'opnd_yn': 'Y'},
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            assert await client.is_market_open(datetime(2026, 6, 12)) is True

    async def test_accepts_plain_date(self, client):
        from datetime import date as _date
        response = ok({'output': [{'bass_dt': '20260612', 'opnd_yn': 'Y'}]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            assert await client.is_market_open(_date(2026, 6, 12)) is True

    async def test_empty_output_raises_unavailable(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output': []})):
            with pytest.raises(KISUnavailableError, match='데이터가 없습니다'):
                await client.is_market_open(datetime(2026, 6, 12))

    async def test_oauth_rate_limit_assumes_open(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS OAuth failed: HTTP 403 - Forbidden')):
            assert await client.is_market_open(datetime(2026, 6, 12)) is True

    async def test_connection_failure_raises_unavailable_not_holiday(self, client):
        """점검/네트워크 장애는 '휴장'으로 삼키지 않고 KISUnavailableError 로 올린다."""
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API request failed: Cannot connect')):
            with pytest.raises(KISUnavailableError, match='접속 실패'):
                await client.is_market_open(datetime(2026, 6, 12))


class TestGetSupplyDemand:
    async def test_parses_million_won_to_won(self, client):
        response = ok({'output': [
            {'frgn_ntby_tr_pbmn': '150', 'orgn_ntby_tr_pbmn': '-80'},
            {'frgn_ntby_tr_pbmn': '999', 'orgn_ntby_tr_pbmn': '999'},
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            result = await client.get_supply_demand('005930')

        # 백만원 단위 → 원 (최근일 index 0 사용)
        assert result == {'foreign_net_buy': 150_000_000, 'institutional_net_buy': -80_000_000}

    @pytest.mark.parametrize('output', [[], None, {}, 'unexpected'])
    async def test_empty_or_invalid_output_returns_zeros(self, client, output):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output': output})):
            result = await client.get_supply_demand('005930')

        assert result == {'foreign_net_buy': 0, 'institutional_net_buy': 0}

    @pytest.mark.parametrize('raw', [{}, {'frgn_ntby_tr_pbmn': '', 'orgn_ntby_tr_pbmn': None}])
    async def test_missing_fields_default_to_zero(self, client, raw):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output': [raw]})):
            result = await client.get_supply_demand('005930')

        assert result == {'foreign_net_buy': 0, 'institutional_net_buy': 0}

    async def test_http_500_treated_as_holiday_returns_zeros(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API request failed: 500, message=Internal Server Error')):
            result = await client.get_supply_demand('005930')

        assert result == {'foreign_net_buy': 0, 'institutional_net_buy': 0}

    async def test_other_errors_are_reraised(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API error: 초당 거래건수 초과')):
            with pytest.raises(RuntimeError, match='초당 거래건수 초과'):
                await client.get_supply_demand('005930')


def ohlcv_row(date_str, open_p=70000, high=71000, low=69000, close=70500, vol=1000):
    return {
        'stck_bsop_date': date_str,
        'stck_oprc': str(open_p),
        'stck_hgpr': str(high),
        'stck_lwpr': str(low),
        'stck_clpr': str(close),
        'acml_vol': str(vol),
    }


class TestGetDailyOhlcv:
    async def test_returns_oldest_first_dataframe(self, client):
        response = ok({'output': [  # KIS 는 최신순으로 반환
            ohlcv_row('20260612', close=72000),
            ohlcv_row('20260611', close=71000),
            ohlcv_row('20260610', close=70000),
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_daily_ohlcv('005930')

        assert list(df.columns) == ['trade_date', 'open', 'high', 'low', 'close', 'volume']
        assert list(df['trade_date']) == ['20260610', '20260611', '20260612']
        assert df['close'].dtype.kind == 'i'
        assert df.iloc[-1]['close'] == 72000

    async def test_days_argument_limits_rows(self, client):
        response = ok({'output': [ohlcv_row(f'2026061{i}') for i in range(5)]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_daily_ohlcv('005930', days=2)

        assert len(df) == 2

    async def test_empty_output_returns_empty_dataframe(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output': []})):
            assert (await client.get_daily_ohlcv('005930')).empty

    @pytest.mark.parametrize('error', [
        'KIS API request failed: 500, message=Internal Server Error',
        'KIS API error: 잘못된 종목코드입니다',
    ])
    async def test_errors_return_empty_dataframe(self, client, error):
        with patch.object(client, 'request', new_callable=AsyncMock, side_effect=RuntimeError(error)):
            assert (await client.get_daily_ohlcv('999999')).empty


class TestGetDailyOhlcvPeriod:
    async def test_parses_output2_and_sorts(self, client):
        response = ok({'output2': [
            ohlcv_row('20260612'), ohlcv_row('20260611'), ohlcv_row('20260610'),
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response) as mock_request:
            df = await client.get_daily_ohlcv_period('005930')

        assert list(df['trade_date']) == ['20260610', '20260611', '20260612']
        params = mock_request.call_args.kwargs['params']
        # 종료일은 미래일이 될 수 없도록 항상 오늘로 고정
        assert params['FID_INPUT_DATE_2'] == datetime.now().strftime('%Y%m%d')
        assert params['FID_INPUT_DATE_1'] < params['FID_INPUT_DATE_2']
        assert params['FID_PERIOD_DIV_CODE'] == 'D'
        assert mock_request.call_args.args[2] == 'FHKST03010100'

    async def test_single_call_only(self, client):
        """과거 페이지네이션 구현이 500 을 유발했으므로 단일 호출이어야 한다."""
        response = ok({'output2': [ohlcv_row('20260612')]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response) as mock_request:
            await client.get_daily_ohlcv_period('005930', days=120)

        assert mock_request.call_count == 1

    async def test_duplicate_dates_are_collapsed(self, client):
        response = ok({'output2': [ohlcv_row('20260612', close=1000), ohlcv_row('20260612', close=2000)]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_daily_ohlcv_period('005930')

        assert len(df) == 1
        assert df.iloc[0]['close'] == 1000  # 첫 행 우선

    async def test_blank_and_malformed_rows_skipped(self, client):
        response = ok({'output2': [
            ohlcv_row('20260612'),
            {'stck_bsop_date': '', 'stck_clpr': '100'},          # 날짜 없음
            {'stck_bsop_date': '20260611', 'stck_clpr': ''},     # 종가 없음(주말 등)
            {'stck_bsop_date': '20260610', 'stck_clpr': 'abc',   # 숫자 아님
             'stck_oprc': 'x', 'stck_hgpr': 'x', 'stck_lwpr': 'x', 'acml_vol': 'x'},
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_daily_ohlcv_period('005930')

        assert list(df['trade_date']) == ['20260612']

    async def test_tail_limited_to_days(self, client):
        rows = [ohlcv_row(f'202606{d:02d}') for d in range(1, 11)]
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output2': rows})):
            df = await client.get_daily_ohlcv_period('005930', days=3)

        assert list(df['trade_date']) == ['20260608', '20260609', '20260610']

    @pytest.mark.parametrize('output2', [[], None])
    async def test_empty_output_returns_empty_dataframe(self, client, output2):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output2': output2})):
            assert (await client.get_daily_ohlcv_period('005930')).empty

    async def test_all_rows_malformed_returns_empty_dataframe(self, client):
        response = ok({'output2': [{'stck_bsop_date': '20260612', 'stck_clpr': 'abc'}]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            assert (await client.get_daily_ohlcv_period('005930')).empty

    async def test_api_error_returns_empty_dataframe(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API request failed: 500')):
            assert (await client.get_daily_ohlcv_period('005930')).empty


class TestGetDailyTradeVolume:
    async def test_parses_standard_keys(self, client):
        response = ok({'output2': [
            {'stck_bsop_date': '20260612', 'total_shnu_qty': '600', 'total_seln_qty': '400'},
            {'stck_bsop_date': '20260611', 'total_shnu_qty': '300', 'total_seln_qty': '700'},
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_daily_trade_volume('005930')

        assert list(df['trade_date']) == ['20260611', '20260612']
        assert list(df['total_volume']) == [1000, 1000]
        assert df.iloc[1]['buy_volume'] == 600

    async def test_accepts_variant_keys(self, client):
        response = ok({'output2': [
            {'stck_bsop_date': '20260612', 'shnu_cnqn_smtn': '500', 'seln_cnqn_smtn': '250'},
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_daily_trade_volume('005930')

        assert df.iloc[0]['buy_volume'] == 500
        assert df.iloc[0]['sell_volume'] == 250
        assert df.iloc[0]['total_volume'] == 750

    async def test_sends_only_three_required_params(self, client):
        """날짜 파라미터를 보내면 KIS 가 INPUT FIELD NOT FOUND / 500 을 반환했었다."""
        with patch.object(client, 'request', new_callable=AsyncMock,
                          return_value=ok({'output2': []})) as mock_request:
            await client.get_daily_trade_volume('005930')

        params = mock_request.call_args.kwargs['params']
        assert params == {
            'FID_COND_MRKT_DIV_CODE': 'J',
            'FID_INPUT_ISCD': '005930',
            'FID_PERIOD_DIV_CODE': 'D',
        }
        assert mock_request.call_args.args[2] == 'FHKST03010800'

    async def test_missing_or_invalid_quantities_become_zero(self, client):
        response = ok({'output2': [
            {'stck_bsop_date': '20260612', 'total_shnu_qty': '', 'total_seln_qty': 'abc'},
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_daily_trade_volume('005930')

        assert df.iloc[0]['buy_volume'] == 0
        assert df.iloc[0]['sell_volume'] == 0

    async def test_duplicate_dates_collapsed_and_tail_applied(self, client):
        rows = [{'stck_bsop_date': f'202606{d:02d}', 'total_shnu_qty': '1', 'total_seln_qty': '1'}
                for d in range(1, 6)]
        rows.append({'stck_bsop_date': '20260601', 'total_shnu_qty': '99', 'total_seln_qty': '99'})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output2': rows})):
            df = await client.get_daily_trade_volume('005930', days=2)

        assert list(df['trade_date']) == ['20260604', '20260605']

    async def test_rows_without_date_skipped(self, client):
        response = ok({'output2': [{'total_shnu_qty': '1', 'total_seln_qty': '1'}]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            assert (await client.get_daily_trade_volume('005930')).empty

    @pytest.mark.parametrize('output2', [[], None])
    async def test_empty_output_returns_empty_dataframe(self, client, output2):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output2': output2})):
            assert (await client.get_daily_trade_volume('005930')).empty

    async def test_api_error_returns_empty_dataframe(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API request failed: 500')):
            assert (await client.get_daily_trade_volume('005930')).empty


def minute_row(time_str, open_p=70000, high=70200, low=69900, price=70100, vol=100):
    return {
        'stck_cntg_hour': time_str,
        'stck_oprc': str(open_p),
        'stck_hgpr': str(high),
        'stck_lwpr': str(low),
        'stck_prpr': str(price),
        'cntg_vol': str(vol),
    }


class TestGetMinuteData:
    async def test_fetches_two_windows_and_merges(self, client):
        responses = [
            ok({'output2': [minute_row('093000'), minute_row('090100')]}),
            ok({'output2': [minute_row('100000'), minute_row('093100')]}),
        ]
        with patch.object(client, 'request', new_callable=AsyncMock, side_effect=responses) as mock_request:
            df = await client.get_minute_data('005930', '20260612')

        assert list(df['time']) == ['090100', '093000', '093100', '100000']
        assert list(df.columns) == ['time', 'open_price', 'high_price', 'low_price', 'close_price', 'volume']
        hours = [c.kwargs['params']['FID_INPUT_HOUR_1'] for c in mock_request.call_args_list]
        assert hours == ['093000', '100000']
        # morning_return 장애의 원인이던 FHKST01010600 이 아니라 분봉 TR 을 써야 한다
        assert mock_request.call_args.args[2] == 'FHKST03010200'

    async def test_filters_rows_outside_0900_1000_window(self, client):
        response = ok({'output2': [
            minute_row('085900'), minute_row('090000'), minute_row('100000'), minute_row('100100'),
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_minute_data('005930', '20260612')

        assert list(df['time']) == ['090000', '100000']

    async def test_duplicate_times_deduplicated(self, client):
        response = ok({'output2': [minute_row('093000', price=1), minute_row('093000', price=2)]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_minute_data('005930', '20260612')

        assert len(df) == 1
        assert df.iloc[0]['close_price'] == 1

    async def test_malformed_and_short_time_rows_skipped(self, client):
        response = ok({'output2': [
            minute_row('093000'),
            {'stck_cntg_hour': '093100', 'stck_oprc': 'x'},  # 숫자 아님
            {'stck_cntg_hour': '090'},                        # 길이 부족
            {'stck_oprc': '1'},                               # 시각 없음
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            df = await client.get_minute_data('005930', '20260612')

        assert list(df['time']) == ['093000']

    async def test_no_rows_returns_empty_dataframe(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output2': []})):
            assert (await client.get_minute_data('005930', '20260612')).empty

    async def test_rows_all_outside_window_returns_empty(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          return_value=ok({'output2': [minute_row('143000')]})):
            assert (await client.get_minute_data('005930', '20260612')).empty

    async def test_one_failed_window_still_returns_other(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock, side_effect=[
            RuntimeError('KIS API request failed: 500'),
            ok({'output2': [minute_row('100000')]}),
        ]):
            df = await client.get_minute_data('005930', '20260612')

        assert list(df['time']) == ['100000']

    async def test_both_windows_failing_returns_empty(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API request failed: 500')):
            assert (await client.get_minute_data('005930', '20260612')).empty


class TestParseKisFloat:
    @pytest.mark.parametrize('raw,expected', [
        ('12.5', 12.5),
        ('1,234.56', 1234.56),
        (' 8.3 ', 8.3),
        ('-3.2', -3.2),
        (12, 12.0),
    ])
    def test_valid_values(self, raw, expected):
        assert KISClient._parse_kis_float(raw) == expected

    @pytest.mark.parametrize('raw', [None, '', '   ', '0', '0.00', 0, 'N/A', 'abc'])
    def test_missing_or_zero_normalized_to_none(self, raw):
        """적자/미산출 종목은 ''/0 으로 내려오므로 None 으로 정규화되어야 한다."""
        assert KISClient._parse_kis_float(raw) is None


class TestGetCurrentPrice:
    async def test_returns_price_and_valuation(self, client):
        response = ok({'output': {'stck_prpr': '70500', 'per': '12.5', 'pbr': '1.2', 'eps': '5600'}})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response), \
             patch.object(kis_module.asyncio, 'sleep', new_callable=AsyncMock):
            result = await client.get_current_price('005930')

        assert result == {'current_price': 70500, 'per': 12.5, 'pbr': 1.2, 'eps': 5600.0}

    async def test_zero_valuation_normalized_to_none(self, client):
        response = ok({'output': {'stck_prpr': '70500', 'per': '0', 'pbr': '', 'eps': '0.00'}})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response), \
             patch.object(kis_module.asyncio, 'sleep', new_callable=AsyncMock):
            result = await client.get_current_price('005930')

        assert result == {'current_price': 70500, 'per': None, 'pbr': None, 'eps': None}

    async def test_api_error_returns_zeroed_dict(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API error: 잘못된 종목코드')), \
             patch.object(kis_module.asyncio, 'sleep', new_callable=AsyncMock):
            result = await client.get_current_price('999999')

        assert result == {'current_price': 0, 'per': None, 'pbr': None, 'eps': None}

    async def test_missing_price_field_returns_zeroed_dict(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output': {}})), \
             patch.object(kis_module.asyncio, 'sleep', new_callable=AsyncMock):
            result = await client.get_current_price('005930')

        assert result['current_price'] == 0

    async def test_does_not_double_acquire_semaphore(self, client):
        """회귀 방지: get_current_price 가 세마포어를 감싸고 request() 가 내부에서
        같은(재진입 불가) 세마포어를 또 잡아, 슬롯이 1개면 자기 자신과 교착되던 버그를
        고쳤다. 세마포어 크기가 1이어도 정상적으로 완료되어야 한다.
        """
        client.semaphore = asyncio.Semaphore(1)
        session = FakeSession(FakeResponse(200, ok({'output': {'stck_prpr': '70500'}})))

        with patch_session(session), \
             patch.object(kis_module.asyncio, 'sleep', new_callable=AsyncMock):
            result = await asyncio.wait_for(client.get_current_price('005930'), timeout=0.3)

        assert result['current_price'] == 70500


class TestValuations:
    async def test_get_valuation_projects_per_pbr_eps(self, client):
        with patch.object(client, 'get_current_price', new_callable=AsyncMock, return_value={
            'current_price': 70500, 'per': 12.5, 'pbr': 1.2, 'eps': 5600.0,
        }):
            assert await client.get_valuation('005930') == {'per': 12.5, 'pbr': 1.2, 'eps': 5600.0}

    async def test_get_valuations_for_stocks_builds_per_map(self, client):
        with patch.object(client, 'get_valuation', new_callable=AsyncMock, side_effect=[
            {'per': 12.5}, {'per': None},
        ]):
            per_map = await client.get_valuations_for_stocks(['005930', '000660'])

        assert per_map == {'005930': 12.5, '000660': None}

    async def test_failure_for_one_stock_yields_none(self, client):
        with patch.object(client, 'get_valuation', new_callable=AsyncMock, side_effect=[
            RuntimeError('boom'), {'per': 8.3},
        ]):
            per_map = await client.get_valuations_for_stocks(['005930', '000660'])

        assert per_map == {'005930': None, '000660': 8.3}

    async def test_empty_input_returns_empty_map(self, client):
        assert await client.get_valuations_for_stocks([]) == {}


class TestGetKospiIndex:
    async def test_parses_output1(self, client):
        response = ok({'output1': {
            'bstp_nmix_prpr': '2750.35', 'bstp_nmix_prdy_ctrt': '1.25',
            'acml_vol': '450000000', 'acml_tr_pbmn': '12500000',
        }})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response) as mock_request:
            result = await client.get_kospi_index('20260612')

        assert result == {
            'kospi_index': 2750.35, 'kospi_change_rate': 1.25,
            'kospi_volume': 450000000, 'kospi_trade_value': 12500000,
        }
        params = mock_request.call_args.kwargs['params']
        assert params['FID_INPUT_ISCD'] == '0001'
        assert params['FID_INPUT_DATE_1'] == params['FID_INPUT_DATE_2'] == '20260612'

    async def test_defaults_to_today(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          return_value=ok({'output1': {}})) as mock_request:
            await client.get_kospi_index()

        today = datetime.now().strftime('%Y%m%d')
        assert mock_request.call_args.kwargs['params']['FID_INPUT_DATE_1'] == today

    async def test_missing_output1_returns_zeros(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({})):
            result = await client.get_kospi_index('20260612')

        assert result['kospi_index'] == 0.0

    async def test_api_error_returns_zeros(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API request failed: 500')):
            result = await client.get_kospi_index('20260612')

        assert result == {
            'kospi_index': 0.0, 'kospi_change_rate': 0.0,
            'kospi_volume': 0, 'kospi_trade_value': 0,
        }


class TestGetHoldings:
    async def test_returns_codes_with_positive_quantity(self, client):
        response = ok({'output1': [
            {'pdno': '005930', 'hldg_qty': '10'},
            {'pdno': '000660', 'hldg_qty': '0'},   # 잔량 0 은 제외
            {'pdno': '', 'hldg_qty': '5'},         # 종목코드 없음
            {'pdno': '051910', 'hldg_qty': '3'},
        ]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response) as mock_request:
            holdings = await client.get_holdings()

        assert holdings == ['005930', '051910']
        assert mock_request.call_args.args[2] == 'VTTC8434R'
        assert mock_request.call_args.args[1] == '/uapi/domestic-stock/v1/trading/inquire-balance'

    async def test_empty_portfolio_returns_empty_list(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=ok({'output1': []})):
            assert await client.get_holdings() == []

    async def test_api_error_degrades_to_empty_list(self, client):
        with patch.object(client, 'request', new_callable=AsyncMock,
                          side_effect=RuntimeError('KIS API request failed: 500')):
            assert await client.get_holdings() == []

    async def test_malformed_quantity_degrades_to_empty_list(self, client):
        response = ok({'output1': [{'pdno': '005930', 'hldg_qty': 'abc'}]})
        with patch.object(client, 'request', new_callable=AsyncMock, return_value=response):
            assert await client.get_holdings() == []


def ohlcv_df(rows):
    return pd.DataFrame(rows)


class TestFetchStockDataParallel:
    def _ohlcv(self, n=21, volume=1000, last_volume=None, high=71000, low=69000, close=70500):
        rows = []
        for i in range(n):
            vol = volume if (last_volume is None or i < n - 1) else last_volume
            rows.append({
                'trade_date': f'2026{i:04d}', 'open': 70000, 'high': high,
                'low': low, 'close': close, 'volume': vol,
            })
        return pd.DataFrame(rows)

    async def test_builds_feature_dataframe(self, client):
        with patch.object(client, 'get_supply_demand', new_callable=AsyncMock,
                          return_value={'foreign_net_buy': 500, 'institutional_net_buy': -200}), \
             patch.object(client, 'get_daily_ohlcv', new_callable=AsyncMock,
                          return_value=self._ohlcv(21, volume=1000, last_volume=2000)):
            df = await client.fetch_stock_data_parallel(['005930'])

        assert list(df.columns) == [
            'stock_code', 'foreign_net_buy', 'institutional_net_buy',
            'volume_ratio', 'price_volatility', 'close_position',
        ]
        row = df.iloc[0]
        assert row['volume_ratio'] == 2.0            # 2000 / 1000(20일 평균)
        assert row['price_volatility'] == pytest.approx((71000 - 69000) / 69000)
        assert row['close_position'] == pytest.approx((70500 - 69000) / (71000 - 69000))

    async def test_fewer_than_21_days_uses_default_volume_ratio(self, client):
        with patch.object(client, 'get_supply_demand', new_callable=AsyncMock,
                          return_value={'foreign_net_buy': 0, 'institutional_net_buy': 0}), \
             patch.object(client, 'get_daily_ohlcv', new_callable=AsyncMock,
                          return_value=self._ohlcv(5)):
            df = await client.fetch_stock_data_parallel(['005930'])

        assert df.iloc[0]['volume_ratio'] == 1.0

    async def test_flat_day_uses_neutral_close_position(self, client):
        with patch.object(client, 'get_supply_demand', new_callable=AsyncMock,
                          return_value={'foreign_net_buy': 0, 'institutional_net_buy': 0}), \
             patch.object(client, 'get_daily_ohlcv', new_callable=AsyncMock,
                          return_value=self._ohlcv(5, high=70000, low=70000, close=70000)):
            df = await client.fetch_stock_data_parallel(['005930'])

        assert df.iloc[0]['close_position'] == 0.5
        assert df.iloc[0]['price_volatility'] == 0.0

    async def test_insufficient_ohlcv_rows_skips_stock(self, client):
        with patch.object(client, 'get_supply_demand', new_callable=AsyncMock,
                          return_value={'foreign_net_buy': 0, 'institutional_net_buy': 0}), \
             patch.object(client, 'get_daily_ohlcv', new_callable=AsyncMock,
                          return_value=self._ohlcv(1)):
            df = await client.fetch_stock_data_parallel(['005930'])

        assert df.empty

    async def test_supply_demand_failure_skips_stock(self, client):
        with patch.object(client, 'get_supply_demand', new_callable=AsyncMock,
                          side_effect=[RuntimeError('boom'),
                                       {'foreign_net_buy': 1, 'institutional_net_buy': 1}]), \
             patch.object(client, 'get_daily_ohlcv', new_callable=AsyncMock,
                          return_value=self._ohlcv(21)):
            df = await client.fetch_stock_data_parallel(['005930', '000660'])

        assert list(df['stock_code']) == ['000660']

    async def test_ohlcv_failure_skips_stock(self, client):
        with patch.object(client, 'get_supply_demand', new_callable=AsyncMock,
                          return_value={'foreign_net_buy': 0, 'institutional_net_buy': 0}), \
             patch.object(client, 'get_daily_ohlcv', new_callable=AsyncMock,
                          side_effect=RuntimeError('boom')):
            df = await client.fetch_stock_data_parallel(['005930'])

        assert df.empty

    async def test_all_failures_return_empty_dataframe(self, client):
        with patch.object(client, 'get_supply_demand', new_callable=AsyncMock,
                          side_effect=RuntimeError('down')):
            df = await client.fetch_stock_data_parallel(['005930', '000660'])

        assert df.empty

    async def test_ohlcv_requested_with_21_days(self, client):
        with patch.object(client, 'get_supply_demand', new_callable=AsyncMock,
                          return_value={'foreign_net_buy': 0, 'institutional_net_buy': 0}), \
             patch.object(client, 'get_daily_ohlcv', new_callable=AsyncMock,
                          return_value=self._ohlcv(21)) as mock_ohlcv:
            await client.fetch_stock_data_parallel(['005930'])

        assert mock_ohlcv.call_args.kwargs['days'] == 21

    async def test_empty_input_returns_empty_dataframe(self, client):
        assert (await client.fetch_stock_data_parallel([])).empty
