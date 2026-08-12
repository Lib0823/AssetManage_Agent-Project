"""컨슈머 단위 테스트 (브로커 없이 handle/_process 경로만 검증).

여기서 보장하는 것:
  - 오프셋은 처리 **후** 커밋된다 (처리 전 커밋 → 메시지 증발 방지)
  - 핸들러가 터져도 커밋한다 (ai-agent 쪽엔 DLQ 가 없어 무한 재처리로 루프가 멈춘다)
  - 장시간 handle() 동안 그룹에서 이탈하지 않는 컨슈머 설정
  - 오래된(재생된) 실행 요청은 실행하지 않는다
  - 결과 메시지 → repository 갱신 인자 매핑
  - 파이프라인 실행 요청 → orchestrator 호출
브로커를 실제로 태우는 검증은 tests/test_kafka_integration.py.
"""
import asyncio
import json
from datetime import date, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from messaging.messages import now_kst_iso

from messaging import PipelineRunConsumer, TradeResultConsumer
from messaging.consumer import KafkaConsumerWorker
from messaging.topics import (
    GROUP_PIPELINE_RUNNER,
    GROUP_TRADE_RESULT,
    TOPIC_PIPELINE_RUN_REQUESTED,
    TOPIC_TRADE_ORDER_RESULT,
)


def _today() -> str:
    """오늘 거래일 문자열. 신선도 가드가 오늘이 아닌 요청을 스킵하므로 필요하다."""
    return date.today().isoformat()


def _message(payload: dict, partition: int = 0, offset: int = 0):
    """aiokafka ConsumerRecord 흉내 (테스트에 필요한 필드만)."""
    return SimpleNamespace(
        value=json.dumps(payload).encode('utf-8'),
        partition=partition,
        offset=offset,
    )


class _RecordingConsumer(KafkaConsumerWorker):
    """handle 호출을 기록하는 테스트용 서브클래스."""

    topic = 'test.topic'
    group_id = 'test.group'

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.handled = []
        self.raise_on_handle = None

    async def handle(self, value):
        if self.raise_on_handle:
            raise self.raise_on_handle
        self.handled.append(value)


@pytest.fixture
def worker():
    w = _RecordingConsumer(bootstrap_servers='stub:9092')
    w._consumer = MagicMock(name='AIOKafkaConsumer')
    w._consumer.commit = AsyncMock()
    return w


class TestConsumerBase:
    async def test_decodes_and_handles(self, worker):
        await worker._process(_message({'a': 1}))

        assert worker.handled == [{'a': 1}]
        assert worker.processed_count == 1

    async def test_commits_after_successful_handling(self, worker):
        order = []
        worker._consumer.commit = AsyncMock(side_effect=lambda: order.append('commit'))

        async def _handle(value):
            order.append('handle')

        worker.handle = _handle
        await worker._process(_message({'a': 1}))

        assert order == ['handle', 'commit']

    async def test_commits_even_when_handler_fails(self, worker):
        """poison 메시지에서 무한 재처리로 루프가 멈추지 않도록 커밋한다."""
        worker.raise_on_handle = RuntimeError('boom')

        await worker._process(_message({'a': 1}))  # 예외가 새어나오면 안 된다

        worker._consumer.commit.assert_awaited_once()
        assert worker.failed_count == 1
        assert worker.processed_count == 0

    async def test_invalid_json_is_counted_as_failure(self, worker):
        bad = SimpleNamespace(value=b'not json', partition=0, offset=1)

        await worker._process(bad)

        assert worker.failed_count == 1
        worker._consumer.commit.assert_awaited_once()

    async def test_empty_body_is_counted_as_failure(self, worker):
        await worker._process(SimpleNamespace(value=None, partition=0, offset=2))

        assert worker.failed_count == 1

    async def test_commit_failure_does_not_raise(self, worker):
        worker._consumer.commit = AsyncMock(side_effect=RuntimeError('commit boom'))

        await worker._process(_message({'a': 1}))  # 예외가 새어나오면 안 된다

        assert worker.processed_count == 1

    async def test_handler_failure_logs_partition_and_offset(self, worker, caplog):
        worker.raise_on_handle = RuntimeError('boom')

        with caplog.at_level('ERROR', logger='messaging.consumer'):
            await worker._process(_message({'a': 1}, partition=3, offset=77))

        assert any('partition=3' in r.message and 'offset=77' in r.message for r in caplog.records)

    async def test_base_handle_is_abstract(self):
        with pytest.raises(NotImplementedError):
            await KafkaConsumerWorker(bootstrap_servers='stub:9092').handle({})


