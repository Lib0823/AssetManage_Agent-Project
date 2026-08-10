"""Trade Executor Module (multi-user, Kafka).

유저별 매수/매도 주문을 Kafka `trade.order.requested` 로 발행한다. 실제 KIS 주문은
api-server 가 이 토픽을 소비해 대행하며(사용자 KIS 키는 api-server DB 에만 있다),
ai-agent 는 **체결 결과를 기다리지 않고** 발행 직후 다음 주문으로 넘어간다.

기존 REST 직접 호출(`InternalApiClient.execute_buy/execute_sell`)과 달라진 점:
  - 주문 유실 방지: 브로커가 메시지를 보관하므로 api-server 가 죽어 있어도 주문이 남는다.
  - 재시도: 프로듀서 재시도(acks=all) + 컨슈머 재처리로 api-server 쪽에서 흡수한다.
  - 상태: 발행 성공은 `QUEUED` 일 뿐 체결이 아니다. 최종 상태는 `trade.order.result`
    컨슈머가 확정한다.
"""
import logging
from datetime import date, datetime
from typing import Dict, List, Optional

from messaging import KafkaMessagePublisher
from messaging.messages import SIDE_BUY, SIDE_SELL

logger = logging.getLogger(__name__)


class TradeExecutor:
    """Publish per-user buy/sell orders to Kafka (fire-and-forget for the pipeline)."""

    def __init__(self, publisher: KafkaMessagePublisher):
        """
        Args:
            publisher: Kafka 프로듀서 래퍼 (lifespan 이 만든 인스턴스를 공유)
        """
        self.publisher = publisher

    async def execute_for_user(
        self,
        user_id: int,
        buy_orders: List[Dict],
        sell_orders: List[Dict],
        trade_date: Optional[date] = None,
    ) -> Dict:
        """
        한 사용자의 매수/매도 주문을 Kafka 로 발행한다.

        Args:
            user_id: 사용자 id
            buy_orders: [{stock_code, stock_name, quantity, reason}] (quantity > 0)
            sell_orders: [{stock_code, stock_name, quantity, reason}] (quantity > 0)
            trade_date: 멱등키에 들어가는 거래일 (기본값: 오늘)

        Returns:
            {user_id, trade_date, buy_results: [...], sell_results: [...], executed_at}
            각 result 는 {'success': bool, 'status': 'QUEUED'|'FAILED', 'idempotency_key': str}
        """
        if trade_date is None:
            trade_date = date.today()

        buy_results = await self._publish_orders(user_id, buy_orders, SIDE_BUY, trade_date)
        sell_results = await self._publish_orders(user_id, sell_orders, SIDE_SELL, trade_date)

        buy_ok = sum(1 for r in buy_results if r['result'].get('success'))
        sell_ok = sum(1 for r in sell_results if r['result'].get('success'))
        logger.info(f"[Execution] user {user_id}: {buy_ok}/{len(buy_results)} buys, "
                    f"{sell_ok}/{len(sell_results)} sells queued to Kafka")

        return {
            'user_id': user_id,
            'trade_date': trade_date.isoformat(),
            'buy_results': buy_results,
            'sell_results': sell_results,
            'executed_at': datetime.now().isoformat(),
        }

    async def _publish_orders(
        self,
        user_id: int,
        orders: List[Dict],
        side: str,
        trade_date: date,
    ) -> List[Dict]:
        """주문 리스트를 순서대로 발행한다. 한 건의 실패가 나머지를 막지 않는다."""
        results: List[Dict] = []
        for order in orders:
            qty = int(order.get('quantity', 0))
            if qty <= 0:
                if side == SIDE_SELL:
                    logger.warning(f"[SELL] user {user_id} {order.get('stock_code')} qty<=0, skip")
                continue

            published, key, _ = await self.publisher.publish_trade_order(
                user_id=user_id,
                stock_code=order['stock_code'],
                side=side,
                quantity=qty,
                trade_date=trade_date,
                price=0,  # 시장가 (기존 REST 경로와 동일)
            )
            results.append({
                'stock_code': order['stock_code'],
                'quantity': qty,
                'reason': order.get('reason', ''),
                'result': {
                    'success': published,
                    'status': 'QUEUED' if published else 'FAILED',
                    'idempotency_key': key,
                },
            })
        return results
