"""Kafka 메시징 계층 (ai-agent ⇄ api-server, ai-agent 내부).

- `trade.order.requested` : Stage 6 매매 주문 발행 (기존 REST 호출 대체)
- `trade.order.result`    : api-server 처리 결과 수신 → trade_execution_plan 갱신
- `pipeline.run.requested`: 파이프라인 실행 요청 (스케줄/수동 트리거 공통 진입점)
"""
from .consumer import KafkaConsumerWorker
from .messages import (
    KST,
    PipelineRunRequest,
    TradeOrderResult,
    build_idempotency_key,
    build_pipeline_run_message,
    build_trade_order_message,
    now_kst_iso,
)
from .pipeline_run_consumer import PipelineRunConsumer
from .producer import KafkaMessagePublisher, ensure_topics
from .topics import (
    GROUP_PIPELINE_RUNNER,
    GROUP_TRADE_RESULT,
    PIPELINE_RUN_PARTITIONS,
    TOPIC_PIPELINE_RUN_REQUESTED,
    TOPIC_TRADE_ORDER_REQUESTED,
    TOPIC_TRADE_ORDER_RESULT,
    TRIGGER_MANUAL,
    TRIGGER_SCHEDULED,
)
from .trade_result_consumer import RESULT_STATUS_TO_DB, TradeResultConsumer

__all__ = [
    "KST",
    "KafkaConsumerWorker",
    "KafkaMessagePublisher",
    "PipelineRunConsumer",
    "PipelineRunRequest",
    "RESULT_STATUS_TO_DB",
    "TradeOrderResult",
    "TradeResultConsumer",
    "build_idempotency_key",
    "build_pipeline_run_message",
    "build_trade_order_message",
    "ensure_topics",
    "now_kst_iso",
    "GROUP_PIPELINE_RUNNER",
    "GROUP_TRADE_RESULT",
    "PIPELINE_RUN_PARTITIONS",
    "TOPIC_PIPELINE_RUN_REQUESTED",
    "TOPIC_TRADE_ORDER_REQUESTED",
    "TOPIC_TRADE_ORDER_RESULT",
    "TRIGGER_MANUAL",
    "TRIGGER_SCHEDULED",
]