class TestConsumerGroupMembership:
    """장시간 handle() 이 그룹 이탈을 유발하지 않도록 하는 기동 파라미터."""

    @pytest.fixture
    def created_kwargs(self, monkeypatch):
        """AIOKafkaConsumer 생성 인자를 가로채는 픽스처."""
        captured = {}

        def _factory(*args, **kwargs):
            captured.update(kwargs)
            consumer = MagicMock(name='AIOKafkaConsumer')
            consumer.start = AsyncMock()
            return consumer

        monkeypatch.setattr('messaging.consumer.AIOKafkaConsumer', _factory)
        return captured

    async def test_max_poll_interval_outlives_a_full_pipeline_run(self, created_kwargs):
        """기본값 300초면 파이프라인이 5분만 넘겨도 그룹에서 쫓겨나 무한 재처리한다."""
        await _RecordingConsumer(bootstrap_servers='stub:9092').start()

        assert created_kwargs['max_poll_interval_ms'] >= 3_600_000

    async def test_pipeline_run_consumer_gets_the_same_guard(self, created_kwargs):
        consumer = PipelineRunConsumer(orchestrator=MagicMock(), bootstrap_servers='stub:9092')

        await consumer.start()

        assert created_kwargs['max_poll_interval_ms'] >= 3_600_000

    async def test_trade_result_consumer_gets_the_same_guard(self, created_kwargs):
        consumer = TradeResultConsumer(db_repo=MagicMock(), bootstrap_servers='stub:9092')

        await consumer.start()

        assert created_kwargs['max_poll_interval_ms'] >= 3_600_000

    async def test_start_is_retried_until_the_broker_is_reachable(self, monkeypatch):
        """브로커가 늦게 떠도 컨슈머는 스스로 붙어야 한다(컨테이너 기동 순서 어긋남)."""
        attempts = {'n': 0}

        def _factory(*args, **kwargs):
            attempts['n'] += 1
            consumer = MagicMock(name='AIOKafkaConsumer')
            if attempts['n'] < 3:
                consumer.start = AsyncMock(side_effect=OSError('broker down'))
            else:
                consumer.start = AsyncMock()
            return consumer

        monkeypatch.setattr('messaging.consumer.AIOKafkaConsumer', _factory)
        monkeypatch.setattr(asyncio, 'sleep', AsyncMock())

        worker = _RecordingConsumer(bootstrap_servers='stub:9092')
        await worker._start_with_retry()

        assert attempts['n'] == 3
        assert worker.running


class TestTradeResultConsumer:
    @pytest.fixture
    def db_repo(self):
        repo = MagicMock(name='DatabaseRepository')
        repo.update_trade_execution_result = MagicMock(return_value=1)
        return repo

    @pytest.fixture
    def consumer(self, db_repo):
        c = TradeResultConsumer(db_repo=db_repo, bootstrap_servers='stub:9092')
        c._consumer = MagicMock()
        c._consumer.commit = AsyncMock()
        return c

    def test_topic_and_group(self, consumer):
        assert consumer.topic == TOPIC_TRADE_ORDER_RESULT
        assert consumer.group_id == GROUP_TRADE_RESULT

    async def test_success_updates_row_as_executed(self, consumer, db_repo):
        await consumer.handle({
            'idempotencyKey': '1:005930:2026-08-09:BUY',
            'userId': 1, 'stockCode': '005930', 'side': 'BUY',
            'status': 'SUCCESS', 'kisOrderNo': '0000123', 'errorMessage': None,
            'processedAt': '2026-08-09T08:55:03+09:00',
        })

        kwargs = db_repo.update_trade_execution_result.call_args.kwargs
        assert kwargs['user_id'] == 1
        assert kwargs['execution_date'] == date(2026, 8, 9)
        assert kwargs['stock_code'] == '005930'
        assert kwargs['trade_type'] == 'BUY'
        assert kwargs['execution_status'] == 'EXECUTED'  # SUCCESS → EXECUTED (뷰 호환)
        assert kwargs['order_no'] == '0000123'

    async def test_failed_result_passes_error_message(self, consumer, db_repo):
        await consumer.handle({
            'idempotencyKey': '2:000660:2026-08-09:SELL',
            'status': 'FAILED', 'errorMessage': '장 종료',
        })

        kwargs = db_repo.update_trade_execution_result.call_args.kwargs
        assert kwargs['execution_status'] == 'FAILED'
        assert kwargs['error_message'] == '장 종료'
        assert kwargs['trade_type'] == 'SELL'

    async def test_raw_message_is_persisted_for_traceability(self, consumer, db_repo):
        payload = {'idempotencyKey': '1:005930:2026-08-09:BUY', 'status': 'SUCCESS'}

        await consumer.handle(payload)

        assert db_repo.update_trade_execution_result.call_args.kwargs['raw_result'] == payload

    async def test_unknown_status_is_passed_through(self, consumer, db_repo):
        """매핑에 없는 상태는 그대로 넘긴다 (계약 확장 시 조용히 EXECUTED 로 오인하지 않도록)."""
        await consumer.handle({'idempotencyKey': '1:005930:2026-08-09:BUY', 'status': 'REJECTED'})

        assert db_repo.update_trade_execution_result.call_args.kwargs['execution_status'] == 'REJECTED'

    async def test_no_matching_row_logs_warning(self, consumer, db_repo, caplog):
        db_repo.update_trade_execution_result = MagicMock(return_value=0)

        with caplog.at_level('WARNING', logger='messaging.trade_result_consumer'):
            await consumer.handle({'idempotencyKey': '9:005930:2026-08-09:BUY', 'status': 'SUCCESS'})

        assert any('No trade_execution_plan row matched' in r.message for r in caplog.records)

    async def test_malformed_message_raises_and_is_absorbed_by_process(self, consumer):
        """스키마 위반은 handle 에서 ValueError → _process 가 흡수하고 커밋한다."""
        with pytest.raises(ValueError):
            await consumer.handle({'status': 'SUCCESS'})

        await consumer._process(_message({'status': 'SUCCESS'}))
        assert consumer.failed_count == 1

    async def test_db_error_propagates_to_process(self, consumer, db_repo):
        db_repo.update_trade_execution_result = MagicMock(side_effect=RuntimeError('db down'))

        await consumer._process(_message({
            'idempotencyKey': '1:005930:2026-08-09:BUY', 'status': 'SUCCESS',
        }))

        assert consumer.failed_count == 1
        consumer._consumer.commit.assert_awaited_once()


