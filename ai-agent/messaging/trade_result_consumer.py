"""`trade.order.result` 컨슈머 — 주문 최종 상태를 trade_execution_plan 에 반영.

Stage 6 는 주문을 발행만 하고 `QUEUED` 로 기록한 뒤 넘어간다. api-server 가 KIS 주문을
마치고 결과를 발행하면 여기서 받아 상태를 확정한다.
"""
import asyncio
import logging
from typing import Dict, Optional

from .consumer import KafkaConsumerWorker
from .messages import TradeOrderResult
from .topics import GROUP_TRADE_RESULT, TOPIC_TRADE_ORDER_RESULT

logger = logging.getLogger(__name__)

# 메시지의 status(계약값) → trade_execution_plan.execution_status(DB 값) 매핑.
#
# 계약상 성공은 "SUCCESS" 지만, DB 에는 기존 값인 'EXECUTED' 로 적는다.
# `v_latest_trade_plan` / `v_market_overview` 두 뷰가 `execution_status = 'EXECUTED'`
# 로 체결 건수를 세고 있어서, 여기서 'SUCCESS' 를 그대로 넣으면 대시보드 집계가
# 조용히 0 이 된다. 원문 status 는 execution_result JSONB 에 그대로 남긴다.
RESULT_STATUS_TO_DB = {
    "SUCCESS": "EXECUTED",
    "FAILED": "FAILED",
}


class TradeResultConsumer(KafkaConsumerWorker):
    """주문 결과를 받아 DB 상태를 QUEUED → EXECUTED/FAILED 로 확정한다."""

    topic = TOPIC_TRADE_ORDER_RESULT
    group_id = GROUP_TRADE_RESULT

    def __init__(self, db_repo, **kwargs):
        """
        Args:
            db_repo: DatabaseRepository (동기 SQLAlchemy — 별도 스레드에서 호출한다)
        """
        super().__init__(**kwargs)
        self.db_repo = db_repo
        self.last_result: Optional[Dict] = None

    async def handle(self, value: Dict) -> None:
        result = TradeOrderResult.from_message(value)
        db_status = RESULT_STATUS_TO_DB.get(result.status, result.status)

        # 동기 SQLAlchemy 호출이라 이벤트 루프를 막지 않도록 스레드로 넘긴다.
        updated = await asyncio.to_thread(
            self.db_repo.update_trade_execution_result,
            user_id=result.user_id,
            execution_date=result.trade_date,
            stock_code=result.stock_code,
            trade_type=result.side,
            execution_status=db_status,
            order_no=result.kis_order_no,
            error_message=result.error_message,
            raw_result=value,
        )

        self.last_result = {
            "idempotency_key": result.idempotency_key,
            "status": result.status,
            "db_status": db_status,
            "updated_rows": updated,
        }

        if updated:
            logger.info(
                f"[TradeResultConsumer] {result.idempotency_key} → {db_status} "
                f"({updated} row(s) updated)"
            )
        else:
            # 계획 행이 없는 경우: 다른 인스턴스가 발행했거나 계획 저장이 실패했던 주문.
            logger.warning(
                f"[TradeResultConsumer] No trade_execution_plan row matched "
                f"{result.idempotency_key} (status={result.status})"
            )
