"""메시지 계약(payload) 단위 테스트.

api-server 담당자와 고정한 스키마를 코드가 그대로 지키는지 검증한다. 필드명이
camelCase 인 것, 멱등키 구성, 결과 메시지 파싱 규칙이 핵심.
브로커는 띄우지 않는다 (통합 검증은 tests/test_kafka_integration.py).
"""
from datetime import date

import pytest

from messaging import (
    PipelineRunRequest,
    TradeOrderResult,
    build_idempotency_key,
    build_pipeline_run_message,
    build_trade_order_message,
    now_kst_iso,
)
from messaging.trade_result_consumer import RESULT_STATUS_TO_DB


class TestTradeOrderRequestedSchema:
    """`trade.order.requested` — ai-agent 가 발행하는 쪽."""

    def test_exact_field_set(self):
        """계약에 정의된 8개 필드만, 정확한 이름으로 나간다."""
        _, value = build_trade_order_message(
            user_id=1, stock_code='005930', side='BUY', quantity=10,
            trade_date=date(2026, 8, 9),
        )

        assert set(value) == {
            'idempotencyKey', 'userId', 'stockCode', 'side',
            'quantity', 'price', 'tradeDate', 'requestedAt',
        }

    def test_values_match_contract_example(self):
        key, value = build_trade_order_message(
            user_id=1, stock_code='005930', side='BUY', quantity=10,
            trade_date=date(2026, 8, 9), requested_at='2026-08-09T08:55:00+09:00',
        )

        assert key == '1:005930:2026-08-09:BUY'
        assert value == {
            'idempotencyKey': '1:005930:2026-08-09:BUY',
            'userId': 1,
            'stockCode': '005930',
            'side': 'BUY',
            'quantity': 10,
            'price': 0,
            'tradeDate': '2026-08-09',
            'requestedAt': '2026-08-09T08:55:00+09:00',
        }

    def test_message_key_equals_idempotency_key(self):
        """키가 멱등키와 같아야 같은 주문이 같은 파티션으로 가서 순서가 보장된다."""
        key, value = build_trade_order_message(
            user_id=3, stock_code='035420', side='SELL', quantity=2,
            trade_date=date(2026, 8, 9),
        )

        assert key == value['idempotencyKey']

    def test_types_are_json_native(self):
        """userId/quantity 는 int, 나머지는 str — 문자열 입력도 정규화된다."""
        _, value = build_trade_order_message(
            user_id='7', stock_code='005930', side='buy', quantity='10',
            trade_date=date(2026, 8, 9),
        )

        assert value['userId'] == 7 and isinstance(value['userId'], int)
        assert value['quantity'] == 10 and isinstance(value['quantity'], int)
        assert value['side'] == 'BUY'

    def test_requested_at_defaults_to_kst(self):
        _, value = build_trade_order_message(
            user_id=1, stock_code='005930', side='BUY', quantity=1,
            trade_date=date(2026, 8, 9),
        )

        assert value['requestedAt'].endswith('+09:00')

    def test_now_kst_iso_has_offset(self):
        assert now_kst_iso().endswith('+09:00')


class TestPipelineRunRequestedSchema:
    """`pipeline.run.requested` — ai-agent 내부 (발행 + 소비)."""

    def test_exact_field_set_and_key(self):
        key, value = build_pipeline_run_message(date(2026, 8, 9), 'SCHEDULED',
                                                requested_at='2026-08-09T08:50:00+09:00')

        assert key == '2026-08-09'  # key 는 거래일
        assert value == {
            'tradeDate': '2026-08-09',
            'triggerType': 'SCHEDULED',
            'requestedAt': '2026-08-09T08:50:00+09:00',
        }

    @pytest.mark.parametrize('trigger', ['SCHEDULED', 'MANUAL'])
    def test_trigger_types(self, trigger):
        _, value = build_pipeline_run_message(date(2026, 8, 9), trigger)
        assert value['triggerType'] == trigger

    def test_trigger_type_is_uppercased(self):
        _, value = build_pipeline_run_message(date(2026, 8, 9), 'manual')
        assert value['triggerType'] == 'MANUAL'

    def test_round_trip_parse(self):
        _, value = build_pipeline_run_message(date(2026, 8, 9), 'MANUAL')

        parsed = PipelineRunRequest.from_message(value)

        assert parsed.trade_date == date(2026, 8, 9)
        assert parsed.trigger_type == 'MANUAL'

    def test_missing_trade_date_raises(self):
        with pytest.raises(ValueError, match='tradeDate'):
            PipelineRunRequest.from_message({'triggerType': 'MANUAL'})

    def test_invalid_trade_date_raises(self):
        with pytest.raises(ValueError, match='invalid tradeDate'):
            PipelineRunRequest.from_message({'tradeDate': '2026/08/09'})

    def test_unknown_trigger_type_is_tolerated(self):
        """모르는 triggerType 때문에 실행을 못 하면 안 된다 — UNKNOWN 으로 흡수."""
        parsed = PipelineRunRequest.from_message({'tradeDate': '2026-08-09'})
        assert parsed.trigger_type == 'UNKNOWN'


