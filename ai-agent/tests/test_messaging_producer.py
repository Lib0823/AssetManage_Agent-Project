"""KafkaMessagePublisher 단위 테스트 (AIOKafkaProducer 를 스텁으로 대체).

브로커 없이 프로듀서 래퍼의 계약을 검증한다:
  - 기동/종료/재기동 멱등성, flush 후 close
  - 발행 실패를 예외 대신 False 로 degrade (한 주문 실패가 파이프라인을 막지 않음)
  - 이벤트 루프 밖(APScheduler 스레드)에서의 sync 발행 경로
"""
import asyncio
import json
import threading
from datetime import date
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from messaging import KafkaMessagePublisher
from messaging.producer import _serialize_key, _serialize_value
from messaging.topics import TOPIC_PIPELINE_RUN_REQUESTED, TOPIC_TRADE_ORDER_REQUESTED


@pytest.fixture
def fake_producer():
    """AIOKafkaProducer 스텁 (start/stop/flush/send_and_wait)."""
    producer = MagicMock(name='AIOKafkaProducer')
    producer.start = AsyncMock()
    producer.stop = AsyncMock()
    producer.flush = AsyncMock()
    producer.send_and_wait = AsyncMock()
    return producer


@pytest.fixture
def publisher(fake_producer):
    with patch('messaging.producer.AIOKafkaProducer', return_value=fake_producer):
        yield KafkaMessagePublisher(bootstrap_servers='stub:9092')


class TestSerializers:
    def test_value_is_utf8_json(self):
        raw = _serialize_value({'stockName': '삼성전자'})

        assert json.loads(raw.decode('utf-8')) == {'stockName': '삼성전자'}
        assert b'\\u' not in raw  # ensure_ascii=False → 한글이 그대로 실린다

    def test_key_is_utf8(self):
        assert _serialize_key('1:005930:2026-08-09:BUY') == b'1:005930:2026-08-09:BUY'

    def test_none_key_stays_none(self):
        assert _serialize_key(None) is None


class TestLifecycle:
    async def test_start_marks_running(self, publisher, fake_producer):
        await publisher.start()

        fake_producer.start.assert_awaited_once()
        assert publisher.running is True

    async def test_start_is_idempotent(self, publisher, fake_producer):
        await publisher.start()
        await publisher.start()

        fake_producer.start.assert_awaited_once()

    async def test_ensure_started_starts_once_under_concurrency(self, publisher, fake_producer):
        """동시에 여러 발행이 몰려도 프로듀서는 한 번만 기동된다."""
        await asyncio.gather(*[publisher.ensure_started() for _ in range(5)])

        fake_producer.start.assert_awaited_once()

    async def test_stop_flushes_before_close(self, publisher, fake_producer):
        call_order = []
        fake_producer.flush = AsyncMock(side_effect=lambda: call_order.append('flush'))
        fake_producer.stop = AsyncMock(side_effect=lambda: call_order.append('stop'))

        await publisher.start()
        await publisher.stop()

        assert call_order == ['flush', 'stop']
        assert publisher.running is False

    async def test_stop_closes_even_if_flush_fails(self, publisher, fake_producer):
        fake_producer.flush = AsyncMock(side_effect=RuntimeError('flush boom'))

        await publisher.start()
        await publisher.stop()  # 예외가 새어나오면 안 된다

        fake_producer.stop.assert_awaited_once()

    async def test_stop_without_start_is_noop(self, publisher, fake_producer):
        await publisher.stop()

        fake_producer.stop.assert_not_awaited()


