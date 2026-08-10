"""
pytest tests for Stage 6: TradeExecutor (execution/trade_executor.py)

실제 Kafka 로 나가지 않는다. `KafkaMessagePublisher` 를 스텁으로 대체해
TradeExecutor 가 발행하는 인자(멱등키 구성요소, 시장가, 순서)와 조립하는 결과 구조만
검증한다. 브로커를 실제로 태우는 검증은 tests/test_kafka_integration.py 에 있다.
"""
from datetime import date, datetime
from unittest.mock import AsyncMock, Mock

import pytest

from execution.trade_executor import TradeExecutor
from messaging import KafkaMessagePublisher, build_idempotency_key, build_trade_order_message

TRADE_DATE = date(2026, 8, 9)


def _publish_ok(**kwargs):
    """발행 성공 — publish_trade_order 의 (ok, key, value) 반환 형태를 그대로 흉내낸다."""
    key, value = build_trade_order_message(**kwargs)
    return True, key, value


def _publish_fail(**kwargs):
    key, value = build_trade_order_message(**kwargs)
    return False, key, value


@pytest.fixture
def publisher():
    """브로커로 나가지 않는 KafkaMessagePublisher 스텁."""
    stub = Mock(spec=KafkaMessagePublisher)
    stub.publish_trade_order = AsyncMock(side_effect=_publish_ok)
    return stub


@pytest.fixture
def executor(publisher):
    return TradeExecutor(publisher=publisher)


class TestTradeExecutorBuy:
    """매수 주문 발행 경로."""

    async def test_buy_order_is_published_with_market_price(self, executor, publisher):
        """매수는 price=0(시장가)으로 trade.order.requested 에 발행된다."""
        result = await executor.execute_for_user(
            user_id=7,
            buy_orders=[{
                'stock_code': '005930',
                'stock_name': '삼성전자',
                'quantity': 10,
                'reason': '외국인 순매수',
            }],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        publisher.publish_trade_order.assert_awaited_once_with(
            user_id=7,
            stock_code='005930',
            side='BUY',
            quantity=10,
            trade_date=TRADE_DATE,
            price=0,
        )

        assert result['buy_results'] == [{
            'stock_code': '005930',
            'quantity': 10,
            'reason': '외국인 순매수',
            'result': {
                'success': True,
                'status': 'QUEUED',
                'idempotency_key': '7:005930:2026-08-09:BUY',
            },
        }]
        assert result['sell_results'] == []

    async def test_idempotency_key_encodes_user_stock_date_side(self, executor, publisher):
        """멱등키는 {userId}:{stockCode}:{tradeDate}:{side} 형식이다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '000660', 'quantity': 3}],
            sell_orders=[],
            trade_date=date(2026, 1, 2),
        )

        assert result['buy_results'][0]['result']['idempotency_key'] == '1:000660:2026-01-02:BUY'

    async def test_reason_defaults_to_empty_string(self, executor):
        """reason 이 없으면 빈 문자열로 채워진다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '000660', 'quantity': 3}],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        assert result['buy_results'][0]['reason'] == ''

    async def test_string_quantity_is_coerced_to_int(self, executor, publisher):
        """문자열 수량도 int 로 변환되어 발행·기록된다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': '12'}],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        assert publisher.publish_trade_order.await_args.kwargs['quantity'] == 12
        assert result['buy_results'][0]['quantity'] == 12

    @pytest.mark.parametrize('quantity', [0, -1, '0'])
    async def test_non_positive_buy_quantity_is_skipped(self, executor, publisher, quantity):
        """수량이 0 이하인 매수는 발행하지 않고 결과에도 남기지 않는다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': quantity}],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        publisher.publish_trade_order.assert_not_awaited()
        assert result['buy_results'] == []

    async def test_missing_quantity_key_is_skipped(self, executor, publisher):
        """quantity 키 자체가 없으면 0 으로 간주되어 건너뛴다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930'}],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        publisher.publish_trade_order.assert_not_awaited()
        assert result['buy_results'] == []

    async def test_multiple_buy_orders_preserve_input_order(self, executor, publisher):
        """여러 매수 주문은 입력 순서대로 순차 발행된다."""
        await executor.execute_for_user(
            user_id=2,
            buy_orders=[
                {'stock_code': '005930', 'quantity': 1},
                {'stock_code': '000660', 'quantity': 2},
                {'stock_code': '051910', 'quantity': 3},
            ],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        codes = [c.kwargs['stock_code'] for c in publisher.publish_trade_order.await_args_list]
        assert codes == ['005930', '000660', '051910']

    async def test_trade_date_defaults_to_today(self, executor, publisher):
        """trade_date 를 안 주면 오늘 날짜로 멱등키가 만들어진다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': 1}],
            sell_orders=[],
        )

        today = date.today()
        assert publisher.publish_trade_order.await_args.kwargs['trade_date'] == today
        assert result['trade_date'] == today.isoformat()
        assert result['buy_results'][0]['result']['idempotency_key'].endswith(
            f':{today.isoformat()}:BUY'
        )


