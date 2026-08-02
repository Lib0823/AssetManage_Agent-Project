"""
Safety Filter Module

Post-Gemini verification layer using feature-based rules to validate trading decisions.
This filter prevents risky trades by checking quantitative thresholds.

Enhanced Version: 추가된 안전망 규칙
- PER, ROE, Operating Margin 추가 검증
- Morning Return, Close Position 추가 확인
- Volume Trend 추가 검증
"""

import pandas as pd
import logging
from typing import List, Dict, Optional, Tuple
from datetime import date

logger = logging.getLogger(__name__)


class SafetyFilter:
    """
    Feature-based safety filter that validates AI trading decisions.

    Enhanced Filtering Rules (11 Features):

    BUY 조건 (ALL must pass):
    1. prophet_price_uncertainty <= 500 (예측 불확실성 낮음)
    2. foreign_net_buy > 0 (외국인 순매수)
    3. institutional_net_buy > 0 (기관 순매수)
    4. sentiment_score >= 0.3 (긍정적 뉴스)
    5. prophet_price_trend > 0 (가격 상승 추세)
    6. prophet_volume_trend > 0 (거래량 증가 추세) [NEW]
    7. per <= 30 (PER 과대평가 방지) [NEW]
    8. roe >= 10 (수익성 확보) [NEW]
    9. operating_margin >= 5 (영업이익률 확보) [NEW]
    10. morning_return > 0 (장초반 상승세) [NEW]
    11. close_position >= 0.6 (고가 근처 마감) [NEW]

    SELL 조건 (Supply AND (Sentiment OR Trend)):
    1. prophet_price_uncertainty <= 500
    2. (foreign_net_buy < 0 OR institutional_net_buy < 0)
    3. (sentiment_score <= -0.3 OR prophet_price_trend < 0)

    임계값은 생성자 기본값(아래)이 폴백이며, `thresholds` 인자로
    `feature_threshold_config` DB 행을 넘기면 해당 값이 우선 적용된다.
    """

    # feature_threshold_config.feature_name → 내부 속성 매핑
    #   {feature_name: {'buy': (attr, expected_operator), 'sell': (attr, expected_operator)}}
    # expected_operator 는 이 클래스가 실제로 구현한 비교 방향이다. DB operator 가
    # 이와 다르면 임계값을 적용하면 의미가 뒤집히므로 경고만 남기고 코드 기본값을 유지한다.
    DB_THRESHOLD_MAP = {
        'prophet_price_uncertainty': {'buy': ('uncertainty_threshold', '<=')},
        'foreign_net_buy': {
            'buy': ('foreign_net_buy_min', '>'),
            'sell': ('foreign_net_buy_sell_max', '<'),
        },
        'institutional_net_buy': {
            'buy': ('institutional_net_buy_min', '>'),
            'sell': ('institutional_net_buy_sell_max', '<'),
        },
        'sentiment_score': {
            'buy': ('sentiment_pos_threshold', '>='),
            'sell': ('sentiment_neg_threshold', '<='),
        },
        'prophet_price_trend': {
            'buy': ('price_trend_min', '>'),
            'sell': ('price_trend_sell_max', '<'),
        },
        'prophet_volume_trend': {'buy': ('volume_trend_min', '>')},
        'per': {'buy': ('per_max', '<=')},
        'roe': {'buy': ('roe_min', '>=')},
        'operating_margin': {'buy': ('operating_margin_min', '>=')},
        'morning_return': {'buy': ('morning_return_min', '>')},
        'close_position': {'buy': ('close_position_min', '>=')},
    }

    def __init__(self,
                 # Sentiment thresholds
                 sentiment_positive_threshold: float = 0.3,
                 sentiment_negative_threshold: float = -0.3,

                 # Uncertainty threshold
                 uncertainty_threshold: float = 500,

                 # NEW: Fundamental thresholds
                 per_max_threshold: float = 30.0,          # PER 상한
                 roe_min_threshold: float = 10.0,          # ROE 하한 (%)
                 operating_margin_min: float = 5.0,        # 영업이익률 하한 (%)

                 # NEW: Technical thresholds
                 close_position_min: float = 0.6,          # 종가 위치 하한 (0~1)
                 volume_trend_min: float = 0.0,            # 거래량 추세 하한

                 # 수급/모멘텀 부호 임계값 (DB 시드도 0)
                 foreign_net_buy_min: float = 0.0,         # 매수 시 외국인 순매수 하한
                 institutional_net_buy_min: float = 0.0,   # 매수 시 기관 순매수 하한
                 price_trend_min: float = 0.0,             # 매수 시 가격 추세 하한
                 morning_return_min: float = 0.0,          # 매수 시 장초반 수익률 하한
                 foreign_net_buy_sell_max: float = 0.0,    # 매도 시 외국인 순매수 상한
                 institutional_net_buy_sell_max: float = 0.0,  # 매도 시 기관 순매수 상한
                 price_trend_sell_max: float = 0.0,        # 매도 시 가격 추세 상한

                 # DB(feature_threshold_config) override
                 thresholds: Optional[Dict[str, Dict]] = None):
        """
        Initialize safety filter with configurable thresholds.

        임계값 우선순위: `thresholds`(DB) > 생성자 인자 > 코드 기본값.
        `thresholds` 가 None/빈 dict 이거나 특정 피처 행이 없으면 해당 임계값은
        기존 하드코딩 기본값을 그대로 사용한다(안전 폴백).

        Args:
            sentiment_positive_threshold: Minimum sentiment score for buy
            sentiment_negative_threshold: Maximum sentiment score for sell
            uncertainty_threshold: Maximum allowed price uncertainty
            per_max_threshold: Maximum PER for buy (avoid overvaluation)
            roe_min_threshold: Minimum ROE (%) for buy
            operating_margin_min: Minimum operating margin (%) for buy
            close_position_min: Minimum close position (0~1) for buy
            volume_trend_min: Minimum volume trend for buy
            foreign_net_buy_min: Minimum foreign net buy for buy
            institutional_net_buy_min: Minimum institutional net buy for buy
            price_trend_min: Minimum Prophet price trend for buy
            morning_return_min: Minimum morning return (%) for buy
            foreign_net_buy_sell_max: Foreign net buy must be below this for sell
            institutional_net_buy_sell_max: Institutional net buy must be below this for sell
            price_trend_sell_max: Prophet price trend must be below this for sell
            thresholds: `DatabaseRepository.get_feature_thresholds()` 결과
                ({feature_name: {buy_enabled, buy_operator, buy_threshold, ...}}).
                None 또는 {} 이면 위 기본값만 사용.

        Note:
            DB 의 `buy_enabled`/`sell_enabled=false` 는 **규칙 자체를 끄지 않는다**
            (임계값 override 만 건너뛴다). 규칙 on/off 는 별도 과제.
        """
        # Existing thresholds
        self.sentiment_pos_threshold = sentiment_positive_threshold
        self.sentiment_neg_threshold = sentiment_negative_threshold
        self.uncertainty_threshold = uncertainty_threshold

        # NEW: Fundamental thresholds
        self.per_max = per_max_threshold
        self.roe_min = roe_min_threshold
        self.operating_margin_min = operating_margin_min

        # NEW: Technical thresholds
        self.close_position_min = close_position_min
        self.volume_trend_min = volume_trend_min

        # 수급/모멘텀 부호 임계값
        self.foreign_net_buy_min = foreign_net_buy_min
        self.institutional_net_buy_min = institutional_net_buy_min
        self.price_trend_min = price_trend_min
        self.morning_return_min = morning_return_min
        self.foreign_net_buy_sell_max = foreign_net_buy_sell_max
        self.institutional_net_buy_sell_max = institutional_net_buy_sell_max
        self.price_trend_sell_max = price_trend_sell_max

        # DB 값이 있으면 위 기본값 위에 덮어쓴다 (없으면 그대로 폴백)
        self.threshold_source = 'defaults'
        applied = self._apply_db_thresholds(thresholds)
        if applied:
            self.threshold_source = 'feature_threshold_config'

        logger.info(f"SafetyFilter initialized with enhanced rules "
                   f"(source={self.threshold_source}, db_overrides={applied}): "
                   f"PER<={self.per_max}, ROE>={self.roe_min}%, "
                   f"OpMargin>={self.operating_margin_min}%, "
                   f"ClosePos>={self.close_position_min}, "
                   f"VolTrend>{self.volume_trend_min}, "
                   f"Uncertainty<={self.uncertainty_threshold}, "
                   f"Sentiment>={self.sentiment_pos_threshold}/<={self.sentiment_neg_threshold}")

    def _apply_db_thresholds(self, thresholds: Optional[Dict[str, Dict]]) -> int:
        """
        Override built-in thresholds with feature_threshold_config rows.

        각 행은 다음 조건을 모두 만족해야 적용된다:
        - `is_active` 가 False 가 아님
        - 해당 side 의 `*_enabled` 가 False 가 아님
        - `*_threshold` 가 None 이 아니고 float 로 변환 가능
        - `*_operator` 가 코드 구현 방향(DB_THRESHOLD_MAP 의 expected_operator)과 일치
          (None 이면 검증 생략)

        하나라도 어긋나면 그 임계값만 건너뛰고 코드 기본값을 유지한다.

        Args:
            thresholds: {feature_name: row dict} 또는 None

        Returns:
            int: 실제로 덮어쓴 임계값 개수 (0 이면 전부 기본값)
        """
        if not thresholds:
            logger.warning(
                "No feature_threshold_config rows provided; "
                "using built-in default thresholds"
            )
            return 0

        applied = 0
        for feature_name, sides in self.DB_THRESHOLD_MAP.items():
            row = thresholds.get(feature_name)
            if not row:
                continue
            if row.get('is_active') is False:
                logger.debug(f"Threshold '{feature_name}' is inactive; keeping default")
                continue

            for side, (attr, expected_operator) in sides.items():
                if row.get(f'{side}_enabled') is False:
                    logger.debug(
                        f"Threshold '{feature_name}.{side}' disabled in DB; "
                        f"keeping default {attr}={getattr(self, attr)}"
                    )
                    continue

                raw_value = row.get(f'{side}_threshold')
                if raw_value is None:
                    continue

                operator = row.get(f'{side}_operator')
                if operator is not None and operator.strip() != expected_operator:
                    logger.warning(
                        f"Threshold '{feature_name}.{side}' operator mismatch "
                        f"(DB='{operator}', code='{expected_operator}'); "
                        f"keeping default {attr}={getattr(self, attr)}"
                    )
                    continue

                try:
                    value = float(raw_value)
                except (TypeError, ValueError):
                    logger.warning(
                        f"Threshold '{feature_name}.{side}' is not numeric "
                        f"({raw_value!r}); keeping default {attr}={getattr(self, attr)}"
                    )
                    continue

                if value != getattr(self, attr):
                    logger.info(
                        f"Threshold override from DB: {attr} "
                        f"{getattr(self, attr)} → {value} ({feature_name}.{side})"
                    )
                setattr(self, attr, value)
                applied += 1

        return applied

    def apply_buy_filter(self, features: Dict[str, float]) -> Tuple[bool, Optional[str], Dict]:
        """
        Apply enhanced buy filter rules with detailed check results.

        Args:
            features: Dictionary containing all 11 features for a stock

        Returns:
            (passed: bool, failure_reason: Optional[str], check_details: Dict)
        """
        check_details = {}
        conditions = []

        # Check 1: Uncertainty (가장 먼저 체크)
        uncertainty_value = features.get('prophet_price_uncertainty', 0)
        check_details['uncertainty_check'] = {
            'passed': uncertainty_value <= self.uncertainty_threshold,
            'value': uncertainty_value,
            'threshold': self.uncertainty_threshold
        }
        if uncertainty_value > self.uncertainty_threshold:
            conditions.append(f"High uncertainty: {uncertainty_value:.2f} > {self.uncertainty_threshold}")

        # Check 2: Foreign net buy > 0
        foreign_value = features.get('foreign_net_buy', 0)
        check_details['foreign_net_buy_check'] = {
            'passed': foreign_value > self.foreign_net_buy_min,
            'value': foreign_value,
            'threshold': self.foreign_net_buy_min
        }
        if foreign_value <= self.foreign_net_buy_min:
            conditions.append(f"Foreign net buy not positive: {foreign_value:,}")

        # Check 3: Institutional net buy > 0
        institutional_value = features.get('institutional_net_buy', 0)
        check_details['institutional_net_buy_check'] = {
            'passed': institutional_value > self.institutional_net_buy_min,
            'value': institutional_value,
            'threshold': self.institutional_net_buy_min
        }
        if institutional_value <= self.institutional_net_buy_min:
            conditions.append(f"Institutional net buy not positive: {institutional_value:,}")

        # Check 4: Sentiment score >= 0.3
        sentiment_value = features.get('sentiment_score', 0)
        check_details['sentiment_check'] = {
            'passed': sentiment_value >= self.sentiment_pos_threshold,
            'value': sentiment_value,
            'threshold': self.sentiment_pos_threshold
        }
        if sentiment_value < self.sentiment_pos_threshold:
            conditions.append(f"Sentiment too low: {sentiment_value:.2f} < {self.sentiment_pos_threshold}")

        # Check 5: Price trend > 0
        price_trend_value = features.get('prophet_price_trend', 0)
        check_details['price_trend_check'] = {
            'passed': price_trend_value > self.price_trend_min,
            'value': price_trend_value,
            'threshold': self.price_trend_min
        }
        if price_trend_value <= self.price_trend_min:
            conditions.append(f"Price trend not positive: {price_trend_value:.4f}")

        # NEW Check 6: Volume trend > 0
        volume_trend_value = features.get('prophet_volume_trend', 0)
        check_details['volume_trend_check'] = {
            'passed': volume_trend_value > self.volume_trend_min,
            'value': volume_trend_value,
            'threshold': self.volume_trend_min
        }
        if volume_trend_value <= self.volume_trend_min:
            conditions.append(f"Volume trend weak: {volume_trend_value:.4f} <= {self.volume_trend_min}")

        # NEW Check 7: PER <= 30 (None 허용 - 적자 기업 제외)
        per_value = features.get('per')
        if per_value is not None:
            check_details['per_check'] = {
                'passed': per_value <= self.per_max,
                'value': per_value,
                'threshold': self.per_max
            }
            if per_value > self.per_max:
                conditions.append(f"PER too high (overvalued): {per_value:.2f} > {self.per_max}")
        else:
            # PER이 None이면 적자 기업 → 매수 제외
            check_details['per_check'] = {'passed': False, 'value': None, 'threshold': self.per_max}
            conditions.append("PER is None (loss-making company)")

        # NEW Check 8: ROE >= 10% (None/NaN 허용 - 재무데이터 결측 시 매수 제외)
        roe_value = features.get('roe')
        if roe_value is None or pd.isna(roe_value):
            # ROE 결측 → 펀더멘탈 확인 불가 → 매수 제외
            check_details['roe_check'] = {'passed': False, 'value': None, 'threshold': self.roe_min}
            conditions.append("ROE is missing (no DART data)")
        else:
            check_details['roe_check'] = {
                'passed': roe_value >= self.roe_min,
                'value': roe_value,
                'threshold': self.roe_min
            }
            if roe_value < self.roe_min:
                conditions.append(f"ROE too low: {roe_value:.2f}% < {self.roe_min}%")

        # NEW Check 9: Operating margin >= 5% (None/NaN 허용 - 재무데이터 결측 시 매수 제외)
        op_margin_value = features.get('operating_margin')
        if op_margin_value is None or pd.isna(op_margin_value):
            # 영업이익률 결측 → 펀더멘탈 확인 불가 → 매수 제외
            check_details['operating_margin_check'] = {'passed': False, 'value': None, 'threshold': self.operating_margin_min}
            conditions.append("Operating margin is missing (no DART data)")
        else:
            check_details['operating_margin_check'] = {
                'passed': op_margin_value >= self.operating_margin_min,
                'value': op_margin_value,
                'threshold': self.operating_margin_min
            }
            if op_margin_value < self.operating_margin_min:
                conditions.append(f"Operating margin too low: {op_margin_value:.2f}% < {self.operating_margin_min}%")

        # NEW Check 10: Morning return > 0
        morning_return_value = features.get('morning_return', 0)
        check_details['morning_return_check'] = {
            'passed': morning_return_value > self.morning_return_min,
            'value': morning_return_value,
            'threshold': self.morning_return_min
        }
        if morning_return_value <= self.morning_return_min:
            conditions.append(f"Morning return not positive: {morning_return_value:.2f}%")

        # NEW Check 11: Close position >= 0.6
        close_pos_value = features.get('close_position', 0)
        check_details['close_position_check'] = {
            'passed': close_pos_value >= self.close_position_min,
            'value': close_pos_value,
            'threshold': self.close_position_min
        }
        if close_pos_value < self.close_position_min:
            conditions.append(f"Close position too low: {close_pos_value:.2f} < {self.close_position_min}")

        # Final result
        passed = len(conditions) == 0
        failure_reason = "; ".join(conditions) if conditions else None

        logger.debug(f"Buy filter: passed={passed}, failed_checks={len(conditions)}/11")

        return passed, failure_reason, check_details

    def apply_sell_filter(self, features: Dict[str, float]) -> Tuple[bool, Optional[str], Dict]:
        """
        Apply sell filter rules with detailed check results.

        Args:
            features: Dictionary containing all 11 features for a stock

        Returns:
            (passed: bool, failure_reason: Optional[str], check_details: Dict)
        """
        check_details = {}
        conditions = []

        # Check 1: Uncertainty
        uncertainty_value = features.get('prophet_price_uncertainty', 0)
        check_details['uncertainty_check'] = {
            'passed': uncertainty_value <= self.uncertainty_threshold,
            'value': uncertainty_value,
            'threshold': self.uncertainty_threshold
        }
        if uncertainty_value > self.uncertainty_threshold:
            conditions.append(f"High uncertainty: {uncertainty_value:.2f} > {self.uncertainty_threshold}")
            return False, conditions[0], check_details

        # Check 2: Supply condition (Foreign OR Institutional selling)
        foreign_value = features.get('foreign_net_buy', 0)
        institutional_value = features.get('institutional_net_buy', 0)

        check_details['foreign_selling_check'] = {
            'passed': foreign_value < self.foreign_net_buy_sell_max,
            'value': foreign_value,
            'threshold': self.foreign_net_buy_sell_max
        }
        check_details['institutional_selling_check'] = {
            'passed': institutional_value < self.institutional_net_buy_sell_max,
            'value': institutional_value,
            'threshold': self.institutional_net_buy_sell_max
        }

        supply_condition = (foreign_value < self.foreign_net_buy_sell_max
                            or institutional_value < self.institutional_net_buy_sell_max)

        if not supply_condition:
            conditions.append("No selling pressure from foreign or institutional investors")
            return False, conditions[0], check_details

        # Check 3: Sentiment condition
        sentiment_value = features.get('sentiment_score', 0)
        check_details['sentiment_check'] = {
            'passed': sentiment_value <= self.sentiment_neg_threshold,
            'value': sentiment_value,
            'threshold': self.sentiment_neg_threshold
        }
        sentiment_condition = sentiment_value <= self.sentiment_neg_threshold

        # Check 4: Price trend condition
        price_trend_value = features.get('prophet_price_trend', 0)
        check_details['price_trend_check'] = {
            'passed': price_trend_value < self.price_trend_sell_max,
            'value': price_trend_value,
            'threshold': self.price_trend_sell_max
        }
        trend_condition = price_trend_value < self.price_trend_sell_max

        if not (sentiment_condition or trend_condition):
            if not sentiment_condition:
                conditions.append(f"Sentiment not negative enough: {sentiment_value:.2f} > {self.sentiment_neg_threshold}")
            if not trend_condition:
                conditions.append(f"Price trend not negative: {price_trend_value:.4f} >= 0")

            return False, "Neither negative sentiment nor negative trend: " + "; ".join(conditions), check_details

        logger.debug(f"Sell filter: passed=True")
        return True, None, check_details

    def check_investment_limit(self,
                               stock_code: str,
                               current_price: float,
                               order_amount: int,
                               user_id: int = 1) -> tuple[bool, Optional[str], int]:
        """
        Check if purchase amount exceeds configured order_amount limit.

        Args:
            stock_code: 6-digit stock code
            current_price: Current stock price
            order_amount: User's configured order amount from user_trade_config
            user_id: User ID (default: 1 for MVP)

        Returns:
            (passed: bool, failure_reason: Optional[str], max_quantity: int)
        """
        # Calculate maximum quantity based on order_amount
        max_quantity = int(order_amount / current_price)

        if max_quantity <= 0:
            return False, f"Stock price ({current_price:,}원) exceeds order amount ({order_amount:,}원)", 0

        return True, None, max_quantity

    def filter_decisions(self,
                        decisions: Dict[str, List[Dict]],
                        features_df: pd.DataFrame,
                        stock_prices: Optional[Dict[str, float]] = None,
                        order_amount: Optional[int] = None) -> Dict[str, List[Dict]]:
        """
        Filter Gemini TOP3 buy/sell decisions using safety rules.

        Args:
            decisions: Gemini API response with buy_top3 and sell_top3
            features_df: DataFrame containing 11 features for all stocks
                        Columns: stock_code, morning_return, close_position, foreign_net_buy,
                                institutional_net_buy, per, roe, operating_margin,
                                sentiment_score, prophet_price_trend, prophet_volume_trend,
                                prophet_price_uncertainty
            stock_prices: Optional dict mapping stock_code -> current_price (for investment limit check)
            order_amount: Optional user's configured order amount (from user_trade_config)

        Returns:
            Filtered decisions dict with same structure, plus filter_results list
        """
        filtered_decisions = {
            'buy_top3': [],
            'sell_top3': [],
            'filter_results': []
        }

        # Create stock_code -> features mapping
        features_dict = features_df.set_index('stock_code').to_dict('index')

        # Filter buy decisions
        for item in decisions.get('buy_top3', []):
            stock_code = item['stock_code']
            if stock_code not in features_dict:
                filtered_decisions['filter_results'].append({
                    'stock_code': stock_code,
                    'decision': 'BUY',
                    'passed': False,
                    'failure_reason': 'Features not found',
                    'max_quantity': 0,
                    'filter_checks': {}
                })
                continue

            features = features_dict[stock_code]
            passed, failure_reason, check_details = self.apply_buy_filter(features)

            # Additional check: Investment limit (if stock_prices and order_amount provided)
            max_quantity = None
            if passed and stock_prices and order_amount:
                current_price = stock_prices.get(stock_code)
                if current_price:
                    limit_passed, limit_reason, max_qty = self.check_investment_limit(
                        stock_code, current_price, order_amount
                    )
                    max_quantity = max_qty

                    # Add investment limit check to check_details
                    check_details['investment_limit_check'] = {
                        'passed': limit_passed,
                        'max_quantity': max_qty,
                        'current_price': current_price,
                        'order_amount': order_amount
                    }

                    if not limit_passed:
                        passed = False
                        failure_reason = f"{failure_reason}; {limit_reason}" if failure_reason else limit_reason

            if passed:
                buy_item = item.copy()
                if max_quantity is not None:
                    buy_item['max_quantity'] = max_quantity
                filtered_decisions['buy_top3'].append(buy_item)

            filtered_decisions['filter_results'].append({
                'stock_code': stock_code,
                'decision': 'BUY',
                'passed': passed,
                'failure_reason': failure_reason,
                'feature_values': features,
                'max_quantity': max_quantity,
                'filter_checks': check_details
            })

        # Filter sell decisions
        for item in decisions.get('sell_top3', []):
            stock_code = item['stock_code']
            if stock_code not in features_dict:
                filtered_decisions['filter_results'].append({
                    'stock_code': stock_code,
                    'decision': 'SELL',
                    'passed': False,
                    'failure_reason': 'Features not found',
                    'filter_checks': {}
                })
                continue

            features = features_dict[stock_code]
            passed, failure_reason, check_details = self.apply_sell_filter(features)

            if passed:
                filtered_decisions['sell_top3'].append(item)

            filtered_decisions['filter_results'].append({
                'stock_code': stock_code,
                'decision': 'SELL',
                'passed': passed,
                'failure_reason': failure_reason,
                'feature_values': features,
                'filter_checks': check_details
            })

        return filtered_decisions

    def save_filter_results(self,
                           filter_results: List[Dict],
                           filter_date: date,
                           conn) -> None:
        """
        Save safety filter results to safety_filter_result table.

        Args:
            filter_results: List of filter result dicts from filter_decisions()
            filter_date: Date of analysis
            conn: Database connection (psycopg2 or SQLAlchemy)
        """
        import json

        records = []
        for result in filter_results:
            records.append({
                'stock_code': result['stock_code'],
                'stock_name': result.get('stock_name', ''),  # TODO: Add stock name lookup
                'filter_date': filter_date,
                'decision': result['decision'],
                'passed': result['passed'],
                'failure_reason': result.get('failure_reason'),
                'feature_values': json.dumps(result.get('feature_values', {}))
            })

        df = pd.DataFrame(records)
        df.to_sql('safety_filter_result', conn, if_exists='append', index=False)
