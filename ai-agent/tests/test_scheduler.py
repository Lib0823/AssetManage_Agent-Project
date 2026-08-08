"""Unit tests for PipelineScheduler (APScheduler cron 배선).

핵심 검증 포인트:
  - 자동 스케줄은 **전체 파이프라인(Stage 1~6)** 을 실행한다
    (`run_complete_pipeline_sync`, Stage 1 전용 `run_stage1_sync` 가 아니다)
  - 평일 08:50 KST cron 이 실제 CronTrigger 로 정확히 변환된다
  - 잡 내부 예외가 스케줄러 스레드를 죽이지 않는다
"""
from datetime import datetime
from unittest.mock import MagicMock, patch

import pytest
from apscheduler.triggers.cron import CronTrigger
from pytz import timezone

from config import settings
from pipeline.scheduler import PipelineScheduler


@pytest.fixture
def sched():
    """PipelineOrchestrator / BackgroundScheduler 를 mock 으로 대체한 스케줄러."""
    with patch('pipeline.scheduler.PipelineOrchestrator') as orch_cls, \
         patch('pipeline.scheduler.BackgroundScheduler') as bg_cls:
        orchestrator = MagicMock(name='orchestrator')
        orchestrator.run_complete_pipeline_sync = MagicMock(return_value={'success': True})
        orch_cls.return_value = orchestrator

        background = MagicMock(name='background_scheduler')
        background.running = False
        bg_cls.return_value = background

        scheduler = PipelineScheduler()
        scheduler._orchestrator_mock = orchestrator
        scheduler._background_mock = background
        yield scheduler


class TestInit:
    def test_reads_cron_settings(self, sched):
        assert sched.cron_expression == settings.pipeline_cron
        assert sched.tz == timezone(settings.pipeline_timezone)

    def test_default_schedule_is_weekday_0850_kst(self):
        """기본 설정은 평일 08:50 Asia/Seoul (장 시작 09:00 직전)."""
        assert settings.pipeline_cron == '50 8 * * mon-fri'
        assert settings.pipeline_timezone == 'Asia/Seoul'

    def test_creates_orchestrator(self, sched):
        assert sched.orchestrator is sched._orchestrator_mock


class TestJobWrapper:
    """_job_wrapper 는 전체 파이프라인을 돌리고 어떤 예외도 밖으로 흘리지 않는다."""

    def test_runs_full_pipeline_not_stage1_only(self, sched):
        sched._job_wrapper()

        sched._orchestrator_mock.run_complete_pipeline_sync.assert_called_once_with()
        sched._orchestrator_mock.run_stage1_sync.assert_not_called()

    def test_failure_result_does_not_raise(self, sched):
        sched._orchestrator_mock.run_complete_pipeline_sync.return_value = {
            'success': False, 'error': '오늘은 휴장일입니다',
        }

        sched._job_wrapper()  # 예외 없이 종료되어야 한다

        sched._orchestrator_mock.run_complete_pipeline_sync.assert_called_once()

    def test_unexpected_exception_is_swallowed(self, sched):
        sched._orchestrator_mock.run_complete_pipeline_sync.side_effect = RuntimeError('boom')

        sched._job_wrapper()  # 스케줄러 스레드가 죽으면 안 된다

    def test_non_dict_result_is_tolerated(self, sched):
        """None 이 반환돼도 .get 접근에서 터지지 않고 예외를 흡수한다."""
        sched._orchestrator_mock.run_complete_pipeline_sync.return_value = None

        sched._job_wrapper()


