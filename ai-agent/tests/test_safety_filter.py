"""Unit tests for SafetyFilter threshold wiring (feature_threshold_config)."""
import pandas as pd
import pytest

from filters.safety_filter import SafetyFilter


# feature_threshold_config 의 Liquibase 시드값과 동일한 행 집합
# (api-server/src/main/resources/db/changelog/mvp/v1.6-stage4-5-enhancements.yaml)
def _seed_rows():
    def row(buy_op=None, buy_th=None, sell_op=None, sell_th=None,
            buy_enabled=True, sell_enabled=True, is_active=True):
        return {
            'buy_enabled': buy_enabled,
            'buy_operator': buy_op,
            'buy_threshold': buy_th,
            'sell_enabled': sell_enabled,
            'sell_operator': sell_op,
            'sell_threshold': sell_th,
            'is_active': is_active,
        }

    return {
        'foreign_net_buy': row('>', 0.0, '<', 0.0),
        'institutional_net_buy': row('>', 0.0, '<', 0.0),
        'sentiment_score': row('>=', 0.3, '<=', -0.3),
        'prophet_price_trend': row('>', 0.0, '<', 0.0),
        'prophet_volume_trend': row('>', 0.0, sell_enabled=False),
        'prophet_price_uncertainty': row('<=', 500.0, '<=', 500.0),
        'per': row('<=', 30.0, sell_enabled=False),
        'roe': row('>=', 10.0, sell_enabled=False),
        'operating_margin': row('>=', 5.0, sell_enabled=False),
        'morning_return': row('>', 0.0, sell_enabled=False),
        'close_position': row('>=', 0.6, sell_enabled=False),
    }


def _passing_buy_features():
    """모든 매수 규칙을 통과하는 기준 피처 집합."""
    return {
        'prophet_price_uncertainty': 100.0,
        'foreign_net_buy': 1_000_000,
        'institutional_net_buy': 500_000,
        'sentiment_score': 0.5,
        'prophet_price_trend': 0.02,
        'prophet_volume_trend': 0.01,
        'per': 12.0,
        'roe': 15.0,
        'operating_margin': 8.0,
        'morning_return': 1.2,
        'close_position': 0.8,
    }


class TestDefaultThresholds:
    """DB 값이 없을 때 기존 하드코딩 기본값이 유지되는지."""

    @pytest.mark.parametrize('thresholds', [None, {}])
    def test_fallback_to_hardcoded_defaults(self, thresholds):
        sf = SafetyFilter(thresholds=thresholds)

        assert sf.threshold_source == 'defaults'
        assert sf.sentiment_pos_threshold == 0.3
        assert sf.sentiment_neg_threshold == -0.3
        assert sf.uncertainty_threshold == 500
        assert sf.uncertainty_threshold_sell == 500
        assert sf.per_max == 30.0
        assert sf.roe_min == 10.0
        assert sf.operating_margin_min == 5.0
        assert sf.close_position_min == 0.6
        assert sf.volume_trend_min == 0.0
        assert sf.foreign_net_buy_min == 0.0
        assert sf.institutional_net_buy_min == 0.0
        assert sf.price_trend_min == 0.0
        assert sf.morning_return_min == 0.0

    def test_no_arg_construction_still_works(self):
        """오케스트레이터 외 호출부(SafetyFilter())가 깨지지 않는지."""
        sf = SafetyFilter()
        assert sf.per_max == 30.0
        assert sf.threshold_source == 'defaults'

    def test_explicit_kwargs_still_honored(self):
        sf = SafetyFilter(per_max_threshold=15.0, roe_min_threshold=20.0)
        assert sf.per_max == 15.0
        assert sf.roe_min == 20.0


