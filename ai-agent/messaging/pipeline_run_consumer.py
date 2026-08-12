"""`pipeline.run.requested` 컨슈머 — 파이프라인 실행의 유일한 진입점.

스케줄 트리거(APScheduler)와 수동 트리거(POST /api/pipeline/trigger)가 모두 이 토픽으로
발행하고, 컨슈머 1개(파티션 1개)가 순차 소비하므로 `run_complete_pipeline()` 이
동시에 두 번 돌지 않는다.
"""
import logging
from datetime import date, datetime, timedelta
from typing import Dict, Optional

from .consumer import KafkaConsumerWorker
from .messages import PipelineRunRequest
from .topics import GROUP_PIPELINE_RUNNER, TOPIC_PIPELINE_RUN_REQUESTED

logger = logging.getLogger(__name__)


class PipelineRunConsumer(KafkaConsumerWorker):
    """파이프라인 실행 요청을 순차적으로 소비해 오케스트레이터를 돌린다."""

    topic = TOPIC_PIPELINE_RUN_REQUESTED
    group_id = GROUP_PIPELINE_RUNNER

    # 발행 후 이 시간을 넘긴 요청은 실행하지 않는다. 08:50 트리거가 자정을 넘겨 도는
    # 경우는 없으므로, 한 거래일 안에서 밀린 요청은 흡수하되 하루 단위 재생은 막는 폭.
    MAX_REQUEST_AGE_HOURS = 6

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

        stale_reason = self._staleness_reason(request)
        if stale_reason:
            logger.error(
                f"[PipelineRunConsumer] Skipping stale run request "
                f"(trade_date={request.trade_date}, trigger={request.trigger_type}, "
                f"requested_at={request.requested_at}): {stale_reason}"
            )
            self.last_run = {
                "trade_date": request.trade_date.isoformat(),
                "trigger_type": request.trigger_type,
                "success": False,
                "skipped": True,
                "error": stale_reason,
                "finished_at": datetime.now().isoformat(),
            }
            return

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

    def _staleness_reason(self, request: PipelineRunRequest) -> Optional[str]:
        """재생된 오래된 요청이면 사유 문자열, 실행해도 되면 None.

        ai-agent 가 하루 이상 내려가 있다 재기동하면 밀린 트리거가 순차 재생된다.
        `is_market_open(trade_date=...)` 은 과거 평일을 정상 통과시키고, 멱등키에도
        재생된 `tradeDate` 가 그대로 들어가 api-server 입장에선 처음 보는 키가 되므로
        중복 방어에도 걸리지 않는다. 즉 여기서 막지 않으면 과거 날짜로 실주문이 나간다.

        `date.today()` 기준으로 비교한다 — 발행 측(scheduler/트리거 엔드포인트)도
        같은 함수로 tradeDate 를 만들기 때문에 타임존 설정과 무관하게 일관된다.

        Args:
            request: 파싱된 실행 요청.

        Returns:
            Optional[str]: 스킵 사유. 실행 가능하면 None.
        """
        today = date.today()
        if request.trade_date != today:
            return f"tradeDate {request.trade_date} is not today ({today})"

        age = self._request_age(request.requested_at)
        if age is not None and age > timedelta(hours=self.MAX_REQUEST_AGE_HOURS):
            return (
                f"requestedAt is {age.total_seconds() / 3600:.1f}h old "
                f"(limit {self.MAX_REQUEST_AGE_HOURS}h)"
            )
        return None

    @staticmethod
    def _request_age(requested_at: Optional[str]) -> Optional[timedelta]:
        """발행 시각으로부터 흐른 시간. 값이 없거나 파싱 불가면 None(가드 미적용).

        `requestedAt` 은 계약상 선택 필드라, 없다는 이유로 실행을 막지는 않는다.
        그 경우 tradeDate 검사만으로 재생을 걸러낸다.
        """
        if not requested_at:
            return None
        try:
            published = datetime.fromisoformat(str(requested_at))
        except ValueError:
            logger.warning(f"[PipelineRunConsumer] Unparseable requestedAt: {requested_at!r}")
            return None
        now = datetime.now(published.tzinfo) if published.tzinfo else datetime.now()
        return now - published
