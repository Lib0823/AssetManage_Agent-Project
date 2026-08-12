"""FastAPI lifespan / 컨슈머 감시 테스트.

여기서 보장하는 것:
  - Kafka 프로듀서 기동이 실패해도 컨슈머는 생성된다
    (예전엔 프로듀서 실패 → 컨슈머 미생성인데, 브로커가 나중에 복구되면
     스케줄 발행만 "성공"해 소비자 없는 상태가 영구히 유지됐다)
  - 컨슈머 백그라운드 태스크가 죽으면 로그로 드러나고 상태 API 로도 보인다
  - 프로듀서가 늦게 붙어도 수동 트리거가 503 에 갇히지 않는다

Kafka/DB/스케줄러는 전부 스텁으로 대체하므로 네트워크로 나가지 않는다.
"""
import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

import main as app_module


class _StubPublisher:
    """KafkaMessagePublisher 스텁 (기동 실패/복구 시나리오 표현용)."""

    def __init__(self, start_error=None):
        self.start_error = start_error
        self.running = False
        self.published = []
        self.stopped = False

    async def start(self):
        if self.start_error is not None:
            raise self.start_error
        self.running = True

    async def ensure_started(self):
        if not self.running:
            await self.start()

    async def stop(self):
        self.stopped = True

    async def publish_pipeline_run(self, trade_date, trigger_type):
        self.published.append((trade_date, trigger_type))
        return True, {'tradeDate': trade_date.isoformat(), 'triggerType': trigger_type}


class _StubWorker:
    """컨슈머 워커 스텁. run() 은 취소될 때까지 살아 있거나 즉시 죽는다."""

    def __init__(self, run_error=None, **kwargs):
        self.run_error = run_error
        self.running = False
        self.processed_count = 0
        self.failed_count = 0
        self.current_run = None
        self.last_run = None

    async def run(self):
        if self.run_error is not None:
            raise self.run_error
        self.running = True
        await asyncio.sleep(3600)


@pytest.fixture
def lifespan_env(monkeypatch):
    """lifespan 의 외부 의존성을 전부 스텁으로 대체하고 핸들을 돌려준다."""
    env = {
        'publisher': _StubPublisher(),
        'workers': {},
    }

    monkeypatch.setattr(app_module, 'KafkaMessagePublisher', lambda *a, **kw: env['publisher'])
    monkeypatch.setattr(app_module, 'ensure_topics', AsyncMock())
    monkeypatch.setattr(app_module, 'PipelineOrchestrator', MagicMock())
    monkeypatch.setattr(app_module, 'DatabaseRepository', MagicMock())
    monkeypatch.setattr(app_module, 'PipelineScheduler', lambda *a, **kw: MagicMock())

    def _pipeline_consumer(**kwargs):
        worker = _StubWorker(run_error=env.get('pipeline_run_error'))
        env['workers']['pipeline'] = worker
        return worker

    def _result_consumer(**kwargs):
        worker = _StubWorker(run_error=env.get('trade_result_run_error'))
        env['workers']['trade_result'] = worker
        return worker

    monkeypatch.setattr(app_module, 'PipelineRunConsumer', _pipeline_consumer)
    monkeypatch.setattr(app_module, 'TradeResultConsumer', _result_consumer)

    yield env


class TestConsumerStartupIndependence:
    async def test_consumers_start_even_when_producer_startup_fails(self, lifespan_env):
        """브로커가 늦게 떠도 컨슈머는 만들어져야 한다 (컨슈머는 프로듀서에 의존하지 않는다)."""
        lifespan_env['publisher'].start_error = OSError('broker down')

        async with app_module.lifespan(app_module.app):
            assert len(app_module.consumer_tasks) == 2
            assert all(not t.done() for t in app_module.consumer_tasks)

    async def test_consumers_start_on_the_happy_path_too(self, lifespan_env):
        async with app_module.lifespan(app_module.app):
            assert len(app_module.consumer_tasks) == 2
            assert lifespan_env['publisher'].running is True

    async def test_consumer_tasks_are_cancelled_on_shutdown(self, lifespan_env):
        async with app_module.lifespan(app_module.app):
            tasks = list(app_module.consumer_tasks)

        assert tasks and all(t.done() for t in tasks)


class TestConsumerSupervision:
    async def test_dead_consumer_task_is_logged(self, lifespan_env, caplog):
        """태스크 참조만 들고 예외를 회수하지 않으면 컨슈머가 죽어도 아무 로그가 없다."""
        lifespan_env['pipeline_run_error'] = RuntimeError('consumer boom')

        with caplog.at_level('ERROR', logger='main'):
            async with app_module.lifespan(app_module.app):
                await asyncio.sleep(0)  # 태스크가 죽고 done callback 이 돌 틈을 준다
                await asyncio.sleep(0)

        assert any('pipeline-run-consumer' in r.message and 'consumer boom' in r.message
                   for r in caplog.records)

    async def test_status_exposes_dead_consumer(self, lifespan_env):
        lifespan_env['pipeline_run_error'] = RuntimeError('consumer boom')

        async with app_module.lifespan(app_module.app):
            await asyncio.sleep(0)
            await asyncio.sleep(0)
            status = app_module._consumer_status()

        assert status['pipeline-run-consumer']['alive'] is False
        assert 'consumer boom' in status['pipeline-run-consumer']['error']
        assert status['trade-result-consumer']['alive'] is True

    async def test_status_exposes_healthy_consumers(self, lifespan_env):
        async with app_module.lifespan(app_module.app):
            await asyncio.sleep(0)
            status = app_module._consumer_status()

        assert status['pipeline-run-consumer']['alive'] is True
        assert status['pipeline-run-consumer']['error'] is None
        assert status['trade-result-consumer']['alive'] is True


class TestManualTriggerProducerRecovery:
    async def test_trigger_retries_producer_start_after_broker_recovery(self, monkeypatch):
        """기동 시 프로듀서가 실패했어도, 브로커가 살아나면 트리거는 통해야 한다."""
        publisher = _StubPublisher(start_error=OSError('broker down'))
        monkeypatch.setattr(app_module, 'publisher', publisher)

        publisher.start_error = None  # 브로커 복구

        response = await app_module.trigger_pipeline_manual(app_module.ManualTriggerRequest())

        assert response.status_code == 202
        assert publisher.published

    async def test_trigger_still_503s_while_broker_is_down(self, monkeypatch):
        publisher = _StubPublisher(start_error=OSError('broker down'))
        monkeypatch.setattr(app_module, 'publisher', publisher)

        with pytest.raises(app_module.HTTPException) as exc_info:
            await app_module.trigger_pipeline_manual(app_module.ManualTriggerRequest())

        assert exc_info.value.status_code == 503


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
