"""실제 Kafka 브로커를 띄워 프로덕션 코드 경로를 검증하는 통합 테스트.

testcontainers 로 Kafka 컨테이너를 1개 띄우고(모듈 단위 재사용), 다음을 실측한다.

  (a) POST /api/pipeline/trigger 가 파이프라인 완료를 기다리지 않고 즉시 202 를 준다
  (b) 스케줄 트리거 + 수동 트리거가 겹쳐도 run_complete_pipeline() 이 직렬 실행된다
  (c) trade.order.requested 가 계약 스키마 그대로 발행된다
  (d) trade.order.result 수신 시 trade_execution_plan 상태가 갱신된다 (실 Postgres)
  (e) 파이프라인이 중복 실행돼도 멱등키 덕분에 실제 주문은 1회만 나갈 수 있다

무엇을 대역으로 바꿨나:
  - `PipelineOrchestrator.run_complete_pipeline` 만 가짜(sleep)로 대체한다. KIS/DART/
    Gemini 네트워크에 의존하지 않으면서, 트리거 → 브로커 → 컨슈머 → 실행 경로는
    전부 실제 코드다.
  - Kafka / PostgreSQL 은 실제 인스턴스를 쓴다.

Docker 나 testcontainers 가 없으면 통째로 skip 된다.
"""
import asyncio
import json
import threading
import time
import uuid
from datetime import date, timedelta

import pytest

pytestmark = pytest.mark.integration


# ---------------------------------------------------------------------------
# 인프라 픽스처
# ---------------------------------------------------------------------------

@pytest.fixture(scope='module')
def kafka_bootstrap():
    """Kafka 컨테이너 1개를 모듈 전체에서 공유한다."""
    try:
        from testcontainers.kafka import KafkaContainer
    except ImportError:  # pragma: no cover
        pytest.skip('testcontainers[kafka] 미설치 — Kafka 통합 테스트 skip')

    try:
        container = KafkaContainer()
        container.start()
    except Exception as e:  # pragma: no cover - Docker 미가동 등
        pytest.skip(f'Kafka 컨테이너 기동 실패 (Docker 미가동?): {e}')

    try:
        yield container.get_bootstrap_server()
    finally:
        container.stop()


@pytest.fixture
def patched_settings(kafka_bootstrap, monkeypatch):
    """모든 프로듀서/컨슈머가 테스트 브로커를 바라보게 한다."""
    from config.settings import get_settings

    monkeypatch.setattr(get_settings(), 'kafka_bootstrap_servers', kafka_bootstrap, raising=False)
    return kafka_bootstrap


# ---------------------------------------------------------------------------
# 유틸
# ---------------------------------------------------------------------------

async def _drain(bootstrap, topic, expected: int, timeout: float = 30.0):
    """토픽을 처음부터 읽어 메시지 `expected` 건을 모아 반환한다."""
    from aiokafka import AIOKafkaConsumer

    consumer = AIOKafkaConsumer(
        topic,
        bootstrap_servers=bootstrap,
        group_id=f'test-drain-{uuid.uuid4()}',
        auto_offset_reset='earliest',
        enable_auto_commit=False,
    )
    await consumer.start()
    collected = []
    try:
        deadline = time.monotonic() + timeout
        while len(collected) < expected and time.monotonic() < deadline:
            batches = await consumer.getmany(timeout_ms=1000)
            for records in batches.values():
                for record in records:
                    collected.append((
                        record.key.decode('utf-8') if record.key else None,
                        json.loads(record.value.decode('utf-8')),
                    ))
    finally:
        await consumer.stop()
    return collected


# ===========================================================================
# (c) trade.order.requested 발행 — 계약 스키마 그대로 실리는지
# ===========================================================================

