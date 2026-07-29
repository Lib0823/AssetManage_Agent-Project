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
    """

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
                 volume_trend_min: float = 0.0):           # 거래량 추세 하한
        """
        Initialize safety filter with configurable thresholds.

        Args:
            sentiment_positive_threshold: Minimum sentiment score for buy
            sentiment_negative_threshold: Maximum sentiment score for sell
            uncertainty_threshold: Maximum allowed price uncertainty
            per_max_threshold: Maximum PER for buy (avoid overvaluation)
            roe_min_threshold: Minimum ROE (%) for buy
            operating_margin_min: Minimum operating margin (%) for buy
            close_position_min: Minimum close position (0~1) for buy
            volume_trend_min: Minimum volume trend for buy
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

        logger.info(f"SafetyFilter initialized with enhanced rules: "
                   f"PER<={self.per_max}, ROE>={self.roe_min}%, "
                   f"OpMargin>={self.operating_margin_min}%, "
                   f"ClosePos>={self.close_position_min}, "
                   f"VolTrend>{self.volume_trend_min}")

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
            'passed': foreign_value > 0,
            'value': foreign_value,
            'threshold': 0
        }
        if foreign_value <= 0:
            conditions.append(f"Foreign net buy not positive: {foreign_value:,}")

        # Check 3: Institutional net buy > 0
        institutional_value = features.get('institutional_net_buy', 0)
        check_details['institutional_net_buy_check'] = {
            'passed': institutional_value > 0,
            'value': institutional_value,
            'threshold': 0
        }
        if institutional_value <= 0:
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
            'passed': price_trend_value > 0,
            'value': price_trend_value,
            'threshold': 0
        }
        if price_trend_value <= 0:
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
            'passed': morning_return_value > 0,
            'value': morning_return_value,
            'threshold': 0
        }
        if morning_return_value <= 0:
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
            'passed': foreign_value < 0,
            'value': foreign_value,
            'threshold': 0
        }
        check_details['institutional_selling_check'] = {
            'passed': institutional_value < 0,
            'value': institutional_value,
            'threshold': 0
        }

        supply_condition = foreign_value < 0 or institutional_value < 0

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
            'passed': price_trend_value < 0,
            'value': price_trend_value,
            'threshold': 0
        }
        trend_condition = price_trend_value < 0

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
