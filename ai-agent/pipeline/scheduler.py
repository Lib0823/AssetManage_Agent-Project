"""APScheduler configuration for automatic pipeline execution.

스케줄러는 더 이상 `PipelineOrchestrator` 를 직접 실행하지 않는다. 평일 08:50 이 되면
`pipeline.run.requested` 토픽에 실행 요청만 발행하고 즉시 반환하며, 실제 실행은 단일
컨슈머(`PipelineRunConsumer`)가 순차적으로 처리한다. 수동 트리거
(`POST /api/pipeline/trigger`)도 같은 토픽으로 들어오므로, 두 경로가 겹쳐도
파이프라인이 동시에 두 번 돌지 않는다.
"""
import logging
from datetime import date
from typing import Optional

from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from pytz import timezone

from config import settings
from messaging import KafkaMessagePublisher, TRIGGER_SCHEDULED

logger = logging.getLogger(__name__)


class PipelineScheduler:
    """
    Background scheduler that queues pipeline runs.

    Default schedule: Weekdays 08:50 KST (before market open at 09:00)
    """

    def __init__(self, publisher: Optional[KafkaMessagePublisher] = None):
        """
        Args:
            publisher: Kafka 프로듀서 (lifespan 이 만든 인스턴스 공유). 생략하면
                자체 인스턴스를 만들고, 이벤트 루프 밖에서 일회용 프로듀서로 발행한다.
        """
        self.publisher = publisher or KafkaMessagePublisher()
        self.scheduler = BackgroundScheduler()

        # Parse cron expression
        self.cron_expression = settings.pipeline_cron
        self.tz = timezone(settings.pipeline_timezone)

        logger.info(f"PipelineScheduler initialized with cron: {self.cron_expression} ({settings.pipeline_timezone})")

    def _job_wrapper(self):
        """Scheduled job: `pipeline.run.requested` 발행만 하고 즉시 끝난다.

        APScheduler 워커 스레드에서 돌기 때문에 sync 발행 경로를 쓴다.
        어떤 예외도 밖으로 흘리지 않는다(스케줄러 스레드 보호).
        """
        logger.info("Scheduled pipeline trigger fired — queueing pipeline run")

        try:
            trade_date = date.today()
            queued, message = self.publisher.publish_pipeline_run_sync(
                trade_date=trade_date, trigger_type=TRIGGER_SCHEDULED
            )

            if queued:
                logger.info(f"Scheduled pipeline run queued for {trade_date}")
            else:
                logger.error(f"Failed to queue scheduled pipeline run for {trade_date}: {message}")

        except Exception as e:
            logger.exception(f"Unexpected error while queueing scheduled pipeline: {e}")

    def start(self):
        """Start the background scheduler."""
        if not settings.pipeline_enabled:
            logger.warning("Pipeline scheduler is disabled in configuration")
            return

        # Parse cron expression: "50 8 * * mon-fri" → minute=50, hour=8, day_of_week=mon-fri
        cron_parts = self.cron_expression.split()
        if len(cron_parts) != 5:
            logger.error(f"Invalid cron expression: {self.cron_expression}")
            return

        minute, hour, day, month, day_of_week = cron_parts

        trigger = CronTrigger(
            minute=minute,
            hour=hour,
            day=day,
            month=month,
            day_of_week=day_of_week,
            timezone=self.tz
        )

        self.scheduler.add_job(
            self._job_wrapper,
            trigger=trigger,
            id='full_pipeline_job',
            name='Full Pipeline (Stage 1-6)',
            replace_existing=True
        )

        self.scheduler.start()
        logger.info(f"Pipeline scheduler started: {self.cron_expression} {settings.pipeline_timezone}")

    def stop(self):
        """Stop the background scheduler."""
        if self.scheduler.running:
            self.scheduler.shutdown()
            logger.info("Pipeline scheduler stopped")

    def trigger_manual(self, trade_date: Optional[date] = None):
        """수동으로 파이프라인 실행을 큐잉한다 (테스트/운영 점검용).

        Returns:
            (queued: bool, message: dict) — 발행 성공 여부와 발행한 메시지
        """
        target_date = trade_date or date.today()
        logger.info(f"Manual pipeline trigger — queueing run for {target_date}")
        return self.publisher.publish_pipeline_run_sync(
            trade_date=target_date, trigger_type='MANUAL'
        )

    def get_next_run_time(self):
        """Get the next scheduled run time."""
        if not self.scheduler.running:
            return None

        job = self.scheduler.get_job('full_pipeline_job')
        if job:
            return job.next_run_time
        return None