class TestTradeOrderPublishing:

    async def test_stage6_orders_land_on_the_topic_with_contract_schema(self, patched_settings):
        """TradeExecutor(프로덕션 코드) → 실제 브로커 → 원시 컨슈머로 수신."""
        from execution.trade_executor import TradeExecutor
        from messaging import KafkaMessagePublisher, TOPIC_TRADE_ORDER_REQUESTED

        publisher = KafkaMessagePublisher(bootstrap_servers=patched_settings)
        await publisher.start()
        try:
            result = await TradeExecutor(publisher=publisher).execute_for_user(
                user_id=1,
                buy_orders=[{'stock_code': '005930', 'stock_name': '삼성전자',
                             'quantity': 10, 'price': 70000, 'reason': '외국인 순매수'}],
                sell_orders=[{'stock_code': '000660', 'stock_name': 'SK하이닉스',
                              'quantity': 3, 'reason': '손절'}],
                trade_date=date(2026, 8, 9),
            )
        finally:
            await publisher.stop()

        assert result['buy_results'][0]['result']['status'] == 'QUEUED'
        assert result['sell_results'][0]['result']['status'] == 'QUEUED'

        messages = await _drain(patched_settings, TOPIC_TRADE_ORDER_REQUESTED, expected=2)
        assert len(messages) == 2

        by_side = {value['side']: (key, value) for key, value in messages}

        buy_key, buy = by_side['BUY']
        assert buy_key == '1:005930:2026-08-09:BUY'
        assert set(buy) == {'idempotencyKey', 'userId', 'stockCode', 'side',
                            'quantity', 'price', 'tradeDate', 'requestedAt'}
        assert buy['idempotencyKey'] == '1:005930:2026-08-09:BUY'
        assert buy['userId'] == 1
        assert buy['stockCode'] == '005930'
        assert buy['quantity'] == 10
        assert buy['price'] == 0            # 시장가
        assert buy['tradeDate'] == '2026-08-09'
        assert buy['requestedAt'].endswith('+09:00')

        sell_key, sell = by_side['SELL']
        assert sell_key == '1:000660:2026-08-09:SELL'
        assert sell['quantity'] == 3

    async def test_message_key_equals_idempotency_key_on_the_wire(self, patched_settings):
        """파티션 배정 근거 — 실제로 브로커에 실린 key 가 멱등키와 같은지."""
        from messaging import KafkaMessagePublisher, TOPIC_TRADE_ORDER_REQUESTED

        publisher = KafkaMessagePublisher(bootstrap_servers=patched_settings)
        try:
            ok, key, _ = await publisher.publish_trade_order(
                user_id=42, stock_code='035420', side='SELL', quantity=1,
                trade_date=date(2026, 8, 9),
            )
        finally:
            await publisher.stop()

        assert ok is True
        messages = await _drain(patched_settings, TOPIC_TRADE_ORDER_REQUESTED, expected=1)
        assert any(k == v['idempotencyKey'] == key for k, v in messages)


# ===========================================================================
# (d) trade.order.result 수신 → trade_execution_plan 갱신 (실 PostgreSQL)
# ===========================================================================