class TestDbThresholdOverride:
    """feature_threshold_config 행이 실제로 반영되는지."""

    def test_seed_rows_match_code_defaults(self):
        """DB 시드값 == 코드 기본값 (프로젝트의 알려진 괴리 함정 방어)."""
        default_sf = SafetyFilter()
        db_sf = SafetyFilter(thresholds=_seed_rows())

        assert db_sf.threshold_source == 'feature_threshold_config'
        for attr in ('sentiment_pos_threshold', 'sentiment_neg_threshold',
                     'uncertainty_threshold', 'uncertainty_threshold_sell',
                     'per_max', 'roe_min',
                     'operating_margin_min', 'close_position_min',
                     'volume_trend_min', 'foreign_net_buy_min',
                     'institutional_net_buy_min', 'price_trend_min',
                     'morning_return_min'):
            assert getattr(db_sf, attr) == getattr(default_sf, attr), attr

    def test_tuned_values_are_applied(self):
        rows = _seed_rows()
        rows['per']['buy_threshold'] = 8.0
        rows['roe']['buy_threshold'] = 25.0
        rows['sentiment_score']['buy_threshold'] = 0.7
        rows['prophet_price_uncertainty']['buy_threshold'] = 120.0

        sf = SafetyFilter(thresholds=rows)

        assert sf.per_max == 8.0
        assert sf.roe_min == 25.0
        assert sf.sentiment_pos_threshold == 0.7
        assert sf.uncertainty_threshold == 120.0

    def test_tuned_value_changes_buy_decision(self):
        """DB 튜닝이 실제 필터 판정을 바꾼다."""
        features = _passing_buy_features()

        assert SafetyFilter().apply_buy_filter(features)[0] is True

        rows = _seed_rows()
        rows['roe']['buy_threshold'] = 20.0  # features 의 roe=15 → 탈락
        passed, reason, checks = SafetyFilter(thresholds=rows).apply_buy_filter(features)

        assert passed is False
        assert 'ROE too low' in reason
        assert checks['roe_check']['threshold'] == 20.0

    def test_tuned_value_changes_sell_decision(self):
        features = {
            'prophet_price_uncertainty': 100.0,
            'foreign_net_buy': -1_000,
            'institutional_net_buy': -1_000,
            'sentiment_score': -0.4,
            'prophet_price_trend': 0.01,
        }
        assert SafetyFilter().apply_sell_filter(features)[0] is True

        rows = _seed_rows()
        rows['sentiment_score']['sell_threshold'] = -0.9  # -0.4 로는 부족
        rows['prophet_price_trend']['sell_threshold'] = -0.5

        passed, reason, _ = SafetyFilter(thresholds=rows).apply_sell_filter(features)
        assert passed is False
        assert 'Neither negative sentiment nor negative trend' in reason

    def test_uncertainty_sell_threshold_is_independent(self):
        """prophet_price_uncertainty 의 sell_threshold 가 매도측에만 적용된다."""
        rows = _seed_rows()
        rows['prophet_price_uncertainty']['sell_threshold'] = 50.0

        sf = SafetyFilter(thresholds=rows)

        assert sf.uncertainty_threshold == 500.0  # 매수측은 시드값 유지
        assert sf.uncertainty_threshold_sell == 50.0

        features = {
            'prophet_price_uncertainty': 100.0,
            'foreign_net_buy': -1_000,
            'institutional_net_buy': -1_000,
            'sentiment_score': -0.4,
            'prophet_price_trend': 0.01,
        }
        passed, reason, checks = sf.apply_sell_filter(features)

        assert passed is False
        assert 'High uncertainty' in reason
        assert checks['uncertainty_check']['threshold'] == 50.0

        # 매수측은 영향 없음 (uncertainty=100 <= 500)
        assert sf.apply_buy_filter(_passing_buy_features())[0] is True

    def test_decimal_string_threshold_is_coerced(self):
        rows = _seed_rows()
        rows['per']['buy_threshold'] = '17.5'
        sf = SafetyFilter(thresholds=rows)
        assert sf.per_max == 17.5


