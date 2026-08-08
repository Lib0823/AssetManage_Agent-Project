"""
pytest tests for Stage 6: TradeExecutor (execution/trade_executor.py)

실제 HTTP 호출은 하지 않는다. `InternalApiClient` 를 AsyncMock 으로 대체해
TradeExecutor 가 내부 API 에 넘기는 인자와 조립하는 결과 구조만 검증한다.
"""
from datetime import datetime
from unittest.mock import AsyncMock, Mock

import pytest

from collectors.internal_api_client import InternalApiClient
from execution.trade_executor import TradeExecutor


def _ok(order_no: str = 'ORD-1') -> dict:
    """api-server 정상 응답 형태."""
    return {'success': True, 'data': {'orderNo': order_no}}


def _fail(message: str = 'HTTP 500') -> dict:
    """api-server 실패 응답 형태 (InternalApiClient 가 degrade 시켜 돌려주는 shape)."""
    return {'success': False, 'error': message}


@pytest.fixture
def internal_api():
    """네트워크로 나가지 않는 InternalApiClient 스텁."""
    client = Mock(spec=InternalApiClient)
    client.execute_buy = AsyncMock(return_value=_ok())
    client.execute_sell = AsyncMock(return_value=_ok())
    return client


@pytest.fixture
def executor(internal_api):
    return TradeExecutor(internal_api=internal_api)