class TestTradeResultConsumption:

    TEST_USER_ID = 990001            # 실 사용자와 겹치지 않는 식별자
    TEST_DATE = date(2030, 1, 15)    # unique_execution_plan_key 충돌 회피용 미래 날짜
    TEST_CODE = '900001'

    @pytest.fixture
    def db_repo(self):
        """실제 PostgreSQL 리포지토리. 접속 불가면 skip."""
        from sqlalchemy import text

        from database.repository import DatabaseRepository

        repo = DatabaseRepository()
        try:
            session = repo.session_factory()
            session.execute(text('SELECT 1'))
            session.close()
        except Exception as e:  # pragma: no cover
            pytest.skip(f'PostgreSQL 접속 불가 — DB 통합 검증 skip: {e}')

        self._cleanup(repo)
        yield repo
        self._cleanup(repo)

    def _cleanup(self, repo):
        from sqlalchemy import text

        session = repo.session_factory()
        try:
            session.execute(
                text('DELETE FROM trade_execution_plan WHERE user_id = :u'),
                {'u': self.TEST_USER_ID},
            )
            session.commit()
        finally:
            session.close()

    def _row(self, repo):
        from sqlalchemy import text

        session = repo.session_factory()
        try:
            return session.execute(text("""
                SELECT execution_status, order_no, execution_result
                  FROM trade_execution_plan
                 WHERE user_id = :u AND execution_date = :d
                   AND stock_code = :c AND trade_type = 'BUY'
            """), {'u': self.TEST_USER_ID, 'd': self.TEST_DATE, 'c': self.TEST_CODE}).mappings().fetchone()
        finally:
            session.close()

    def _seed_queued_row(self, repo):
        """Stage 6 가 남기는 QUEUED 행을 실제 저장 경로로 심는다."""
        repo.save_trade_execution_plan(self.TEST_USER_ID, self.TEST_DATE, [{
            'stock_code': self.TEST_CODE,
            'stock_name': '통합테스트종목',
            'trade_type': 'BUY',
            'planned_quantity': 10,
            'reference_price': 70000,
            'estimated_amount': 700000,
            'gemini_reason': 'integration test',
            'gemini_rank': 1,
            'safety_filter_passed': True,
            'execution_status': 'QUEUED',
            'order_no': None,
            'execution_result': {
                'success': True, 'status': 'QUEUED',
                'idempotency_key': f'{self.TEST_USER_ID}:{self.TEST_CODE}:{self.TEST_DATE}:BUY',
            },
        }])

    async def _run_consumer_until(self, consumer, predicate, timeout=30.0):
        """컨슈머를 백그라운드로 돌리며 조건이 만족될 때까지 대기."""
        task = asyncio.create_task(consumer.run())
        try:
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if predicate():
                    return True
                await asyncio.sleep(0.2)
            return False
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def test_success_result_marks_row_executed(self, patched_settings, db_repo):
        from messaging import KafkaMessagePublisher, TOPIC_TRADE_ORDER_RESULT, TradeResultConsumer

        self._seed_queued_row(db_repo)
        assert self._row(db_repo)['execution_status'] == 'QUEUED'

        key = f'{self.TEST_USER_ID}:{self.TEST_CODE}:{self.TEST_DATE}:BUY'
        publisher = KafkaMessagePublisher(bootstrap_servers=patched_settings)
        try:
            assert await publisher.publish(TOPIC_TRADE_ORDER_RESULT, key, {
                'idempotencyKey': key,
                'userId': self.TEST_USER_ID,
                'stockCode': self.TEST_CODE,
                'side': 'BUY',
                'status': 'SUCCESS',
                'kisOrderNo': '0000123',
                'errorMessage': None,
                'processedAt': '2030-01-15T08:55:03+09:00',
            }) is True
        finally:
            await publisher.stop()

        consumer = TradeResultConsumer(db_repo=db_repo, bootstrap_servers=patched_settings)
        updated = await self._run_consumer_until(
            consumer, lambda: self._row(db_repo)['execution_status'] != 'QUEUED'
        )

        assert updated, '결과 메시지를 받고도 상태가 갱신되지 않았다'
        row = self._row(db_repo)
        assert row['execution_status'] == 'EXECUTED'   # SUCCESS → EXECUTED (뷰 호환)
        assert row['order_no'] == '0000123'
        # 기존 JSONB(멱등키)는 유지되고 결과가 병합된다
        assert row['execution_result']['idempotency_key'] == key
        assert row['execution_result']['result_message']['status'] == 'SUCCESS'

    async def test_failed_result_marks_row_failed_with_error(self, patched_settings, db_repo):
        from messaging import KafkaMessagePublisher, TOPIC_TRADE_ORDER_RESULT, TradeResultConsumer

        self._seed_queued_row(db_repo)

        key = f'{self.TEST_USER_ID}:{self.TEST_CODE}:{self.TEST_DATE}:BUY'
        publisher = KafkaMessagePublisher(bootstrap_servers=patched_settings)
        try:
            await publisher.publish(TOPIC_TRADE_ORDER_RESULT, key, {
                'idempotencyKey': key,
                'status': 'FAILED',
                'kisOrderNo': None,
                'errorMessage': '주문가능금액 부족',
                'processedAt': '2030-01-15T08:55:03+09:00',
            })
        finally:
            await publisher.stop()

        consumer = TradeResultConsumer(db_repo=db_repo, bootstrap_servers=patched_settings)
        updated = await self._run_consumer_until(
            consumer, lambda: self._row(db_repo)['execution_status'] != 'QUEUED'
        )

        assert updated
        row = self._row(db_repo)
        assert row['execution_status'] == 'FAILED'
        assert row['execution_result']['error_message'] == '주문가능금액 부족'


# ===========================================================================
# (a)(b) 트리거 응답 시간 + 파이프라인 직렬 실행
# ===========================================================================

PIPELINE_SECONDS = 2.0  # 가짜 파이프라인 1회 소요 시간

# 컨슈머의 신선도 가드가 오늘이 아닌 tradeDate 를 스킵하므로, 실행까지 가는
# 시나리오는 반드시 오늘 날짜로 트리거해야 한다.
TODAY = date.today().isoformat()