class TestDbThresholdGuards:
    """이상 행은 무시하고 기본값을 유지한다."""

    def test_inactive_row_is_ignored(self):
        rows = _seed_rows()
        rows['per']['buy_threshold'] = 5.0
        rows['per']['is_active'] = False

        sf = SafetyFilter(thresholds=rows)
        assert sf.per_max == 30.0

    def test_disabled_side_is_ignored(self):
        rows = _seed_rows()
        rows['roe']['buy_threshold'] = 99.0
        rows['roe']['buy_enabled'] = False

        sf = SafetyFilter(thresholds=rows)
        assert sf.roe_min == 10.0

    def test_null_threshold_is_ignored(self):
        rows = _seed_rows()
        rows['close_position']['buy_threshold'] = None

        sf = SafetyFilter(thresholds=rows)
        assert sf.close_position_min == 0.6

    def test_operator_mismatch_is_ignored(self):
        """DB operator 가 코드 구현 방향과 다르면 적용하지 않는다(의미 역전 방지)."""
        rows = _seed_rows()
        rows['per']['buy_operator'] = '>='
        rows['per']['buy_threshold'] = 5.0

        sf = SafetyFilter(thresholds=rows)
        assert sf.per_max == 30.0

    def test_missing_operator_is_allowed(self):
        rows = _seed_rows()
        rows['per']['buy_operator'] = None
        rows['per']['buy_threshold'] = 22.0

        sf = SafetyFilter(thresholds=rows)
        assert sf.per_max == 22.0

    def test_non_numeric_threshold_is_ignored(self):
        rows = _seed_rows()
        rows['operating_margin']['buy_threshold'] = 'N/A'

        sf = SafetyFilter(thresholds=rows)
        assert sf.operating_margin_min == 5.0

    def test_unknown_feature_name_is_ignored(self):
        rows = _seed_rows()
        rows['some_future_feature'] = {
            'buy_enabled': True, 'buy_operator': '>', 'buy_threshold': 1.0,
            'sell_enabled': True, 'sell_operator': '<', 'sell_threshold': 1.0,
            'is_active': True,
        }
        sf = SafetyFilter(thresholds=rows)  # 예외 없이 생성되어야 한다
        assert sf.threshold_source == 'feature_threshold_config'

    def test_partial_rows_only_override_present_features(self):
        rows = {'per': {'buy_enabled': True, 'buy_operator': '<=',
                        'buy_threshold': 11.0, 'is_active': True}}
        sf = SafetyFilter(thresholds=rows)
        assert sf.per_max == 11.0
        assert sf.roe_min == 10.0  # 나머지는 기본값


class TestRepositoryFallback:
    """DatabaseRepository.get_feature_thresholds 의 실패 폴백."""

    def test_returns_empty_dict_when_query_fails(self):
        from database.repository import DatabaseRepository

        class _BrokenSession:
            def execute(self, *args, **kwargs):
                raise RuntimeError("connection refused")

            def close(self):
                pass

        repo = DatabaseRepository.__new__(DatabaseRepository)
        repo.session_factory = lambda: _BrokenSession()

        assert repo.get_feature_thresholds() == {}

    def test_empty_table_returns_empty_dict(self):
        from database.repository import DatabaseRepository

        class _Result:
            def mappings(self):
                return self

            def all(self):
                return []

        class _EmptySession:
            def execute(self, *args, **kwargs):
                return _Result()

            def close(self):
                pass

        repo = DatabaseRepository.__new__(DatabaseRepository)
        repo.session_factory = lambda: _EmptySession()

        assert repo.get_feature_thresholds() == {}

    def test_rows_are_normalized(self):
        from decimal import Decimal
        from database.repository import DatabaseRepository

        rows = [{
            'feature_name': 'per',
            'buy_enabled': True,
            'buy_operator': '<=',
            'buy_threshold': Decimal('30.00000000'),
            'sell_enabled': False,
            'sell_operator': None,
            'sell_threshold': None,
            'is_active': True,
        }]

        class _Result:
            def mappings(self):
                return self

            def all(self):
                return rows

        class _Session:
            def execute(self, *args, **kwargs):
                return _Result()

            def close(self):
                pass

        repo = DatabaseRepository.__new__(DatabaseRepository)
        repo.session_factory = lambda: _Session()

        result = repo.get_feature_thresholds()
        assert result['per']['buy_threshold'] == 30.0
        assert isinstance(result['per']['buy_threshold'], float)
        assert result['per']['sell_threshold'] is None
        assert result['per']['sell_enabled'] is False

        # 폴백 없이 그대로 SafetyFilter 에 주입 가능한 형태인지
        sf = SafetyFilter(thresholds=result)
        assert sf.per_max == 30.0