class TestPipelineRunConsumer:
    @pytest.fixture
    def orchestrator(self):
        orch = MagicMock(name='PipelineOrchestrator')
        orch.run_complete_pipeline = AsyncMock(return_value={'success': True})
        return orch

    @pytest.fixture
    def consumer(self, orchestrator):
        c = PipelineRunConsumer(orchestrator=orchestrator, bootstrap_servers='stub:9092')
        c._consumer = MagicMock()
        c._consumer.commit = AsyncMock()
        return c

    def test_topic_and_group(self, consumer):
        assert consumer.topic == TOPIC_PIPELINE_RUN_REQUESTED
        assert consumer.group_id == GROUP_PIPELINE_RUNNER

    async def test_runs_complete_pipeline_with_trade_date(self, consumer, orchestrator):
        await consumer.handle({'tradeDate': _today(), 'triggerType': 'MANUAL'})

        orchestrator.run_complete_pipeline.assert_awaited_once_with(trade_date=date.today())

    async def test_last_run_is_recorded(self, consumer):
        await consumer.handle({'tradeDate': _today(), 'triggerType': 'SCHEDULED'})

        assert consumer.last_run['trade_date'] == _today()
        assert consumer.last_run['trigger_type'] == 'SCHEDULED'
        assert consumer.last_run['success'] is True
        assert consumer.last_run['elapsed_seconds'] >= 0

    async def test_current_run_is_exposed_while_running_and_cleared_after(self, consumer, orchestrator):
        seen = {}

        async def _run(trade_date=None):
            seen['during'] = consumer.current_run
            return {'success': True}

        orchestrator.run_complete_pipeline = AsyncMock(side_effect=_run)

        await consumer.handle({'tradeDate': _today(), 'triggerType': 'MANUAL'})

        assert seen['during'] == _today()
        assert consumer.current_run is None

    async def test_failed_pipeline_is_recorded_not_raised(self, consumer, orchestrator):
        orchestrator.run_complete_pipeline = AsyncMock(
            return_value={'success': False, 'error': '오늘은 휴장일입니다'}
        )

        await consumer.handle({'tradeDate': _today(), 'triggerType': 'SCHEDULED'})

        assert consumer.last_run['success'] is False
        assert consumer.last_run['error'] == '오늘은 휴장일입니다'

    async def test_orchestrator_exception_clears_current_run(self, consumer, orchestrator):
        orchestrator.run_complete_pipeline = AsyncMock(side_effect=RuntimeError('kis down'))

        with pytest.raises(RuntimeError):
            await consumer.handle({'tradeDate': _today(), 'triggerType': 'MANUAL'})

        assert consumer.current_run is None  # 다음 실행이 막히면 안 된다

    async def test_messages_are_processed_one_at_a_time(self, consumer, orchestrator):
        """같은 컨슈머가 두 메시지를 받으면 두 번째는 첫 번째가 끝난 뒤에 시작된다.

        (동시 실행 방지의 단위 레벨 근거 — 실제 브로커 검증은 통합 테스트에 있다)
        """
        concurrent = []
        active = 0

        async def _run(trade_date=None):
            nonlocal active
            active += 1
            concurrent.append(active)
            await asyncio.sleep(0.05)
            active -= 1
            return {'success': True}

        orchestrator.run_complete_pipeline = AsyncMock(side_effect=_run)

        # _process 를 순차로 부르는 것이 곧 `async for` 루프의 동작이다
        await consumer._process(_message({'tradeDate': _today(), 'triggerType': 'SCHEDULED'}))
        await consumer._process(_message({'tradeDate': _today(), 'triggerType': 'MANUAL'}, offset=1))

        assert concurrent == [1, 1]  # 동시에 2가 된 적이 없다
        assert consumer.processed_count == 2


