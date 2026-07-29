"""APScheduler configuration for automatic pipeline execution."""
import logging
from apscheduler.schedulers.background import BackgroundScheduler
from apscheduler.triggers.cron import CronTrigger
from pytz import timezone

from config import settings
from .orchestrator import PipelineOrchestrator

logger = logging.getLogger(__name__)


class PipelineScheduler:
    """
    Background scheduler for automatic pipeline execution.

    Default schedule: Weekdays 08:50 KST (before market open at 09:00)
    """

    def __init__(self):
        self.orchestrator = PipelineOrchestrator()
        self.scheduler = BackgroundScheduler()

        # Parse cron expression
        self.cron_expression = settings.pipeline_cron
        self.tz = timezone(settings.pipeline_timezone)

        logger.info(f"PipelineScheduler initialized with cron: {self.cron_expression} ({settings.pipeline_timezone})")

    def _job_wrapper(self):
        """Wrapper function for scheduled job execution (full pipeline, Stage 1~6)."""
        logger.info("Scheduled pipeline execution triggered (full pipeline)")

        try:
            result = self.orchestrator.run_complete_pipeline_sync()

            if result.get('success'):
                logger.info("Scheduled pipeline completed successfully (Stage 1~6)")
            else:
                logger.error(f"Scheduled pipeline failed: {result.get('error')}")

        except Exception as e:
            logger.exception(f"Unexpected error in scheduled pipeline: {e}")

    def start(self):
        """Start the background scheduler."""
        if not settings.pipeline_enabled:
            logger.warning("Pipeline scheduler is disabled in configuration")
            return

        # Parse cron expression: "50 8 * * 1-5" → minute=50, hour=8, day_of_week=1-5
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

    def trigger_manual(self):
        """Manually trigger full pipeline execution (for testing/manual runs)."""
        logger.info("Manual pipeline execution triggered (full pipeline)")
        return self.orchestrator.run_complete_pipeline_sync()

    def get_next_run_time(self):
        """Get the next scheduled run time."""
        if not self.scheduler.running:
            return None

        job = self.scheduler.get_job('full_pipeline_job')
        if job:
            return job.next_run_time
        return None