class TestTradeOrderResultSchema:
    """`trade.order.result` — api-server 가 발행하고 ai-agent 가 소비하는 쪽."""

    CONTRACT_EXAMPLE = {
        'idempotencyKey': '1:005930:2026-08-09:BUY',
        'userId': 1,
        'stockCode': '005930',
        'side': 'BUY',
        'status': 'SUCCESS',
        'kisOrderNo': None,
        'errorMessage': None,
        'processedAt': '2026-08-09T08:55:03+09:00',
    }

    def test_parses_contract_example(self):
        result = TradeOrderResult.from_message(self.CONTRACT_EXAMPLE)

        assert result.idempotency_key == '1:005930:2026-08-09:BUY'
        assert result.user_id == 1
        assert result.stock_code == '005930'
        assert result.side == 'BUY'
        assert result.status == 'SUCCESS'
        assert result.kis_order_no is None
        assert result.error_message is None
        assert result.processed_at == '2026-08-09T08:55:03+09:00'

    def test_trade_date_is_recovered_from_idempotency_key(self):
        """계약에 tradeDate 필드가 없으므로 멱등키에서 되꺼낸다 (DB 매칭에 필수)."""
        result = TradeOrderResult.from_message(self.CONTRACT_EXAMPLE)

        assert result.trade_date == date(2026, 8, 9)

    def test_failed_result_carries_error_message(self):
        payload = dict(self.CONTRACT_EXAMPLE, status='FAILED', errorMessage='주문가능금액 부족')

        result = TradeOrderResult.from_message(payload)

        assert result.status == 'FAILED'
        assert result.error_message == '주문가능금액 부족'

    def test_kis_order_no_is_kept(self):
        payload = dict(self.CONTRACT_EXAMPLE, kisOrderNo='0000123')

        assert TradeOrderResult.from_message(payload).kis_order_no == '0000123'

    def test_falls_back_to_key_parts_when_fields_missing(self):
        """본문에 userId/stockCode/side 가 없어도 멱등키만으로 복원된다."""
        result = TradeOrderResult.from_message({
            'idempotencyKey': '42:000660:2026-01-02:SELL', 'status': 'SUCCESS',
        })

        assert (result.user_id, result.stock_code, result.side) == (42, '000660', 'SELL')
        assert result.trade_date == date(2026, 1, 2)

    @pytest.mark.parametrize('bad_key', ['1:005930:2026-08-09', '', 'nonsense', '1:005930:x:BUY:extra'])
    def test_malformed_idempotency_key_raises(self, bad_key):
        with pytest.raises(ValueError):
            TradeOrderResult.from_message({'idempotencyKey': bad_key, 'status': 'SUCCESS'})

    def test_invalid_date_in_key_raises(self):
        with pytest.raises(ValueError, match='tradeDate'):
            TradeOrderResult.from_message({
                'idempotencyKey': '1:005930:2026-13-45:BUY', 'status': 'SUCCESS',
            })

    def test_basic_iso_date_in_key_is_accepted(self):
        """Python 3.11 의 date.fromisoformat 은 'yyyyMMdd' 도 받는다 (동작 문서화)."""
        result = TradeOrderResult.from_message({
            'idempotencyKey': '1:005930:20260809:BUY', 'status': 'SUCCESS',
        })
        assert result.trade_date == date(2026, 8, 9)

    def test_missing_status_raises(self):
        with pytest.raises(ValueError, match='status'):
            TradeOrderResult.from_message({'idempotencyKey': '1:005930:2026-08-09:BUY'})

    def test_non_dict_payload_raises(self):
        with pytest.raises(ValueError):
            TradeOrderResult.from_message(['not', 'an', 'object'])


class TestResultStatusMapping:
    """계약 status → DB execution_status 매핑."""

    def test_success_is_stored_as_executed(self):
        """`v_latest_trade_plan` / `v_market_overview` 가 'EXECUTED' 로 체결을 세므로
        DB 에는 기존 값을 유지한다 (원문 status 는 execution_result 에 보존)."""
        assert RESULT_STATUS_TO_DB['SUCCESS'] == 'EXECUTED'

    def test_failed_maps_to_failed(self):
        assert RESULT_STATUS_TO_DB['FAILED'] == 'FAILED'


class TestIdempotencyKeyCollisions:
    """멱등키가 실제로 '중복 주문 1회'를 보장하는 단위인지 확인."""

    def test_same_stock_different_users_are_distinct(self):
        a = build_idempotency_key(1, '005930', date(2026, 8, 9), 'BUY')
        b = build_idempotency_key(2, '005930', date(2026, 8, 9), 'BUY')
        assert a != b

    def test_same_order_different_dates_are_distinct(self):
        a = build_idempotency_key(1, '005930', date(2026, 8, 9), 'BUY')
        b = build_idempotency_key(1, '005930', date(2026, 8, 10), 'BUY')
        assert a != b

    def test_repeated_pipeline_run_produces_identical_key(self):
        """같은 거래일에 파이프라인이 두 번 돌아도 같은 주문이면 키가 같다 —
        api-server 가 이 키로 두 번째를 버릴 수 있는 근거."""
        run1 = build_trade_order_message(
            user_id=1, stock_code='005930', side='BUY', quantity=10,
            trade_date=date(2026, 8, 9),
        )[0]
        run2 = build_trade_order_message(
            user_id=1, stock_code='005930', side='BUY', quantity=7,  # 수량이 달라도
            trade_date=date(2026, 8, 9),
        )[0]

        assert run1 == run2


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