class _FakeOrchestrator:
    """run_complete_pipeline 만 흉내내는 오케스트레이터 대역.

    실행 구간(start/end)을 기록해 두 실행이 겹쳤는지 사후에 판정한다.
    """

    runs = []            # [(trade_date, start_monotonic, end_monotonic)]
    active = 0
    max_active = 0

    def __init__(self, *args, **kwargs):
        pass

    async def run_complete_pipeline(self, trade_date=None, holdings=None, conn=None):
        type(self).active += 1
        type(self).max_active = max(type(self).max_active, type(self).active)
        started = time.monotonic()
        try:
            await asyncio.sleep(PIPELINE_SECONDS)
        finally:
            type(self).active -= 1
        type(self).runs.append((trade_date, started, time.monotonic()))
        return {'success': True, 'trade_date': str(trade_date), 'stages': {}}


@pytest.fixture
def app_client(patched_settings, monkeypatch):
    """실제 FastAPI 앱을 lifespan 과 함께 띄운다 (Kafka 프로듀서/컨슈머 전부 실동작).

    무거운 분석만 대역으로 바꾼다 — 트리거/브로커/컨슈머 경로는 프로덕션 코드 그대로.
    """
    from fastapi.testclient import TestClient

    import main

    _FakeOrchestrator.runs = []
    _FakeOrchestrator.active = 0
    _FakeOrchestrator.max_active = 0
    monkeypatch.setattr(main, 'PipelineOrchestrator', _FakeOrchestrator)

    with TestClient(main.app) as client:
        assert main.publisher is not None and main.publisher.running, 'Kafka 프로듀서 미기동'
        yield client


