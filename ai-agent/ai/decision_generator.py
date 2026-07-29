"""Trading Decision Generator - Stage 4

Integrates 11 features from Stages 2-1, 2-2, 2-3 and generates trading decisions using Gemini AI.

Features:
- Quantitative (7): morning_return, close_position, foreign_net_buy, institutional_net_buy, per, roe, operating_margin
- Sentiment (1): sentiment_score
- Time-Series (3): prophet_price_trend, prophet_volume_trend, prophet_price_uncertainty
"""
import pandas as pd
from typing import Dict, List
from datetime import datetime
import logging

from ai.gemini_client import GeminiClient

logger = logging.getLogger(__name__)


class TradingDecisionGenerator:
    """Generate AI-powered trading decisions using Gemini."""

    def __init__(self):
        """Initialize decision generator with Gemini client."""
        self.gemini_client = GeminiClient()
        logger.info("TradingDecisionGenerator initialized")

    def generate_decisions(
        self,
        quant_features: pd.DataFrame,
        sentiment_features: pd.DataFrame,
        timeseries_features: pd.DataFrame
    ) -> Dict:
        """
        Generate TOP3 buy/sell decisions using Gemini AI.

        Args:
            quant_features: DataFrame with 7 quantitative features
            sentiment_features: DataFrame with sentiment_score
            timeseries_features: DataFrame with 3 time-series features

        Returns:
            dict: {
                'buy_top3': [{'stock_code': str, 'reason': str}, ...],
                'sell_top3': [{'stock_code': str, 'reason': str}, ...]
            }
        """
        logger.info("Generating trading decisions with Gemini AI...")

        # 1. Merge all features into single DataFrame
        all_features_df = self._merge_all_features(quant_features, sentiment_features, timeseries_features)

        # 2. Build context for each stock (11 features)
        stock_contexts = self._build_stock_contexts(all_features_df)

        # 3. Generate Gemini prompt
        prompt = self._generate_gemini_prompt(stock_contexts)

        # 4. Call Gemini API
        try:
            decision = self.gemini_client.generate_decision(prompt)
            logger.info("Trading decision generated successfully")
            return decision

        except Exception as e:
            logger.error(f"Failed to generate decision: {e}")
            raise

    def _merge_all_features(
        self,
        quant_df: pd.DataFrame,
        sentiment_df: pd.DataFrame,
        timeseries_df: pd.DataFrame
    ) -> pd.DataFrame:
        """
        Merge all feature DataFrames on stock_code.

        Args:
            quant_df: Quantitative features (7)
            sentiment_df: Sentiment features (1)
            timeseries_df: Time-series features (3)

        Returns:
            DataFrame with all 11 features per stock
        """
        # Merge quantitative and sentiment
        merged = pd.merge(quant_df, sentiment_df, on='stock_code', how='left')

        # Merge time-series
        merged = pd.merge(merged, timeseries_df, on='stock_code', how='left')

        logger.info(f"Merged features for {len(merged)} stocks with 11 features each")

        return merged

    def _build_stock_contexts(self, df: pd.DataFrame) -> List[str]:
        """
        Build context string for each stock with 11 features.

        Args:
            df: DataFrame with all 11 features

        Returns:
            List of context strings (one per stock)
        """
        contexts = []

        for _, row in df.iterrows():
            stock_code = row['stock_code']

            context = f"""
[종목: {stock_code}]

정량 분석 (KIS 기반):
- 장초반 수익률: {row['morning_return']:.2f}%
- 종가 위치 (고저 범위 내): {row['close_position']:.2f}
- 외국인 순매수: {row['foreign_net_buy']:,}원
- 기관 순매수: {row['institutional_net_buy']:,}원

정량 분석 (DART 기반):
- PER: {self._format_per(row['per'])}
- ROE: {self._format_pct(row['roe'])}
- 영업이익률: {self._format_pct(row['operating_margin'])}

감성 분석:
- 뉴스 감성 점수: {row['sentiment_score']:.2f} (범위: -1.0 ~ 1.0)

시계열 예측:
- 가격 추세 (D+1~D+5): {row['prophet_price_trend']:.4f} (양수: 상승, 음수: 하락)
- 거래량 추세 (매수 비율): {row['prophet_volume_trend']:.4f} (양수: 매수세 강화)
- 가격 예측 불확실성: {row['prophet_price_uncertainty']:.2f}

"""
            contexts.append(context)

        return contexts

    def _format_per(self, per_value) -> str:
        """
        Format PER value (handle None for loss-making companies).

        Args:
            per_value: PER value or None

        Returns:
            str: Formatted PER string
        """
        if pd.isna(per_value) or per_value is None:
            return "적자 또는 결측"
        else:
            return f"{per_value:.2f}"

    def _format_pct(self, value) -> str:
        """
        Format a percentage feature (ROE, 영업이익률), handling None/NaN for missing DART data.

        Args:
            value: Numeric value or None/NaN (재무데이터 결측 시)

        Returns:
            str: Formatted "{value:.2f}%" or "결측" when data is missing
        """
        if value is None or pd.isna(value):
            return "결측"
        else:
            return f"{value:.2f}%"

    def _generate_gemini_prompt(self, stock_contexts: List[str]) -> str:
        """
        Generate complete prompt for Gemini AI.

        Args:
            stock_contexts: List of context strings (30 stocks)

        Returns:
            str: Complete Gemini prompt
        """
        contexts_text = '\n'.join(stock_contexts)

        prompt = f"""
당신은 한국 주식 시장의 AI 트레이딩 어드바이저입니다.
아래 30개 종목의 11개 피처를 분석하여 매수/매도 결정을 내려주세요.

## 분석 종목 (30개)
{contexts_text}

## 판단 기준
1. **수급 흐름**: 외국인·기관 순매수가 동시에 양수면 강한 매수 신호
2. **가격 모멘텀**: morning_return, close_position이 높으면 단기 강세
3. **펀더멘탈**: PER 낮고 ROE·영업이익률 높으면 중장기 매력
4. **뉴스 심리**: sentiment_score가 0.5 이상이면 호재 집중
5. **추세 방향**: prophet_price_trend 양수이고 prophet_volume_trend도 양수면 상승 기조
6. **불확실성**: prophet_price_uncertainty가 크면 판단 보류 고려

## 출력 형식 (JSON)
{{
  "buy_top3": [
    {{"stock_code": "005930", "reason": "외국인·기관 동시 순매수 + 가격 상승 추세 + 긍정 뉴스"}},
    {{"stock_code": "000660", "reason": "..."}},
    {{"stock_code": "051910", "reason": "..."}}
  ],
  "sell_top3": [
    {{"stock_code": "005380", "reason": "외국인·기관 동시 순매도 + 가격 하락 추세 + 부정 뉴스"}},
    {{"stock_code": "035420", "reason": "..."}},
    {{"stock_code": "068270", "reason": "..."}}
  ]
}}

**중요**: 반드시 JSON 형식으로만 답변하고, 설명은 reason 필드에 포함하세요.
각 매수/매도 결정은 정확히 3개씩이어야 합니다.
"""

        return prompt

    # ------------------------------------------------------------------
    # Per-user portfolio-aware decision (Stage 6, multi-user)
    # ------------------------------------------------------------------
    def generate_user_decision(
        self,
        candidate_features: pd.DataFrame,
        portfolio: Dict,
        order_amount: int,
        max_holdings: int,
        user_id: int
    ) -> Dict:
        """
        한 사용자의 포트폴리오 맥락을 반영한 맞춤 매수/매도 결정 (유저당 Gemini 1콜).

        Args:
            candidate_features: 분석 유니버스 11피처 (매수 후보 풀)
            portfolio: InternalApiClient.get_user_portfolio() 결과
                       (holdings/cash/total_assets 포함)
            order_amount: 1회 주문 한도(원)
            max_holdings: 최대 보유 종목수
            user_id: 로깅용 사용자 id

        Returns:
            {'buy': [{'stock_code', 'reason'}, ...], 'sell': [{'stock_code', 'reason'}, ...]}
            (가변 길이, 판단 실패 시 빈 리스트)
        """
        logger.info(f"Generating per-user decision for user {user_id}...")

        candidate_contexts = self._build_stock_contexts(candidate_features)
        portfolio_context = self._build_portfolio_context(portfolio, order_amount, max_holdings)
        prompt = self._generate_user_prompt(candidate_contexts, portfolio_context)

        return self.gemini_client.generate_user_decision(prompt)

    def _build_portfolio_context(self, portfolio: Dict, order_amount: int, max_holdings: int) -> str:
        """사용자 포트폴리오/자금 상황을 프롬프트용 문자열로 구성."""
        holdings = portfolio.get('holdings', [])
        cash = portfolio.get('cash', 0)
        total_assets = portfolio.get('total_assets', 0)

        if holdings:
            lines = []
            for h in holdings:
                lines.append(
                    f"- [{h['stock_code']} {h['stock_name']}] 수량 {h['quantity']:,}주, "
                    f"매입단가 {h['avg_price']:,.0f}원, 현재가 {h['current_price']:,.0f}원, "
                    f"평가금액 {h['eval_amount']:,.0f}원, 손익률 {h['profit_loss_rate']:+.2f}%, "
                    f"총자산 대비 비중 {h['weight_pct']:.2f}%"
                )
            holdings_text = '\n'.join(lines)
        else:
            holdings_text = "- (보유 종목 없음)"

        return f"""주문가능현금: {cash:,.0f}원
총자산(보유평가 + 현금): {total_assets:,.0f}원
1회 주문 한도(order_amount): {order_amount:,}원
최대 보유 종목수(max_holdings): {max_holdings}
현재 보유 종목 수: {len(holdings)}

보유 종목:
{holdings_text}"""

    def _generate_user_prompt(self, candidate_contexts: List[str], portfolio_context: str) -> str:
        """유저 맞춤 매수/매도 프롬프트 생성."""
        contexts_text = '\n'.join(candidate_contexts)

        return f"""당신은 한국 주식 시장의 AI 트레이딩 어드바이저입니다.
아래 분석 후보 종목들과 '이 사용자'의 포트폴리오/자금 상황을 함께 고려하여,
이 사용자에게 맞는 매수/매도를 결정하세요.

## 분석 후보 종목 (11개 피처)
{contexts_text}

## 이 사용자의 포트폴리오 / 자금
{portfolio_context}

## 매수 판단 시 반드시 고려
1. 가용 현금이 부족하면 매수하지 않는다(주문가능현금 한도 내).
2. 이미 보유 중인 종목은 신규 매수에서 제외한다(비중 과다 종목도 회피).
3. 보유 종목과 같은/유사 종목으로의 집중을 피하고 분산을 고려한다.
4. 최대 보유 종목수(max_holdings)를 초과하지 않도록 신규 매수 수를 제한한다.
5. 1회 주문 한도(order_amount) 안에서 매수 가능한 종목만 고른다.

## 매도 판단
- 매도는 '보유 종목' 중에서만 한다(분석 후보가 아니라 보유 목록 기준).
- 추세 하락 / 부정적 뉴스 심리 / 펀더멘탈 악화 / 비중 과다 등을 근거로 한다.
- 매도할 이유가 없으면 매도하지 않아도 된다(빈 리스트 허용).

## 출력 형식 (JSON) — 매수/매도는 각각 0개 이상 가변
{{
  "buy": [
    {{"stock_code": "005930", "reason": "현금 충분 + 미보유 + 외국인·기관 순매수 + 상승 추세"}}
  ],
  "sell": [
    {{"stock_code": "000660", "reason": "비중 과다 + 가격 하락 추세 + 부정 뉴스"}}
  ]
}}

**중요**: 반드시 JSON 형식으로만 답변하세요. buy 의 stock_code 는 분석 후보 중에서,
sell 의 stock_code 는 위 '보유 종목' 중에서만 선택하세요. 근거가 약하면 빈 리스트로 두세요.
"""

    def validate_decision(self, decision: Dict) -> Dict[str, bool]:
        """
        Validate generated decision for completeness and correctness.

        Args:
            decision: Decision dictionary from Gemini

        Returns:
            dict: {validation_metric: is_valid}
        """
        validation_results = {}

        # Check structure
        validation_results['has_buy_top3'] = 'buy_top3' in decision
        validation_results['has_sell_top3'] = 'sell_top3' in decision

        if 'buy_top3' in decision and 'sell_top3' in decision:
            # Check count
            validation_results['buy_count_correct'] = len(decision['buy_top3']) == 3
            validation_results['sell_count_correct'] = len(decision['sell_top3']) == 3

            # Check each item has required fields
            buy_valid = all('stock_code' in item and 'reason' in item for item in decision['buy_top3'])
            sell_valid = all('stock_code' in item and 'reason' in item for item in decision['sell_top3'])

            validation_results['buy_items_valid'] = buy_valid
            validation_results['sell_items_valid'] = sell_valid

            # Check for unique stock codes (no duplicates)
            buy_codes = [item['stock_code'] for item in decision['buy_top3']]
            sell_codes = [item['stock_code'] for item in decision['sell_top3']]

            validation_results['buy_unique'] = len(buy_codes) == len(set(buy_codes))
            validation_results['sell_unique'] = len(sell_codes) == len(set(sell_codes))

            # Check for overlap between buy and sell (should not buy AND sell same stock)
            overlap = set(buy_codes) & set(sell_codes)
            validation_results['no_overlap'] = len(overlap) == 0

            if overlap:
                logger.warning(f"Buy/Sell overlap detected: {overlap}")

        return validation_results
