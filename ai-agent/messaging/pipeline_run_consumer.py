"""`pipeline.run.requested` 컨슈머 — 파이프라인 실행의 유일한 진입점.

스케줄 트리거(APScheduler)와 수동 트리거(POST /api/pipeline/trigger)가 모두 이 토픽으로
발행하고, 컨슈머 1개(파티션 1개)가 순차 소비하므로 `run_complete_pipeline()` 이
동시에 두 번 돌지 않는다.
"""
import logging
from datetime import datetime
from typing import Dict, Optional

from .consumer import KafkaConsumerWorker
from .messages import PipelineRunRequest
from .topics import GROUP_PIPELINE_RUNNER, TOPIC_PIPELINE_RUN_REQUESTED

logger = logging.getLogger(__name__)


class PipelineRunConsumer(KafkaConsumerWorker):
    """파이프라인 실행 요청을 순차적으로 소비해 오케스트레이터를 돌린다."""

    topic = TOPIC_PIPELINE_RUN_REQUESTED
    group_id = GROUP_PIPELINE_RUNNER

    def __init__(self, orchestrator, **kwargs):
        """
        Args:
            orchestrator: PipelineOrchestrator (lifespan 이 만든 인스턴스를 공유해
                KIS OAuth 토큰 캐시를 재사용한다)
        """
        super().__init__(**kwargs)
        self.orchestrator = orchestrator
        self.current_run: Optional[str] = None
        self.last_run: Optional[Dict] = None

    async def handle(self, value: Dict) -> None:
        request = PipelineRunRequest.from_message(value)
        started_at = datetime.now()
        self.current_run = request.trade_date.isoformat()

        logger.info(
            f"[PipelineRunConsumer] Running pipeline for {request.trade_date} "
            f"(trigger={request.trigger_type})"
        )
        try:
            result = await self.orchestrator.run_complete_pipeline(trade_date=request.trade_date)
        finally:
            self.current_run = None

        elapsed = (datetime.now() - started_at).total_seconds()
        success = bool((result or {}).get("success"))
        self.last_run = {
            "trade_date": request.trade_date.isoformat(),
            "trigger_type": request.trigger_type,
            "success": success,
            "error": (result or {}).get("error"),
            "elapsed_seconds": round(elapsed, 3),
            "finished_at": datetime.now().isoformat(),
        }

        if success:
            logger.info(
                f"[PipelineRunConsumer] Pipeline finished for {request.trade_date} in {elapsed:.1f}s"
            )
        else:
            logger.error(
                f"[PipelineRunConsumer] Pipeline failed for {request.trade_date}: "
                f"{(result or {}).get('error')}"
            )