class TestFilterResultsShape:
    """`filter_decisions` 가 만드는 filter_results 가 repository 가 읽는 키를 갖는지.

    `save_safety_filter_results` 는 `stock_name` / `current_price` / `max_quantity` 를
    `.get()` 으로 읽으므로, 생산자에 키가 없으면 조용히 빈 값이 DB 에 남는다.
    """

    CODE = '000660'          # STOCK_NAMES 등록 종목
    NAME = 'SK하이닉스'

    def _features_df(self, code=None):
        return pd.DataFrame([{'stock_code': code or self.CODE, **_passing_buy_features()}])

    def _run(self, decisions, features_df=None, stock_prices=None, order_amount=None):
        return SafetyFilter(thresholds=_seed_rows()).filter_decisions(
            decisions,
            features_df if features_df is not None else self._features_df(),
            stock_prices=stock_prices,
            order_amount=order_amount,
        )

    def test_buy_result_carries_name_price_and_quantity(self):
        result = self._run(
            {'buy_top3': [{'stock_code': self.CODE, 'reason': 'r'}]},
            stock_prices={self.CODE: 80_000},
            order_amount=1_000_000,
        )

        row = result['filter_results'][0]
        assert row['stock_name'] == self.NAME
        assert row['current_price'] == 80_000
        assert row['max_quantity'] == 12

    def test_sell_result_carries_name_price_and_quantity_key(self):
        result = self._run(
            {'sell_top3': [{'stock_code': self.CODE, 'reason': 'r'}]},
            stock_prices={self.CODE: 80_000},
        )

        row = result['filter_results'][0]
        assert row['stock_name'] == self.NAME
        assert row['current_price'] == 80_000
        # 매도 수량은 보유 수량에서 나오며 이 필터는 보유 정보를 받지 않는다
        assert row['max_quantity'] is None

    @pytest.mark.parametrize('side', ['buy_top3', 'sell_top3'])
    def test_features_not_found_result_still_carries_the_keys(self, side):
        result = self._run({side: [{'stock_code': self.CODE}]},
                           features_df=self._features_df('051910'))

        row = result['filter_results'][0]
        assert row['failure_reason'] == 'Features not found'
        assert row['stock_name'] == self.NAME
        assert 'current_price' in row and 'max_quantity' in row

    def test_current_price_is_present_even_without_order_amount(self):
        """투자한도 체크를 건너뛰어도(order_amount 없음) 현재가는 기록된다."""
        result = self._run({'buy_top3': [{'stock_code': self.CODE}]},
                           stock_prices={self.CODE: 80_000})

        row = result['filter_results'][0]
        assert row['current_price'] == 80_000
        assert row['max_quantity'] is None

    def test_unknown_stock_code_yields_empty_name_not_a_key_error(self):
        df = pd.DataFrame([{'stock_code': '005930', **_passing_buy_features()}])
        result = self._run({'buy_top3': [{'stock_code': '005930'}]}, features_df=df)

        assert result['filter_results'][0]['stock_name'] == ''