def _wait_for_runs(count, timeout=40.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if len(_FakeOrchestrator.runs) >= count:
            return True
        time.sleep(0.1)
    return False


class TestManualTriggerLatency:
    """(a) 트리거는 파이프라인 완료를 기다리지 않는다."""

    def test_trigger_returns_202_immediately(self, app_client, capsys):
        started = time.perf_counter()
        response = app_client.post('/api/pipeline/trigger', json={'trade_date': TODAY})
        elapsed = time.perf_counter() - started

        assert response.status_code == 202
        body = response.json()
        assert body['status'] == 'QUEUED'
        assert body['request']['tradeDate'] == TODAY
        assert body['request']['triggerType'] == 'MANUAL'

        # 파이프라인 1회는 PIPELINE_SECONDS 걸린다 — 그보다 훨씬 빨리 응답해야 한다
        assert elapsed < PIPELINE_SECONDS / 2, f'트리거 응답이 느리다: {elapsed:.3f}s'
        with capsys.disabled():
            print(f'\n[측정] 유휴 상태 트리거 응답 시간: {elapsed * 1000:.1f}ms')

        assert _wait_for_runs(1), '큐잉된 파이프라인이 실행되지 않았다'

    def test_trigger_is_fast_even_while_a_pipeline_is_running(self, app_client, capsys):
        """이미 실행 중이어도 트리거는 즉시 202 (대기하지 않는다)."""
        app_client.post('/api/pipeline/trigger', json={'trade_date': TODAY})
        time.sleep(0.5)  # 컨슈머가 첫 실행을 시작할 시간

        started = time.perf_counter()
        response = app_client.post('/api/pipeline/trigger', json={'trade_date': TODAY})
        elapsed = time.perf_counter() - started

        assert response.status_code == 202
        assert elapsed < PIPELINE_SECONDS / 2, f'실행 중 트리거 응답이 느리다: {elapsed:.3f}s'
        with capsys.disabled():
            print(f'[측정] 파이프라인 실행 중 트리거 응답 시간: {elapsed * 1000:.1f}ms')

        assert _wait_for_runs(2)

    def test_invalid_date_is_rejected_before_publishing(self, app_client):
        response = app_client.post('/api/pipeline/trigger', json={'trade_date': '2026/08/09'})

        assert response.status_code == 400
        assert _FakeOrchestrator.runs == []

    def test_holdings_are_reported_as_ignored(self, app_client):
        response = app_client.post(
            '/api/pipeline/trigger',
            json={'trade_date': TODAY, 'holdings': ['005930']},
        )

        assert response.status_code == 202
        assert response.json()['ignored'] == {'holdings': ['005930']}


class TestPipelineSerialization:
    """(b) 스케줄 트리거 + 수동 트리거가 겹쳐도 직렬 실행된다."""

    def test_scheduled_and_manual_triggers_do_not_overlap(self, app_client, capsys):
        import main
        from pipeline.scheduler import PipelineScheduler

        # 스케줄 경로: APScheduler 워커 스레드에서 _job_wrapper() 가 도는 상황을 그대로 재현
        scheduler = PipelineScheduler(publisher=main.publisher)
        thread = threading.Thread(target=scheduler._job_wrapper)
        thread.start()

        # 수동 경로: 거의 동시에 HTTP 트리거
        response = app_client.post('/api/pipeline/trigger', json={'trade_date': TODAY})
        thread.join(timeout=20)

        assert response.status_code == 202
        assert _wait_for_runs(2), f'2회 실행되지 않았다: {_FakeOrchestrator.runs}'

        runs = sorted(_FakeOrchestrator.runs, key=lambda r: r[1])
        (_, start1, end1), (_, start2, end2) = runs[0], runs[1]

        with capsys.disabled():
            print(f'\n[측정] run1 {end1 - start1:.2f}s, run2 {end2 - start2:.2f}s, '
                  f'run2 시작 - run1 종료 = {start2 - end1:+.3f}s, '
                  f'최대 동시 실행 수 = {_FakeOrchestrator.max_active}')

        # 두 번째 실행은 첫 번째가 끝난 뒤에 시작해야 한다
        assert start2 >= end1, f'실행 구간이 겹쳤다: run1=({start1:.3f},{end1:.3f}) run2=({start2:.3f},{end2:.3f})'
        assert _FakeOrchestrator.max_active == 1, '파이프라인이 동시에 2개 실행됐다'

    def test_burst_of_triggers_runs_one_at_a_time(self, app_client, capsys):
        """수동 트리거 3연발도 순차 처리된다."""
        for _ in range(3):
            assert app_client.post('/api/pipeline/trigger', json={'trade_date': TODAY}).status_code == 202

        assert _wait_for_runs(3), f'3회 실행되지 않았다: {len(_FakeOrchestrator.runs)}'

        runs = sorted(_FakeOrchestrator.runs, key=lambda r: r[1])
        for (_, _, prev_end), (_, next_start, _) in zip(runs, runs[1:]):
            assert next_start >= prev_end, '실행 구간이 겹쳤다'
        assert _FakeOrchestrator.max_active == 1

        with capsys.disabled():
            print(f'[측정] 3연속 트리거 최대 동시 실행 수 = {_FakeOrchestrator.max_active}')

    def test_status_endpoint_exposes_kafka_and_last_run(self, app_client):
        app_client.post('/api/pipeline/trigger', json={'trade_date': TODAY})
        assert _wait_for_runs(1)
        time.sleep(0.3)

        body = app_client.get('/api/pipeline/status').json()

        assert body['kafka_connected'] is True
        assert body['last_run']['trade_date'] == TODAY
        assert body['last_run']['trigger_type'] == 'MANUAL'
        assert body['last_run']['success'] is True
        assert body['consumers']['pipeline-run-consumer']['alive'] is True
        assert body['consumers']['trade-result-consumer']['alive'] is True


class TestStaleTriggerIsNotExecuted:
    """(f) 재생된 과거 트리거는 브로커를 거쳐 들어와도 실행되지 않는다.

    ai-agent 가 하루 이상 내려가 있다 재기동하면 밀린 트리거가 순차 재생되는데,
    그대로 실행하면 과거 날짜 멱등키로 실주문이 나간다.
    """

    def test_yesterdays_trigger_is_consumed_but_not_run(self, app_client):
        yesterday = (date.today() - timedelta(days=1)).isoformat()

        response = app_client.post('/api/pipeline/trigger', json={'trade_date': yesterday})
        assert response.status_code == 202  # 발행 자체는 정상

        assert not _wait_for_runs(1, timeout=6.0), '과거 날짜 트리거가 실행됐다'

        body = app_client.get('/api/pipeline/status').json()
        assert body['last_run']['skipped'] is True
        assert body['last_run']['trade_date'] == yesterday
        # 컨슈머는 계속 살아 있어야 한다 (스킵은 오프셋을 커밋하고 넘어간다)
        assert body['consumers']['pipeline-run-consumer']['alive'] is True

    def test_a_fresh_trigger_still_runs_after_a_stale_one(self, app_client):
        yesterday = (date.today() - timedelta(days=1)).isoformat()

        app_client.post('/api/pipeline/trigger', json={'trade_date': yesterday})
        app_client.post('/api/pipeline/trigger', json={'trade_date': TODAY})

        assert _wait_for_runs(1), '스킵 뒤에 온 정상 트리거가 실행되지 않았다'
        assert len(_FakeOrchestrator.runs) == 1


# ===========================================================================
# (e) 이슈 2 의 잔여 리스크(중복 실행 → 중복 주문)를 이슈 1 의 멱등키가 막아주는가
# ===========================================================================

class TestDuplicateRunIdempotency:

    async def test_duplicate_pipeline_runs_emit_identical_keys(self, patched_settings):
        """같은 거래일로 Stage 6 를 두 번 돌리면 주문 메시지는 2건 나가지만
        멱등키가 완전히 같다 — 즉 중복 제거의 근거가 브로커 위에 실제로 존재한다."""
        from execution.trade_executor import TradeExecutor
        from messaging import KafkaMessagePublisher

        publisher = KafkaMessagePublisher(bootstrap_servers=patched_settings)
        await publisher.start()
        try:
            executor = TradeExecutor(publisher=publisher)
            orders = [{'stock_code': '005930', 'stock_name': '삼성전자',
                       'quantity': 10, 'reason': 'r'}]
            # 파이프라인 2회 실행 = Stage 6 두 번
            first = await executor.execute_for_user(1, orders, [], trade_date=date(2026, 8, 9))
            second = await executor.execute_for_user(1, orders, [], trade_date=date(2026, 8, 9))
        finally:
            await publisher.stop()

        key1 = first['buy_results'][0]['result']['idempotency_key']
        key2 = second['buy_results'][0]['result']['idempotency_key']
        assert key1 == key2 == '1:005930:2026-08-09:BUY'

    async def test_consumer_side_dedup_executes_once(self, patched_settings):
        """api-server 역할의 mock 컨슈머가 멱등키로 중복을 걸러내면 실제 주문은 1회.

        ai-agent 는 중복을 스스로 막지 않는다(같은 키를 2번 발행한다). 잔여 리스크를
        실제로 흡수하는 주체가 api-server 라는 점을 실측으로 남긴다.
        """
        from aiokafka import AIOKafkaConsumer

        from execution.trade_executor import TradeExecutor
        from messaging import KafkaMessagePublisher, TOPIC_TRADE_ORDER_REQUESTED

        stock_code = f'9{uuid.uuid4().int % 99999:05d}'  # 이 테스트만의 종목코드
        publisher = KafkaMessagePublisher(bootstrap_servers=patched_settings)
        await publisher.start()
        try:
            executor = TradeExecutor(publisher=publisher)
            orders = [{'stock_code': stock_code, 'quantity': 10, 'reason': 'r'}]
            await executor.execute_for_user(1, orders, [], trade_date=date(2026, 8, 9))
            await executor.execute_for_user(1, orders, [], trade_date=date(2026, 8, 9))
        finally:
            await publisher.stop()

        # api-server 대역: 멱등키 기반 dedup
        consumer = AIOKafkaConsumer(
            TOPIC_TRADE_ORDER_REQUESTED,
            bootstrap_servers=patched_settings,
            group_id=f'test-apiserver-{uuid.uuid4()}',
            auto_offset_reset='earliest',
            enable_auto_commit=False,
        )
        await consumer.start()
        received, executed, seen = 0, 0, set()
        try:
            deadline = time.monotonic() + 30
            while received < 2 and time.monotonic() < deadline:
                for records in (await consumer.getmany(timeout_ms=1000)).values():
                    for record in records:
                        payload = json.loads(record.value.decode('utf-8'))
                        if payload['stockCode'] != stock_code:
                            continue  # 다른 테스트가 남긴 메시지
                        received += 1
                        key = payload['idempotencyKey']
                        if key in seen:
                            continue  # 중복 → 주문 실행하지 않음
                        seen.add(key)
                        executed += 1
        finally:
            await consumer.stop()

        assert received == 2, 'ai-agent 는 중복 실행 시 같은 주문을 2번 발행한다 (설계상 정상)'
        assert executed == 1, '멱등키 dedup 이 중복 주문을 막지 못했다'


if __name__ == '__main__':
    pytest.main([__file__, '-v', '-s'])
