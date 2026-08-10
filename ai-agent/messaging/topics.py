"""Kafka 토픽 / 컨슈머 그룹 상수.

토픽 이름과 메시지 스키마는 api-server 와 공유하는 **계약**이다. 여기서 이름을 바꾸면
api-server 의 컨슈머/프로듀서와 즉시 어긋나므로 반드시 backend-engineer 와 조율한다.
"""

# ai-agent → api-server : 매매 주문 요청 (Stage 6)
TOPIC_TRADE_ORDER_REQUESTED = "trade.order.requested"

# api-server → ai-agent : 매매 주문 처리 결과
TOPIC_TRADE_ORDER_RESULT = "trade.order.result"

# ai-agent → ai-agent : 파이프라인 실행 요청 (스케줄 트리거 + 수동 트리거 공통 진입점)
TOPIC_PIPELINE_RUN_REQUESTED = "pipeline.run.requested"

# 컨슈머 그룹 (그룹당 컨슈머 1개 = 순차 처리)
GROUP_TRADE_RESULT = "ai-agent.trade-result"
GROUP_PIPELINE_RUNNER = "ai-agent.pipeline-runner"

# pipeline.run.requested 는 파티션 1개로 고정한다.
# 파티션이 여러 개면 컨슈머가 1개여도 여러 파티션을 동시에 할당받고, 재조정(rebalance)
# 이나 컨슈머 증설 시 병렬 소비가 가능해져 "파이프라인 동시 실행 방지"라는 목적이 깨진다.
PIPELINE_RUN_PARTITIONS = 1

# 트리거 종류
TRIGGER_SCHEDULED = "SCHEDULED"
TRIGGER_MANUAL = "MANUAL"