class TestStart:
    def test_disabled_scheduler_does_not_register_job(self, sched, monkeypatch):
        monkeypatch.setattr(settings, 'pipeline_enabled', False)

        sched.start()

        sched._background_mock.add_job.assert_not_called()
        sched._background_mock.start.assert_not_called()

    @pytest.mark.parametrize('bad_cron', ['50 8 * *', '50 8 * * 1-5 *', ''])
    def test_invalid_cron_expression_aborts(self, sched, monkeypatch, bad_cron):
        monkeypatch.setattr(settings, 'pipeline_enabled', True)
        sched.cron_expression = bad_cron

        sched.start()

        sched._background_mock.add_job.assert_not_called()
        sched._background_mock.start.assert_not_called()

    def test_registers_job_with_expected_identity(self, sched, monkeypatch):
        monkeypatch.setattr(settings, 'pipeline_enabled', True)

        sched.start()

        args, kwargs = sched._background_mock.add_job.call_args
        assert args[0] == sched._job_wrapper
        assert kwargs['id'] == 'full_pipeline_job'
        assert kwargs['name'] == 'Full Pipeline (Stage 1-6)'
        assert kwargs['replace_existing'] is True
        sched._background_mock.start.assert_called_once()

    def test_cron_fields_are_parsed_positionally(self, sched, monkeypatch):
        monkeypatch.setattr(settings, 'pipeline_enabled', True)
        sched.cron_expression = '50 8 * * 1-5'

        sched.start()

        trigger = sched._background_mock.add_job.call_args.kwargs['trigger']
        assert isinstance(trigger, CronTrigger)
        fields = {f.name: str(f) for f in trigger.fields}
        assert fields['minute'] == '50'
        assert fields['hour'] == '8'
        assert fields['day'] == '*'
        assert fields['month'] == '*'
        assert fields['day_of_week'] == '1-5'
        assert trigger.timezone == timezone(settings.pipeline_timezone)

    def test_actual_fire_days_are_mon_to_fri(self, sched, monkeypatch):
        """회귀 방지: day_of_week='1-5'(숫자) 는 APScheduler 에서 화~토로 해석되는 버그가
        있었다(표준 crontab 은 0=일요일 기준이지만 APScheduler CronTrigger 는 Python
        weekday() 기준 0=월요일). 이름 기반 'mon-fri' 로 고쳐 실제 월~금에만 발화하는지
        검증한다.
        """
        monkeypatch.setattr(settings, 'pipeline_enabled', True)

        sched.start()
        trigger = sched._background_mock.add_job.call_args.kwargs['trigger']

        kst = timezone('Asia/Seoul')
        cursor = kst.localize(datetime(2026, 8, 2, 0, 0))  # 일요일부터 한 주 순회
        fired = []
        for _ in range(6):
            cursor = trigger.get_next_fire_time(None, cursor)
            fired.append(cursor.strftime('%a'))
            cursor = cursor.replace(hour=23, minute=59)

        assert fired == ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Mon']
        assert 'Sat' not in fired
        assert 'Sun' not in fired

    def test_friday_evening_rolls_to_monday_not_saturday(self, sched, monkeypatch):
        """회귀 방지: 금요일 밤 이후 다음 실행은 (휴장일인 토요일이 아니라) 월요일이어야 한다."""
        monkeypatch.setattr(settings, 'pipeline_enabled', True)

        sched.start()
        trigger = sched._background_mock.add_job.call_args.kwargs['trigger']

        kst = timezone('Asia/Seoul')
        friday_evening = kst.localize(datetime(2026, 8, 7, 20, 0))
        next_run = trigger.get_next_fire_time(None, friday_evening)

        assert next_run.strftime('%Y-%m-%d %H:%M') == '2026-08-10 08:50'
        assert next_run.weekday() == 0  # 월요일

    def test_next_fire_time_same_day_before_cutoff(self, sched, monkeypatch):
        monkeypatch.setattr(settings, 'pipeline_enabled', True)

        sched.start()
        trigger = sched._background_mock.add_job.call_args.kwargs['trigger']

        kst = timezone('Asia/Seoul')
        wednesday_dawn = kst.localize(datetime(2026, 8, 5, 6, 0))
        next_run = trigger.get_next_fire_time(None, wednesday_dawn)

        assert next_run.strftime('%Y-%m-%d %H:%M') == '2026-08-05 08:50'


class TestStop:
    def test_shutdown_when_running(self, sched):
        sched._background_mock.running = True

        sched.stop()

        sched._background_mock.shutdown.assert_called_once()

    def test_noop_when_not_running(self, sched):
        sched._background_mock.running = False

        sched.stop()

        sched._background_mock.shutdown.assert_not_called()


class TestManualTrigger:
    def test_manual_trigger_runs_full_pipeline(self, sched):
        result = sched.trigger_manual()

        assert result == {'success': True}
        sched._orchestrator_mock.run_complete_pipeline_sync.assert_called_once_with()

    def test_manual_trigger_propagates_exception(self, sched):
        """수동 트리거는 API 응답으로 오류를 알려야 하므로 삼키지 않는다."""
        sched._orchestrator_mock.run_complete_pipeline_sync.side_effect = RuntimeError('boom')

        with pytest.raises(RuntimeError):
            sched.trigger_manual()


class TestNextRunTime:
    def test_none_when_scheduler_not_running(self, sched):
        sched._background_mock.running = False

        assert sched.get_next_run_time() is None
        sched._background_mock.get_job.assert_not_called()

    def test_returns_job_next_run_time(self, sched):
        sched._background_mock.running = True
        job = MagicMock()
        job.next_run_time = datetime(2026, 8, 10, 8, 50)
        sched._background_mock.get_job.return_value = job

        assert sched.get_next_run_time() == datetime(2026, 8, 10, 8, 50)
        sched._background_mock.get_job.assert_called_once_with('full_pipeline_job')

    def test_none_when_job_missing(self, sched):
        sched._background_mock.running = True
        sched._background_mock.get_job.return_value = None

        assert sched.get_next_run_time() is None