class TestPipelineRunFreshnessGuard:
    """오래된 트리거 재생 방어.

    ai-agent 가 하루 이상 내려가 있다 재기동하면 밀린 트리거가 순차 재생된다.
    `is_market_open(trade_date=...)` 은 과거 평일을 정상 통과시키고 멱등키에도 그
    과거 날짜가 그대로 들어가 api-server 의 중복 방어에도 걸리지 않으므로,
    막지 않으면 과거 날짜로 실주문이 나간다.
    """

    @pytest.fixture
    def orchestrator(self):
        orch = MagicMock(name='PipelineOrchestrator')
        orch.run_complete_pipeline = AsyncMock(return_value={'success': True})
        return orch

    @pytest.fixture
    def consumer(self, orchestrator):
        c = PipelineRunConsumer(orchestrator=orchestrator, bootstrap_servers='stub:9092')
        c._consumer = MagicMock()
        c._consumer.commit = AsyncMock()
        return c

    async def test_yesterdays_trade_date_is_not_executed(self, consumer, orchestrator):
        yesterday = (date.today() - timedelta(days=1)).isoformat()

        await consumer.handle({
            'tradeDate': yesterday, 'triggerType': 'SCHEDULED', 'requestedAt': now_kst_iso(),
        })

        orchestrator.run_complete_pipeline.assert_not_awaited()
        assert consumer.last_run['skipped'] is True
        assert consumer.last_run['success'] is False
        assert yesterday in consumer.last_run['error']

    async def test_stale_request_for_today_is_not_executed(self, consumer, orchestrator):
        long_ago = (datetime.now().astimezone() - timedelta(hours=12)).isoformat(timespec='seconds')

        await consumer.handle({
            'tradeDate': _today(), 'triggerType': 'SCHEDULED', 'requestedAt': long_ago,
        })

        orchestrator.run_complete_pipeline.assert_not_awaited()
        assert consumer.last_run['skipped'] is True

    async def test_skipped_message_is_still_committed(self, consumer, orchestrator):
        """스킵해도 오프셋은 커밋해야 컨슈머 루프가 같은 자리에 갇히지 않는다."""
        yesterday = (date.today() - timedelta(days=1)).isoformat()

        await consumer._process(_message({'tradeDate': yesterday, 'triggerType': 'SCHEDULED'}))

        consumer._consumer.commit.assert_awaited_once()
        assert consumer.failed_count == 0  # 정상 처리(스킵)이지 실패가 아니다

    async def test_skip_is_logged_loudly(self, consumer, caplog):
        yesterday = (date.today() - timedelta(days=1)).isoformat()

        with caplog.at_level('ERROR', logger='messaging.pipeline_run_consumer'):
            await consumer.handle({'tradeDate': yesterday, 'triggerType': 'SCHEDULED'})

        assert any('stale' in r.message.lower() for r in caplog.records)

    async def test_fresh_request_runs(self, consumer, orchestrator):
        await consumer.handle({
            'tradeDate': _today(), 'triggerType': 'SCHEDULED', 'requestedAt': now_kst_iso(),
        })

        orchestrator.run_complete_pipeline.assert_awaited_once()

    async def test_missing_requested_at_still_runs_when_trade_date_is_today(
        self, consumer, orchestrator
    ):
        """requestedAt 은 계약상 선택 필드 — 없다고 실행을 막지는 않는다."""
        await consumer.handle({'tradeDate': _today(), 'triggerType': 'MANUAL'})

        orchestrator.run_complete_pipeline.assert_awaited_once()

    async def test_unparseable_requested_at_still_runs(self, consumer, orchestrator):
        await consumer.handle({
            'tradeDate': _today(), 'triggerType': 'MANUAL', 'requestedAt': 'not-a-timestamp',
        })

        orchestrator.run_complete_pipeline.assert_awaited_once()


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