class TestTradeExecutorSell:
    """매도 주문 발행 경로."""

    async def test_sell_order_is_published(self, executor, publisher):
        result = await executor.execute_for_user(
            user_id=9,
            buy_orders=[],
            sell_orders=[{
                'stock_code': '035420',
                'stock_name': 'NAVER',
                'quantity': 5,
                'reason': '손절',
            }],
            trade_date=TRADE_DATE,
        )

        publisher.publish_trade_order.assert_awaited_once_with(
            user_id=9,
            stock_code='035420',
            side='SELL',
            quantity=5,
            trade_date=TRADE_DATE,
            price=0,
        )
        assert result['sell_results'][0]['reason'] == '손절'
        assert result['sell_results'][0]['result']['idempotency_key'] == '9:035420:2026-08-09:SELL'

    async def test_non_positive_sell_quantity_is_skipped_with_warning(self, executor, publisher, caplog):
        """수량이 0 이하인 매도는 건너뛰되 경고 로그를 남긴다 (매수와 달리 로깅됨)."""
        with caplog.at_level('WARNING', logger='execution.trade_executor'):
            result = await executor.execute_for_user(
                user_id=3,
                buy_orders=[],
                sell_orders=[{'stock_code': '035420', 'quantity': 0}],
                trade_date=TRADE_DATE,
            )

        publisher.publish_trade_order.assert_not_awaited()
        assert result['sell_results'] == []
        assert any('qty<=0' in record.message for record in caplog.records)


