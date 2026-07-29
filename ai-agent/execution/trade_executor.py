"""Trade Executor Module (multi-user).

유저별 매수/매도 주문을 api-server 내부 채널(/internal/users/{id}/trades/...)로
실행한다. 사용자 KIS 키는 api-server(DB, Jasypt)에만 있으며, 실제 KIS 주문은
api-server 가 대행한다. 한 유저의 주문 실패가 다른 주문/유저를 막지 않도록 격리한다.
"""
import logging
from datetime import datetime
from typing import Dict, List

from collectors.internal_api_client import InternalApiClient

logger = logging.getLogger(__name__)


class TradeExecutor:
    """Execute per-user buy/sell orders via the api-server internal channel."""

    def __init__(self, internal_api: InternalApiClient):
        """
        Args:
            internal_api: 내부 API 클라이언트 (X-Internal-Api-Key 인증)
        """
        self.internal_api = internal_api

    async def execute_for_user(
        self,
        user_id: int,
        buy_orders: List[Dict],
        sell_orders: List[Dict]
    ) -> Dict:
        """
        한 사용자의 매수/매도 주문 실행.

        Args:
            user_id: 사용자 id
            buy_orders: [{stock_code, stock_name, quantity, reason}] (quantity > 0)
            sell_orders: [{stock_code, stock_name, quantity, reason}] (quantity > 0)

        Returns:
            {user_id, buy_results: [...], sell_results: [...], executed_at}
        """
        buy_results: List[Dict] = []
        for order in buy_orders:
            qty = int(order.get('quantity', 0))
            if qty <= 0:
                continue
            result = await self.internal_api.execute_buy(
                user_id=user_id,
                stock_code=order['stock_code'],
                stock_name=order.get('stock_name', order['stock_code']),
                quantity=qty,
                price=0,  # 시장가
            )
            buy_results.append({
                'stock_code': order['stock_code'],
                'quantity': qty,
                'reason': order.get('reason', ''),
                'result': result,
            })

        sell_results: List[Dict] = []
        for order in sell_orders:
            qty = int(order.get('quantity', 0))
            if qty <= 0:
                logger.warning(f"[SELL] user {user_id} {order.get('stock_code')} qty<=0, skip")
                continue
            result = await self.internal_api.execute_sell(
                user_id=user_id,
                stock_code=order['stock_code'],
                stock_name=order.get('stock_name', order['stock_code']),
                quantity=qty,
                price=0,  # 시장가
            )
            sell_results.append({
                'stock_code': order['stock_code'],
                'quantity': qty,
                'reason': order.get('reason', ''),
                'result': result,
            })

        buy_ok = sum(1 for r in buy_results if r['result'].get('success'))
        sell_ok = sum(1 for r in sell_results if r['result'].get('success'))
        logger.info(f"[Execution] user {user_id}: {buy_ok}/{len(buy_results)} buys, "
                    f"{sell_ok}/{len(sell_results)} sells executed")

        return {
            'user_id': user_id,
            'buy_results': buy_results,
            'sell_results': sell_results,
            'executed_at': datetime.now().isoformat(),
        }