class TestPublish:
    async def test_publish_lazily_starts_producer(self, publisher, fake_producer):
        """단독 실행 경로: 명시적 start 없이 첫 발행에서 기동된다."""
        ok = await publisher.publish('t', 'k', {'a': 1})

        assert ok is True
        fake_producer.start.assert_awaited_once()
        fake_producer.send_and_wait.assert_awaited_once_with('t', key='k', value={'a': 1})

    async def test_publish_failure_returns_false(self, publisher, fake_producer):
        fake_producer.send_and_wait = AsyncMock(side_effect=RuntimeError('broker down'))

        assert await publisher.publish('t', 'k', {'a': 1}) is False

    async def test_publish_trade_order_uses_contract_topic_and_key(self, publisher, fake_producer):
        ok, key, value = await publisher.publish_trade_order(
            user_id=1, stock_code='005930', side='BUY', quantity=10,
            trade_date=date(2026, 8, 9),
        )

        assert ok is True
        assert key == '1:005930:2026-08-09:BUY'
        topic, kwargs = fake_producer.send_and_wait.await_args.args[0], fake_producer.send_and_wait.await_args.kwargs
        assert topic == TOPIC_TRADE_ORDER_REQUESTED
        assert kwargs['key'] == key
        assert kwargs['value']['idempotencyKey'] == key
        assert value['price'] == 0  # 시장가

    async def test_publish_trade_order_failure_still_returns_key(self, publisher, fake_producer):
        """발행이 실패해도 멱등키는 돌려줘야 계획 레코드에 남길 수 있다."""
        fake_producer.send_and_wait = AsyncMock(side_effect=RuntimeError('broker down'))

        ok, key, _ = await publisher.publish_trade_order(
            user_id=1, stock_code='005930', side='BUY', quantity=10,
            trade_date=date(2026, 8, 9),
        )

        assert ok is False
        assert key == '1:005930:2026-08-09:BUY'

    async def test_publish_pipeline_run(self, publisher, fake_producer):
        ok, value = await publisher.publish_pipeline_run(date(2026, 8, 9), 'MANUAL')

        assert ok is True
        assert fake_producer.send_and_wait.await_args.args[0] == TOPIC_PIPELINE_RUN_REQUESTED
        assert fake_producer.send_and_wait.await_args.kwargs['key'] == '2026-08-09'
        assert value['triggerType'] == 'MANUAL'


class TestPublishSync:
    """APScheduler 워커 스레드에서 부르는 경로."""

    async def test_reuses_running_loop_producer(self, publisher, fake_producer):
        """이미 기동된 프로듀서가 있으면 그 이벤트 루프에 코루틴을 밀어 넣어 재사용한다."""
        await publisher.start()

        results = {}

        def worker():
            results['ok'] = publisher.publish_sync('t', 'k', {'a': 1})

        thread = threading.Thread(target=worker)
        thread.start()
        while thread.is_alive():          # 워커가 밀어 넣은 코루틴을 이 루프가 돌려준다
            await asyncio.sleep(0.01)
        thread.join()

        assert results['ok'] is True
        fake_producer.send_and_wait.assert_awaited_once()
        fake_producer.start.assert_awaited_once()  # 일회용 프로듀서를 새로 만들지 않았다

    def test_falls_back_to_standalone_producer(self, publisher, fake_producer):
        """루프가 없으면(순수 sync 컨텍스트) 일회용 프로듀서로 발행하고 닫는다."""
        ok = publisher.publish_sync('t', 'k', {'a': 1})

        assert ok is True
        fake_producer.start.assert_awaited_once()
        fake_producer.send_and_wait.assert_awaited_once()
        fake_producer.stop.assert_awaited_once()

    def test_standalone_failure_returns_false(self, publisher, fake_producer):
        fake_producer.send_and_wait = AsyncMock(side_effect=RuntimeError('broker down'))

        assert publisher.publish_sync('t', 'k', {'a': 1}) is False
        fake_producer.stop.assert_awaited_once()  # 실패해도 정리한다

    def test_publish_pipeline_run_sync_builds_contract_message(self, publisher, fake_producer):
        ok, value = publisher.publish_pipeline_run_sync(date(2026, 8, 9), 'SCHEDULED')

        assert ok is True
        assert value['tradeDate'] == '2026-08-09'
        assert value['triggerType'] == 'SCHEDULED'
        assert fake_producer.send_and_wait.await_args.args[0] == TOPIC_PIPELINE_RUN_REQUESTED


class TestProducerConfig:
    """주문 유실 방지를 위한 프로듀서 설정 (회귀 방지)."""

    def test_acks_all_and_idempotence(self):
        with patch('messaging.producer.AIOKafkaProducer') as cls:
            KafkaMessagePublisher(bootstrap_servers='stub:9092')._new_producer()

        kwargs = cls.call_args.kwargs
        assert kwargs['acks'] == 'all'
        assert kwargs['enable_idempotence'] is True
        assert kwargs['bootstrap_servers'] == 'stub:9092'


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