class TestTradeExecutorResultShape:
    """반환 구조 및 혼합 시나리오."""

    async def test_buys_publish_before_sells(self, executor, publisher):
        """매수 루프가 매도 루프보다 먼저 완료된다."""
        order_log = []

        async def _record(**kwargs):
            order_log.append(kwargs['side'])
            return _publish_ok(**kwargs)

        publisher.publish_trade_order = AsyncMock(side_effect=_record)

        await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': 1}],
            sell_orders=[{'stock_code': '000660', 'quantity': 1}],
            trade_date=TRADE_DATE,
        )

        assert order_log == ['BUY', 'SELL']

    async def test_result_contains_user_id_and_iso_timestamp(self, executor):
        result = await executor.execute_for_user(
            user_id=42, buy_orders=[], sell_orders=[], trade_date=TRADE_DATE
        )

        assert result['user_id'] == 42
        assert set(result) == {'user_id', 'trade_date', 'buy_results', 'sell_results', 'executed_at'}
        # ISO 8601 로 파싱 가능해야 한다
        assert isinstance(datetime.fromisoformat(result['executed_at']), datetime)

    async def test_empty_orders_publish_nothing(self, executor, publisher):
        result = await executor.execute_for_user(
            user_id=1, buy_orders=[], sell_orders=[], trade_date=TRADE_DATE
        )

        publisher.publish_trade_order.assert_not_awaited()
        assert result['buy_results'] == []
        assert result['sell_results'] == []

    async def test_publish_failure_is_recorded_as_failed(self, executor, publisher):
        """발행 실패는 예외로 번지지 않고 status=FAILED 로 기록된다."""
        publisher.publish_trade_order = AsyncMock(side_effect=_publish_fail)

        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': 1}],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        assert result['buy_results'][0]['result'] == {
            'success': False,
            'status': 'FAILED',
            'idempotency_key': '1:005930:2026-08-09:BUY',
        }

    async def test_partial_failure_does_not_stop_remaining_orders(self, executor, publisher):
        """앞선 주문 발행이 실패해도 뒤따르는 주문은 계속 발행된다."""
        publisher.publish_trade_order = AsyncMock(side_effect=[
            (False, '1:005930:2026-08-09:BUY', {}),
            (True, '1:000660:2026-08-09:BUY', {}),
        ])

        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[
                {'stock_code': '005930', 'quantity': 1},
                {'stock_code': '000660', 'quantity': 2},
            ],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        assert publisher.publish_trade_order.await_count == 2
        assert result['buy_results'][0]['result']['success'] is False
        assert result['buy_results'][1]['result']['success'] is True

    async def test_queued_is_not_executed(self, executor):
        """발행 성공은 '체결'이 아니라 'QUEUED' 다 — 이 구분이 Stage 6 의 핵심."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': 1}],
            sell_orders=[],
            trade_date=TRADE_DATE,
        )

        assert result['buy_results'][0]['result']['status'] == 'QUEUED'


class TestTradeExecutorErrorPropagation:
    """현재 구현의 에러 처리 경계 (문서화 목적)."""

    async def test_exception_from_publisher_propagates(self, executor, publisher):
        """프로듀서가 예외를 던지면 TradeExecutor 는 삼키지 않고 그대로 올린다.

        KafkaMessagePublisher.publish 는 자체적으로 예외를 False 로 degrade 하므로
        정상 조합에서는 발생하지 않지만, 다른 구현이 주입되면 파이프라인까지 전파된다.
        """
        publisher.publish_trade_order = AsyncMock(side_effect=TimeoutError('broker timeout'))

        with pytest.raises(TimeoutError):
            await executor.execute_for_user(
                user_id=1,
                buy_orders=[
                    {'stock_code': '005930', 'quantity': 1},
                    {'stock_code': '000660', 'quantity': 1},
                ],
                sell_orders=[],
                trade_date=TRADE_DATE,
            )

        # 첫 주문에서 중단되어 두 번째 주문은 시도조차 되지 않는다
        assert publisher.publish_trade_order.await_count == 1

    async def test_missing_stock_code_raises_key_error(self, executor):
        """stock_code 없는 주문은 KeyError 로 즉시 실패한다 (검증 계층 없음)."""
        with pytest.raises(KeyError):
            await executor.execute_for_user(
                user_id=1,
                buy_orders=[{'stock_name': '삼성전자', 'quantity': 1}],
                sell_orders=[],
                trade_date=TRADE_DATE,
            )

    async def test_non_numeric_quantity_raises_value_error(self, executor):
        """숫자로 변환 불가한 수량은 int() 에서 ValueError 를 낸다."""
        with pytest.raises(ValueError):
            await executor.execute_for_user(
                user_id=1,
                buy_orders=[{'stock_code': '005930', 'quantity': 'ten'}],
                sell_orders=[],
                trade_date=TRADE_DATE,
            )


class TestIdempotencyKeyHelper:
    """멱등키 조립 규칙 (api-server 와 공유하는 계약)."""

    def test_key_format(self):
        assert build_idempotency_key(1, '005930', date(2026, 8, 9), 'BUY') == '1:005930:2026-08-09:BUY'

    def test_side_is_uppercased(self):
        assert build_idempotency_key(1, '005930', date(2026, 8, 9), 'buy').endswith(':BUY')

    def test_same_inputs_produce_same_key(self):
        """같은 (유저, 종목, 거래일, 방향)이면 몇 번을 만들어도 같은 키 — 중복 주문 방지의 근거."""
        first = build_idempotency_key(1, '005930', date(2026, 8, 9), 'BUY')
        second = build_idempotency_key(1, '005930', date(2026, 8, 9), 'BUY')
        assert first == second

    def test_different_side_produces_different_key(self):
        buy = build_idempotency_key(1, '005930', date(2026, 8, 9), 'BUY')
        sell = build_idempotency_key(1, '005930', date(2026, 8, 9), 'SELL')
        assert buy != sell


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
