"""Gemini AI Client for Trading Decisions

Uses Google Gemini API for buy/sell decision generation.

Two call shapes:
- generate_decision: 시장 전반 매수/매도 TOP3 (대시보드용, 전역 1콜)
- generate_user_decision: 유저 포트폴리오 맥락 기반 맞춤 매수/매도 (유저당 1콜)

멀티유저로 유저당 1콜이 burst 로 나가므로, 무료 티어 RPM(10/분) 보호를 위해
호출 간 최소 간격(throttle) + 429(쿼터) 지수 백오프 재시도를 적용한다.

추가로 DNS 해석 실패·503·타임아웃 등 일시적 네트워크 오류도 짧은 백오프로
재시도한다(순간적인 네트워크 끊김에 파이프라인 전체가 무너지는 것을 방지).
"""
import json
import logging
import time
from typing import Optional, Dict

import google.generativeai as genai

from config.settings import get_settings

logger = logging.getLogger(__name__)

try:  # 429 감지용 (google-generativeai 가 내부적으로 사용)
    from google.api_core.exceptions import ResourceExhausted
except Exception:  # pragma: no cover - import 환경에 따라 없을 수 있음
    ResourceExhausted = None

try:  # 일시적 네트워크 오류(503/타임아웃) 감지용
    from google.api_core.exceptions import ServiceUnavailable, DeadlineExceeded
except Exception:  # pragma: no cover - import 환경에 따라 없을 수 있음
    ServiceUnavailable = None
    DeadlineExceeded = None


