"""Internal API client — ai-agent → api-server service-to-service channel.

멀티유저 파이프라인용. 사용자 KIS 키는 api-server(DB, Jasypt)에만 있으며,
ai-agent 는 식별자/보유종목만 받는다. 인증은 공유 시크릿 헤더
(X-Internal-Api-Key)로 수행한다. 모든 호출은 실패 시 빈 결과로 degrade 하여
파이프라인을 막지 않되, **실패했다는 사실은 `degradations` 에 남긴다** — 반환값만으로는
"조회 실패로 비었다"와 "실제로 보유/잔고가 0이다"를 구분할 수 없기 때문이다.
"""
import asyncio
import logging
from typing import Dict, List, Optional

import aiohttp

logger = logging.getLogger(__name__)


class InternalApiClient:
    """api-server 의 /internal/** 엔드포인트 비동기 클라이언트."""

    # 일시적 실패로 보고 재시도할 상태코드 (레이트리밋 / 서버측 장애).
    # 4xx(인증·설정 오류)는 재시도해도 같은 결과이므로 즉시 포기한다.
    RETRYABLE_STATUSES = frozenset({429, 500, 502, 503, 504})
    RETRY_BACKOFF_SECONDS = (0.5, 1.0)  # 길이 = 최대 재시도 횟수

    def __init__(self, base_url: str, api_key: Optional[str], timeout: int = 30):
        """
        Args:
            base_url: api-server base URL (컨텍스트패스 /api 제외, 예: http://api-server:7070)
            api_key: X-Internal-Api-Key 값 (INTERNAL_API_KEY)
            timeout: HTTP 타임아웃(초)
        """
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = aiohttp.ClientTimeout(total=timeout)
        self.degradations: List[Dict] = []

    @property
    def _headers(self) -> Dict[str, str]:
        return {"X-Internal-Api-Key": self.api_key or ""}

    def reset_degradations(self) -> None:
        """실패 기록을 비운다. 파이프라인 실행 1회 단위로 호출한다."""
        self.degradations = []

    def _record_degradation(self, operation: str, reason: str, user_id: Optional[int]) -> None:
        """조회 실패로 빈 결과를 반환했음을 기록한다."""
        self.degradations.append({"operation": operation, "user_id": user_id, "reason": reason})

    async def _get_json(
        self, url: str, operation: str, user_id: Optional[int] = None
    ) -> Optional[Dict]:
        """GET 후 JSON 본문 반환. 최종 실패 시 degradation 을 기록하고 None.

        429/5xx·네트워크 오류는 짧은 backoff 로 재시도한다. 재시도 끝에도 실패하면
        호출자는 빈 결과로 degrade 하되, 실패 사실은 `degradations` 로 전파된다.

        Args:
            url: 호출 대상 URL.
            operation: 실패 기록에 남길 호출 이름.
            user_id: 유저 단위 호출이면 해당 id (전역 호출이면 None).

        Returns:
            Optional[Dict]: 성공 시 파싱된 JSON 본문, 실패 시 None.
        """
        max_attempts = len(self.RETRY_BACKOFF_SECONDS) + 1
        reason = "unknown"

        for attempt in range(1, max_attempts + 1):
            try:
                async with aiohttp.ClientSession(timeout=self.timeout) as session:
                    async with session.get(url, headers=self._headers) as resp:
                        if resp.status == 200:
                            return await resp.json(content_type=None)
                        reason = f"HTTP {resp.status}"
                        retryable = resp.status in self.RETRYABLE_STATUSES
            except Exception as e:
                reason = f"{type(e).__name__}: {e}"
                retryable = True

            if not retryable:
                logger.error(f"{operation} failed, not retryable: {reason} (url={url})")
                break
            if attempt < max_attempts:
                delay = self.RETRY_BACKOFF_SECONDS[attempt - 1]
                logger.warning(
                    f"{operation} transient failure ({reason}); retrying in {delay}s "
                    f"[attempt {attempt}/{max_attempts}]"
                )
                await asyncio.sleep(delay)
            else:
                logger.error(f"{operation} failed after {max_attempts} attempts: {reason}")

        self._record_degradation(operation, reason, user_id)
        return None

    async def get_active_auto_trading_users(self) -> List[Dict]:
        """
        자동매매 활성 사용자 목록 조회.

        Returns:
            [{user_id, kis_account_id, order_amount, max_holdings, order_type}, ...]
            실패 시 빈 리스트(실패 사실은 `degradations` 에 기록).
        """
        url = f"{self.base_url}/api/internal/auto-trading/users"
        body = await self._get_json(url, "get_active_auto_trading_users")
        if body is None:
            return []
        users = (body or {}).get("data") or []
        logger.info(f"Fetched {len(users)} active auto-trading users")
        return users

    async def get_user_holdings(self, user_id: int) -> List[str]:
        """
        특정 사용자의 보유 종목코드 목록 조회 (api-server 가 사용자 KIS 키로 KIS 조회).

        Args:
            user_id: 사용자 id

        Returns:
            보유 종목코드 리스트(6자리). 보유 없음이면 빈 리스트, 조회 실패면 빈 리스트
            + `degradations` 기록.
        """
        url = f"{self.base_url}/api/internal/users/{user_id}/holdings"
        body = await self._get_json(url, "get_user_holdings", user_id=user_id)
        if body is None:
            return []
        data = (body or {}).get("data") or {}
        holdings = data.get("holdings") or []
        codes = [h.get("stockCode") for h in holdings if h.get("stockCode")]
        logger.info(f"User {user_id}: {len(codes)} holdings")
        return codes

    async def get_user_portfolio(self, user_id: int) -> Dict:
        """
        특정 사용자의 포트폴리오 전체 조회 (유저별 매수/매도 판단 입력용).

        보유 상세(수량/매입단가/평가액/손익률/총자산 대비 비중) + 가용 현금을 포함한다.
        조회 실패/보유 없음 시 빈 포트폴리오로 degrade 하며, 조회 실패인 경우에만
        `degradations` 에 기록이 남는다(현금 0 → 매수 스킵이 조용히 일어나지 않도록).

        Returns:
            {
                "holdings": [
                    {stock_code, stock_name, quantity, available_quantity, avg_price,
                     current_price, eval_amount, profit_loss_rate, weight_pct}, ...
                ],
                "cash": float,            # 주문가능현금
                "total_eval": float,      # 보유 평가금액 합계
                "total_assets": float,    # total_eval + cash
                "holding_codes": [str, ...],
            }
        """
        empty = {"holdings": [], "cash": 0.0, "total_eval": 0.0, "total_assets": 0.0, "holding_codes": []}
        url = f"{self.base_url}/api/internal/users/{user_id}/holdings"
        body = await self._get_json(url, "get_user_portfolio", user_id=user_id)
        if body is None:
            return empty

        data = (body or {}).get("data") or {}
        cash = _to_float(data.get("cashBalance"))
        total_eval = _to_float(data.get("totalEvaluationAmount"))
        total_assets = total_eval + cash

        holdings = []
        for h in data.get("holdings") or []:
            code = h.get("stockCode")
            if not code:
                continue
            eval_amount = _to_float(h.get("evaluationAmount"))
            weight_pct = round(eval_amount / total_assets * 100, 2) if total_assets > 0 else 0.0
            holdings.append({
                "stock_code": code,
                "stock_name": h.get("stockName") or code,
                "quantity": int(_to_float(h.get("holdingQuantity"))),
                "available_quantity": int(_to_float(h.get("availableQuantity"))),
                "avg_price": _to_float(h.get("averagePrice")),
                "current_price": _to_float(h.get("currentPrice")),
                "eval_amount": eval_amount,
                "profit_loss_rate": _to_float(h.get("profitLossRate")),
                "weight_pct": weight_pct,
            })

        logger.info(f"User {user_id} portfolio: {len(holdings)} holdings, cash={cash:,.0f}")
        return {
            "holdings": holdings,
            "cash": cash,
            "total_eval": total_eval,
            "total_assets": total_assets,
            "holding_codes": [h["stock_code"] for h in holdings],
        }


def _to_float(value) -> float:
    """KIS/JSON 수치(숫자 또는 콤마 포함 문자열)를 float 로 안전 변환. 실패 시 0.0."""
    if value is None:
        return 0.0
    try:
        if isinstance(value, str):
            value = value.replace(",", "").strip()
            if not value:
                return 0.0
        return float(value)
    except (TypeError, ValueError):
        return 0.0