class TestTradeExecutorBuy:
    """매수 주문 경로."""

    async def test_buy_order_calls_internal_api_with_market_price(self, executor, internal_api):
        """매수는 price=0(시장가)으로 내부 API 에 위임된다."""
        result = await executor.execute_for_user(
            user_id=7,
            buy_orders=[{
                'stock_code': '005930',
                'stock_name': '삼성전자',
                'quantity': 10,
                'reason': '외국인 순매수',
            }],
            sell_orders=[],
        )

        internal_api.execute_buy.assert_awaited_once_with(
            user_id=7,
            stock_code='005930',
            stock_name='삼성전자',
            quantity=10,
            price=0,
        )
        internal_api.execute_sell.assert_not_awaited()

        assert result['buy_results'] == [{
            'stock_code': '005930',
            'quantity': 10,
            'reason': '외국인 순매수',
            'result': _ok(),
        }]
        assert result['sell_results'] == []

    async def test_stock_name_defaults_to_stock_code(self, executor, internal_api):
        """stock_name 이 없으면 종목코드를 이름으로 사용한다."""
        await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '000660', 'quantity': 3}],
            sell_orders=[],
        )

        assert internal_api.execute_buy.await_args.kwargs['stock_name'] == '000660'

    async def test_reason_defaults_to_empty_string(self, executor):
        """reason 이 없으면 빈 문자열로 채워진다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '000660', 'quantity': 3}],
            sell_orders=[],
        )

        assert result['buy_results'][0]['reason'] == ''

    async def test_string_quantity_is_coerced_to_int(self, executor, internal_api):
        """문자열 수량도 int 로 변환되어 전달·기록된다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': '12'}],
            sell_orders=[],
        )

        assert internal_api.execute_buy.await_args.kwargs['quantity'] == 12
        assert result['buy_results'][0]['quantity'] == 12

    @pytest.mark.parametrize('quantity', [0, -1, '0'])
    async def test_non_positive_buy_quantity_is_skipped(self, executor, internal_api, quantity):
        """수량이 0 이하인 매수는 주문하지 않고 결과에도 남기지 않는다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': quantity}],
            sell_orders=[],
        )

        internal_api.execute_buy.assert_not_awaited()
        assert result['buy_results'] == []

    async def test_missing_quantity_key_is_skipped(self, executor, internal_api):
        """quantity 키 자체가 없으면 0 으로 간주되어 건너뛴다."""
        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930'}],
            sell_orders=[],
        )

        internal_api.execute_buy.assert_not_awaited()
        assert result['buy_results'] == []

    async def test_multiple_buy_orders_preserve_input_order(self, executor, internal_api):
        """여러 매수 주문은 입력 순서대로 순차 실행된다."""
        await executor.execute_for_user(
            user_id=2,
            buy_orders=[
                {'stock_code': '005930', 'quantity': 1},
                {'stock_code': '000660', 'quantity': 2},
                {'stock_code': '051910', 'quantity': 3},
            ],
            sell_orders=[],
        )

        codes = [c.kwargs['stock_code'] for c in internal_api.execute_buy.await_args_list]
        assert codes == ['005930', '000660', '051910']


class TestTradeExecutorSell:
    """매도 주문 경로."""

    async def test_sell_order_calls_internal_api(self, executor, internal_api):
        result = await executor.execute_for_user(
            user_id=9,
            buy_orders=[],
            sell_orders=[{
                'stock_code': '035420',
                'stock_name': 'NAVER',
                'quantity': 5,
                'reason': '손절',
            }],
        )

        internal_api.execute_sell.assert_awaited_once_with(
            user_id=9,
            stock_code='035420',
            stock_name='NAVER',
            quantity=5,
            price=0,
        )
        internal_api.execute_buy.assert_not_awaited()
        assert result['sell_results'][0]['reason'] == '손절'

    async def test_non_positive_sell_quantity_is_skipped_with_warning(self, executor, internal_api, caplog):
        """수량이 0 이하인 매도는 건너뛰되 경고 로그를 남긴다 (매수와 달리 로깅됨)."""
        with caplog.at_level('WARNING', logger='execution.trade_executor'):
            result = await executor.execute_for_user(
                user_id=3,
                buy_orders=[],
                sell_orders=[{'stock_code': '035420', 'quantity': 0}],
            )

        internal_api.execute_sell.assert_not_awaited()
        assert result['sell_results'] == []
        assert any('qty<=0' in record.message for record in caplog.records)


class TestTradeExecutorResultShape:
    """반환 구조 및 혼합 시나리오."""

    async def test_buys_execute_before_sells(self, executor, internal_api):
        """매수 루프가 매도 루프보다 먼저 완료된다."""
        order_log = []
        internal_api.execute_buy = AsyncMock(side_effect=lambda **kw: order_log.append('buy') or _ok())
        internal_api.execute_sell = AsyncMock(side_effect=lambda **kw: order_log.append('sell') or _ok())

        await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': 1}],
            sell_orders=[{'stock_code': '000660', 'quantity': 1}],
        )

        assert order_log == ['buy', 'sell']

    async def test_result_contains_user_id_and_iso_timestamp(self, executor):
        result = await executor.execute_for_user(user_id=42, buy_orders=[], sell_orders=[])

        assert result['user_id'] == 42
        assert set(result) == {'user_id', 'buy_results', 'sell_results', 'executed_at'}
        # ISO 8601 로 파싱 가능해야 한다
        assert isinstance(datetime.fromisoformat(result['executed_at']), datetime)

    async def test_empty_orders_make_no_calls(self, executor, internal_api):
        result = await executor.execute_for_user(user_id=1, buy_orders=[], sell_orders=[])

        internal_api.execute_buy.assert_not_awaited()
        internal_api.execute_sell.assert_not_awaited()
        assert result['buy_results'] == []
        assert result['sell_results'] == []

    async def test_failed_order_is_still_recorded(self, executor, internal_api):
        """주문 실패(success=False)도 결과에 그대로 담긴다 — 예외로 번지지 않는다."""
        internal_api.execute_buy = AsyncMock(return_value=_fail('주문가능금액 부족'))

        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': 1}],
            sell_orders=[],
        )

        assert result['buy_results'][0]['result'] == {'success': False, 'error': '주문가능금액 부족'}

    async def test_partial_failure_does_not_stop_remaining_orders(self, executor, internal_api):
        """앞선 주문이 실패해도 뒤따르는 주문은 계속 실행된다."""
        internal_api.execute_buy = AsyncMock(side_effect=[_fail(), _ok('ORD-2')])

        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[
                {'stock_code': '005930', 'quantity': 1},
                {'stock_code': '000660', 'quantity': 2},
            ],
            sell_orders=[],
        )

        assert internal_api.execute_buy.await_count == 2
        assert result['buy_results'][0]['result']['success'] is False
        assert result['buy_results'][1]['result']['success'] is True

    async def test_result_without_success_key_is_tolerated(self, executor, internal_api):
        """내부 API 가 success 키 없는 응답을 줘도 집계에서 터지지 않는다."""
        internal_api.execute_buy = AsyncMock(return_value={})

        result = await executor.execute_for_user(
            user_id=1,
            buy_orders=[{'stock_code': '005930', 'quantity': 1}],
            sell_orders=[],
        )

        assert result['buy_results'][0]['result'] == {}


class TestTradeExecutorErrorPropagation:
    """현재 구현의 에러 처리 경계 (문서화 목적)."""

    async def test_exception_from_internal_api_propagates(self, executor, internal_api):
        """내부 API 가 예외를 던지면 TradeExecutor 는 삼키지 않고 그대로 올린다.

        InternalApiClient 는 자체적으로 모든 예외를 {'success': False} 로 degrade 하므로
        정상 조합에서는 발생하지 않지만, 다른 클라이언트가 주입되면 파이프라인까지 전파된다.
        """
        internal_api.execute_buy = AsyncMock(side_effect=TimeoutError('api-server timeout'))

        with pytest.raises(TimeoutError):
            await executor.execute_for_user(
                user_id=1,
                buy_orders=[
                    {'stock_code': '005930', 'quantity': 1},
                    {'stock_code': '000660', 'quantity': 1},
                ],
                sell_orders=[],
            )

        # 첫 주문에서 중단되어 두 번째 주문은 시도조차 되지 않는다
        assert internal_api.execute_buy.await_count == 1

    async def test_missing_stock_code_raises_key_error(self, executor):
        """stock_code 없는 주문은 KeyError 로 즉시 실패한다 (검증 계층 없음)."""
        with pytest.raises(KeyError):
            await executor.execute_for_user(
                user_id=1,
                buy_orders=[{'stock_name': '삼성전자', 'quantity': 1}],
                sell_orders=[],
            )

    async def test_non_numeric_quantity_raises_value_error(self, executor):
        """숫자로 변환 불가한 수량은 int() 에서 ValueError 를 낸다."""
        with pytest.raises(ValueError):
            await executor.execute_for_user(
                user_id=1,
                buy_orders=[{'stock_code': '005930', 'quantity': 'ten'}],
                sell_orders=[],
            )


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