class GeminiClient:
    """Gemini AI client for generating trading decisions."""

    MODEL_NAME = 'models/gemini-2.5-flash'  # Gemini 2.5 Flash (stable, fast, free tier)

    # REST transport 사용(gRPC 대신).
    # 기본 gRPC 리졸버(c-ares)는 IPv6 link-local DNS 서버(fe80::...%en0) 환경에서
    # "Could not contact DNS servers"로 해석에 실패한다. REST 는 OS 리졸버를 타므로
    # 동일 환경에서 정상 동작한다.
    TRANSPORT = 'rest'

    # 무료 티어 보호: 10 RPM → 호출 간 최소 6.5초 간격(≈9.2/분, 마진 포함)
    MIN_CALL_INTERVAL_SEC = 6.5
    # 429(쿼터) 재시도: 지수 백오프
    MAX_RETRIES = 3
    BACKOFF_BASE_SEC = 10
    # 일시적 네트워크 오류(DNS/503/타임아웃) 재시도: 쿼터와 무관하므로 짧게
    NETWORK_BACKOFF_BASE_SEC = 3

    def __init__(self, api_key: Optional[str] = None):
        """
        Initialize Gemini client.

        Args:
            api_key: Gemini API key (optional, reads from settings if not provided)
        """
        settings = get_settings()
        self.api_key = api_key or settings.gemini_api_key
        self._last_call_ts: float = 0.0  # throttle 용 마지막 호출 시각(monotonic)

        if not self.api_key:
            logger.warning("Gemini API key not provided - decisions will be mocked")
            self.model = None
        else:
            try:
                genai.configure(api_key=self.api_key, transport=self.TRANSPORT)
                self.model = genai.GenerativeModel(self.MODEL_NAME)
                logger.info(
                    f"Gemini AI client initialized with model: {self.MODEL_NAME} "
                    f"(transport={self.TRANSPORT})"
                )
            except Exception as e:
                logger.error(f"Failed to initialize Gemini client: {e}")
                self.model = None

    # ------------------------------------------------------------------
    # Rate limiting / retry
    # ------------------------------------------------------------------
    def _throttle(self) -> None:
        """직전 호출과의 간격이 MIN_CALL_INTERVAL_SEC 미만이면 그만큼 대기(RPM 보호)."""
        elapsed = time.monotonic() - self._last_call_ts
        wait = self.MIN_CALL_INTERVAL_SEC - elapsed
        if wait > 0:
            logger.debug(f"Throttling Gemini call: sleeping {wait:.1f}s")
            time.sleep(wait)

    @staticmethod
    def _is_rate_limit_error(e: Exception) -> bool:
        if ResourceExhausted is not None and isinstance(e, ResourceExhausted):
            return True
        msg = str(e).lower()
        return '429' in msg or 'quota' in msg or 'rate limit' in msg

    @staticmethod
    def _is_transient_network_error(e: Exception) -> bool:
        """DNS 해석 실패·연결 오류·503·타임아웃 등 일시적 네트워크 오류인지 판단."""
        if ServiceUnavailable is not None and isinstance(e, ServiceUnavailable):
            return True
        if DeadlineExceeded is not None and isinstance(e, DeadlineExceeded):
            return True
        msg = str(e).lower()
        keywords = (
            '503', '502', '504',
            'timeout', 'timed out', 'deadline',
            'dns', 'lookup', 'resolv',            # 'resolving', 'address lookup failed'
            'could not contact', 'unavailable',
            'connection', 'network is unreachable', 'temporarily',
        )
        return any(k in msg for k in keywords)

    def _generate_text(self, prompt: str) -> str:
        """
        throttle + 지수 백오프 재시도로 감싼 단일 텍스트 생성.

        재시도 대상:
        - 429(쿼터) → 긴 백오프(무료 티어 쿼터 회복 대기)
        - 일시적 네트워크 오류(DNS/503/타임아웃) → 짧은 백오프

        Returns:
            응답 텍스트

        Raises:
            RuntimeError: 모델 미초기화 / 재시도 소진 / 비정상 응답
        """
        if not self.model:
            raise RuntimeError("Gemini client not initialized")

        last_err: Optional[Exception] = None
        for attempt in range(self.MAX_RETRIES + 1):
            self._throttle()
            try:
                response = self.model.generate_content(prompt)
                self._last_call_ts = time.monotonic()
                if not response or not response.text:
                    raise ValueError("Empty response from Gemini API")
                return response.text
            except Exception as e:  # noqa: BLE001
                self._last_call_ts = time.monotonic()
                last_err = e
                if attempt < self.MAX_RETRIES:
                    if self._is_rate_limit_error(e):
                        backoff = self.BACKOFF_BASE_SEC * (2 ** attempt)
                        logger.warning(
                            f"Gemini rate-limited (attempt {attempt + 1}/{self.MAX_RETRIES}), "
                            f"backing off {backoff}s: {e}"
                        )
                        time.sleep(backoff)
                        continue
                    if self._is_transient_network_error(e):
                        backoff = self.NETWORK_BACKOFF_BASE_SEC * (2 ** attempt)
                        logger.warning(
                            f"Gemini transient network error (attempt {attempt + 1}/{self.MAX_RETRIES}), "
                            f"retrying in {backoff}s: {e}"
                        )
                        time.sleep(backoff)
                        continue
                raise
        raise RuntimeError(f"Gemini call failed after retries: {last_err}")

    # ------------------------------------------------------------------
    # Market-wide decision (dashboard) — global single call
    # ------------------------------------------------------------------
    def generate_decision(self, prompt: str) -> Dict:
        """
        시장 전반 매수/매도 TOP3 생성 (대시보드용).

        Returns:
            {'buy_top3': [{'stock_code', 'reason'}, ...], 'sell_top3': [...]}

        Raises:
            RuntimeError: Gemini 호출 실패
        """
        if not self.model:
            logger.warning("Gemini client not available, returning mock decision")
            return self._get_mock_decision()

        try:
            logger.info("Calling Gemini API for market-wide trading decision...")
            text = self._generate_text(prompt)
            decision = self._parse_gemini_response(text)
            logger.info("Gemini market decision generated successfully")
            return decision
        except Exception as e:
            logger.error(f"Gemini API call failed: {e}")
            raise RuntimeError(f"Gemini decision generation failed: {e}")

    # ------------------------------------------------------------------
    # Per-user decision — one call per user, portfolio-aware
    # ------------------------------------------------------------------
    def generate_user_decision(self, prompt: str) -> Dict:
        """
        유저 포트폴리오 맥락 기반 맞춤 매수/매도 생성 (유저당 1콜).

        매수/매도 항목 수는 가변(0개 이상). 모델 미초기화 시 빈 결정으로 degrade
        (실거래 안전: 결정 불가 → 아무것도 실행하지 않음).

        Returns:
            {'buy': [{'stock_code', 'reason'}, ...], 'sell': [{'stock_code', 'reason'}, ...]}
        """
        if not self.model:
            logger.warning("Gemini client not available, returning empty user decision")
            return {'buy': [], 'sell': []}

        try:
            logger.info("Calling Gemini API for per-user trading decision...")
            text = self._generate_text(prompt)
            decision = self._parse_user_decision(text)
            logger.info(
                f"Gemini user decision: {len(decision['buy'])} buys, {len(decision['sell'])} sells"
            )
            return decision
        except Exception as e:
            logger.error(f"Gemini per-user decision failed: {e}")
            # 실거래 안전: 판단 실패 시 빈 결정(아무것도 실행 안 함)
            return {'buy': [], 'sell': []}

    # ------------------------------------------------------------------
    # Parsing
    # ------------------------------------------------------------------
    @staticmethod
    def _extract_json(response_text: str) -> dict:
        text = response_text.strip()
        if '```json' in text:
            text = text.split('```json')[1].split('```')[0]
        elif '```' in text:
            text = text.split('```')[1].split('```')[0]
        return json.loads(text.strip())

    def _parse_gemini_response(self, response_text: str) -> Dict:
        """시장 전반 응답 파싱 (buy_top3 / sell_top3 각 정확히 3개)."""
        try:
            decision = self._extract_json(response_text)

            if 'buy_top3' not in decision or 'sell_top3' not in decision:
                raise ValueError("Missing buy_top3 or sell_top3 in response")
            if len(decision['buy_top3']) != 3 or len(decision['sell_top3']) != 3:
                raise ValueError("buy_top3 and sell_top3 must have exactly 3 items each")
            for item in decision['buy_top3'] + decision['sell_top3']:
                if 'stock_code' not in item or 'reason' not in item:
                    raise ValueError("Each decision item must have stock_code and reason")
            return decision
        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse Gemini response as JSON: {e}")
            logger.error(f"Response text: {response_text[:500]}")
            raise ValueError(f"Gemini response is not valid JSON: {e}")
        except Exception as e:
            logger.error(f"Failed to validate Gemini response: {e}")
            raise ValueError(f"Invalid Gemini response structure: {e}")

    def _parse_user_decision(self, response_text: str) -> Dict:
        """
        유저별 응답 파싱. buy/sell 키의 가변 길이 리스트.
        stock_code/reason 없는 항목은 버린다(견고성). 파싱 실패 시 빈 결정.
        """
        try:
            data = self._extract_json(response_text)
        except json.JSONDecodeError as e:
            logger.error(f"User decision not valid JSON: {e}; text={response_text[:300]}")
            return {'buy': [], 'sell': []}

        def _clean(items) -> list:
            cleaned = []
            if isinstance(items, list):
                for it in items:
                    if isinstance(it, dict) and it.get('stock_code') and it.get('reason'):
                        cleaned.append({'stock_code': str(it['stock_code']).strip(), 'reason': str(it['reason']).strip()})
            return cleaned

        return {'buy': _clean(data.get('buy')), 'sell': _clean(data.get('sell'))}

    def _get_mock_decision(self) -> Dict:
        """Mock market decision for testing without Gemini API."""
        logger.warning("Generating mock decision (Gemini API not available)")
        return {
            'buy_top3': [
                {'stock_code': '005930', 'reason': '[MOCK] Strong fundamentals with positive sentiment'},
                {'stock_code': '000660', 'reason': '[MOCK] Rising price trend and institutional buying'},
                {'stock_code': '051910', 'reason': '[MOCK] High ROE with favorable news sentiment'}
            ],
            'sell_top3': [
                {'stock_code': '005380', 'reason': '[MOCK] Declining trend with negative sentiment'},
                {'stock_code': '035420', 'reason': '[MOCK] High uncertainty in price forecast'},
                {'stock_code': '068270', 'reason': '[MOCK] Foreign sell-off and weak fundamentals'}
            ]
        }

    def test_connection(self) -> bool:
        """Test Gemini API connection with a simple request."""
        if not self.model:
            logger.error("Gemini client not initialized")
            return False
        try:
            logger.info("Testing Gemini API connection...")
            text = self._generate_text("Hello, respond with 'OK'")
            logger.info("Gemini API connection successful")
            return bool(text)
        except Exception as e:
            logger.error(f"Gemini API connection test failed: {e}")
            return False
