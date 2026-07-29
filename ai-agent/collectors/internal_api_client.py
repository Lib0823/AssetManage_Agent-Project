"""Internal API client — ai-agent → api-server service-to-service channel.

멀티유저 파이프라인용. 사용자 KIS 키는 api-server(DB, Jasypt)에만 있으며,
ai-agent 는 식별자/보유종목만 받는다. 인증은 공유 시크릿 헤더
(X-Internal-Api-Key)로 수행한다. 모든 호출은 실패 시 빈 결과로 degrade 하여
파이프라인을 막지 않는다.
"""
import logging
from typing import Dict, List, Optional

import aiohttp

logger = logging.getLogger(__name__)


class InternalApiClient:
    """api-server 의 /internal/** 엔드포인트 비동기 클라이언트."""

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

    @property
    def _headers(self) -> Dict[str, str]:
        return {"X-Internal-Api-Key": self.api_key or ""}

    async def get_active_auto_trading_users(self) -> List[Dict]:
        """
        자동매매 활성 사용자 목록 조회.

        Returns:
            [{user_id, kis_account_id, order_amount, max_holdings, order_type}, ...]
            실패 시 빈 리스트.
        """
        url = f"{self.base_url}/api/internal/auto-trading/users"
        try:
            async with aiohttp.ClientSession(timeout=self.timeout) as session:
                async with session.get(url, headers=self._headers) as resp:
                    if resp.status != 200:
                        logger.error(f"Active users fetch failed: HTTP {resp.status}")
                        return []
                    body = await resp.json(content_type=None)
            users = (body or {}).get("data") or []
            logger.info(f"Fetched {len(users)} active auto-trading users")
            return users
        except Exception as e:
            logger.error(f"Active users fetch error: {e}")
            return []

    async def get_user_holdings(self, user_id: int) -> List[str]:
        """
        특정 사용자의 보유 종목코드 목록 조회 (api-server 가 사용자 KIS 키로 KIS 조회).

        Args:
            user_id: 사용자 id

        Returns:
            보유 종목코드 리스트(6자리). 보유 없음/조회 실패 시 빈 리스트.
        """
        url = f"{self.base_url}/api/internal/users/{user_id}/holdings"
        try:
            async with aiohttp.ClientSession(timeout=self.timeout) as session:
                async with session.get(url, headers=self._headers) as resp:
                    if resp.status != 200:
                        logger.warning(f"Holdings fetch for user {user_id} failed: HTTP {resp.status}")
                        return []
                    body = await resp.json(content_type=None)
            data = (body or {}).get("data") or {}
            holdings = data.get("holdings") or []
            codes = [h.get("stockCode") for h in holdings if h.get("stockCode")]
            logger.info(f"User {user_id}: {len(codes)} holdings")
            return codes
        except Exception as e:
            logger.warning(f"Holdings fetch error for user {user_id}: {e}")
            return []

    async def get_user_portfolio(self, user_id: int) -> Dict:
        """
        특정 사용자의 포트폴리오 전체 조회 (유저별 매수/매도 판단 입력용).

        보유 상세(수량/매입단가/평가액/손익률/총자산 대비 비중) + 가용 현금을 포함한다.
        조회 실패/보유 없음 시 빈 포트폴리오로 degrade 한다.

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
        try:
            async with aiohttp.ClientSession(timeout=self.timeout) as session:
                async with session.get(url, headers=self._headers) as resp:
                    if resp.status != 200:
                        logger.warning(f"Portfolio fetch for user {user_id} failed: HTTP {resp.status}")
                        return empty
                    body = await resp.json(content_type=None)
        except Exception as e:
            logger.warning(f"Portfolio fetch error for user {user_id}: {e}")
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

    async def execute_buy(self, user_id: int, stock_code: str, stock_name: str,
                          quantity: int, price: float = 0) -> Dict:
        """특정 사용자 명의 매수 주문 (api-server 가 사용자 KIS 키로 대행)."""
        return await self._post_trade(user_id, "buy", stock_code, stock_name, quantity, price)

    async def execute_sell(self, user_id: int, stock_code: str, stock_name: str,
                           quantity: int, price: float = 0) -> Dict:
        """특정 사용자 명의 매도 주문 (api-server 가 사용자 KIS 키로 대행)."""
        return await self._post_trade(user_id, "sell", stock_code, stock_name, quantity, price)

    async def _post_trade(self, user_id: int, side: str, stock_code: str, stock_name: str,
                          quantity: int, price: float) -> Dict:
        url = f"{self.base_url}/api/internal/users/{user_id}/trades/{side}"
        payload = {
            "stock_code": stock_code,
            "stock_name": stock_name,
            "quantity": int(quantity),
            "price": price or 0,
        }
        try:
            async with aiohttp.ClientSession(timeout=self.timeout) as session:
                async with session.post(url, headers=self._headers, json=payload) as resp:
                    body = await resp.json(content_type=None)
                    if resp.status == 200 and (body or {}).get("success"):
                        logger.info(f"[{side.upper()}] user {user_id} {stock_code} x{quantity} ok")
                        return {"success": True, "data": (body or {}).get("data")}
                    logger.error(f"[{side.upper()}] user {user_id} {stock_code} failed: HTTP {resp.status} {body}")
                    return {"success": False, "error": (body or {}).get("message", f"HTTP {resp.status}")}
        except Exception as e:
            logger.error(f"[{side.upper()}] user {user_id} {stock_code} error: {e}")
            return {"success": False, "error": str(e)}


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
