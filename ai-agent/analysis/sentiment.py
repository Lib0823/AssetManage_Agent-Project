"""Sentiment Analysis Module - Stage 2-2

Two-track sentiment analysis using KR-FinBERT:
- Track 1: Market-wide RSS feed → sentiment_market (for Vue3 dashboard)
- Track 2: Stock-specific crawling → sentiment_score (for Gemini AI input)

Output: 1 feature per stock (sentiment_score)
"""
import pandas as pd
from typing import List, Dict
from datetime import datetime
import logging

from collectors.news_collector import NewsCollector
from models.kr_finbert import KRFinBERTAnalyzer, score_to_label
from analysis.keyword_extractor import extract_keywords

logger = logging.getLogger(__name__)


class SentimentAnalyzer:
    """Sentiment analysis orchestrator using KR-FinBERT."""

    def __init__(self):
        """Initialize sentiment analyzer with KR-FinBERT model."""
        self.kr_finbert = KRFinBERTAnalyzer()
        # Track 1 결과 보관: analyze_stocks 호출 시 갱신되어 caller가 시장 감성을 영속화할 수 있도록 한다
        self.last_market_sentiment: float = 0.0  # 시장 감성점수 (-1.0 ~ 1.0)
        self.last_market_news_count: int = 0  # 시장 전반 분석에 사용된 기사 수
        # Track 2 부산물: 종목별 기사 원문 + 기사별 감성/키워드 (stock_news 영속화용).
        # analyze_stocks 호출마다 초기화되며, 뉴스가 있는 종목만 키로 채워진다.
        self.last_stock_news: Dict[str, List[Dict]] = {}
        logger.info("SentimentAnalyzer initialized")

    async def analyze_stocks(self, stock_codes: List[str]) -> pd.DataFrame:
        """
        Perform sentiment analysis for filtered stocks.

        Track 1(시장 전반) 결과는 self.last_market_sentiment /
        self.last_market_news_count 인스턴스 속성에 저장되어, 호출자가
        market_daily_summary 및 news_analysis(시장 전반 행)에 영속화할 수 있다.

        Args:
            stock_codes: List of stock codes (30 filtered stocks)

        Returns:
            DataFrame with columns: stock_code, sentiment_score, news_count
        """
        logger.info(f"Starting sentiment analysis for {len(stock_codes)} stocks")

        # 종목별 기사 부산물 초기화 (재실행 시 이전 결과 누수 방지)
        self.last_stock_news = {}

        async with NewsCollector() as collector:
            # Track 1: Market sentiment (계산 후 인스턴스에 보관, fallback 으로도 사용)
            market_sentiment, market_news_count = await self._analyze_market_sentiment(collector)
            self.last_market_sentiment = market_sentiment
            self.last_market_news_count = market_news_count
            logger.info(f"Market sentiment will be used as fallback: {market_sentiment:.4f}")

            # Track 2: Stock-specific sentiment (for Gemini)
            stock_sentiments = await self._analyze_stock_specific(
                stock_codes,
                collector,
                fallback_sentiment=market_sentiment
            )

        # Return stock-specific sentiments for Gemini
        return stock_sentiments

    async def _analyze_stock_specific(
        self,
        stock_codes: List[str],
        collector: NewsCollector,
        fallback_sentiment: float = 0.0
    ) -> pd.DataFrame:
        """
        Track 2: Analyze stock-specific news (Naver Finance crawling).

        Args:
            stock_codes: List of stock codes
            collector: NewsCollector instance
            fallback_sentiment: Sentiment score to use when stock news not available

        Returns:
            DataFrame with columns: stock_code, sentiment_score, news_count
            (news_count = 분석에 사용된 기사 수, 종목 뉴스가 없어 시장 감성으로
             대체한 경우 0)
        """
        results = []

        for stock_code in stock_codes:
            try:
                # Collect top 5 recent news for this stock
                articles = await collector.collect_stock_news(stock_code, max_articles=5)

                if not articles:
                    logger.warning(f"No news found for {stock_code}, using market sentiment: {fallback_sentiment:.4f}")
                    sentiment_score = fallback_sentiment
                    news_count = 0  # 종목 뉴스 없음 → 시장 감성 대체, 기사 수 0
                else:
                    # Time-weighted sentiment + per-article scores (single inference pass).
                    # sentiment_score 는 기존 analyze_multiple_time_weighted 와 동일한 값.
                    sentiment_score, per_scores = (
                        self.kr_finbert.analyze_multiple_time_weighted_with_scores(articles)
                    )
                    news_count = len(articles)  # 실제 분석에 사용된 기사 수

                    # stock_news 영속화용 기사별 레코드 구성 (집계값에는 영향 없음)
                    self.last_stock_news[stock_code] = self._build_news_records(
                        articles, per_scores
                    )

                results.append({
                    'stock_code': stock_code,
                    'sentiment_score': sentiment_score,
                    'news_count': news_count
                })

                logger.debug(f"{stock_code}: sentiment_score = {sentiment_score:.4f}, news_count = {news_count}")

            except Exception as e:
                logger.error(f"Sentiment analysis failed for {stock_code}: {e}")
                # Fallback to market sentiment (기사 분석 실패 → 기사 수 0)
                results.append({
                    'stock_code': stock_code,
                    'sentiment_score': fallback_sentiment,
                    'news_count': 0
                })

        df = pd.DataFrame(results)
        logger.info(f"Stock-specific sentiment analysis complete: {len(df)} stocks")

        return df

    def _build_news_records(
        self,
        articles: List[Dict],
        per_scores: List[float]
    ) -> List[Dict]:
        """
        Build per-article persistence records for the stock_news table.

        기사별 감성 점수/라벨/키워드를 부착한 레코드 리스트를 만든다. 집계 감성
        점수에는 영향을 주지 않으며, orchestrator 가 repository.save_stock_news 로
        영속화하는 입력 형태와 1:1 대응한다.

        Args:
            articles: collect_stock_news 가 반환한 기사 dict 리스트
                ({title, content, published, url, source}).
            per_scores: 각 기사에 대응하는 기사별 감성 점수(-1.0 ~ 1.0).
                articles 와 동일 순서/길이를 가정한다.

        Returns:
            List[Dict]: 기사별 레코드 리스트. 각 dict 키는
            {title, summary, url, source, published_at, sentiment_score,
             sentiment_label, keywords}.
        """
        records = []
        for article, score in zip(articles, per_scores):
            title = article.get('title', '')
            content = article.get('content', '')
            records.append({
                'title': title,
                'summary': content,
                'url': article.get('url', ''),
                'source': article.get('source'),
                'published_at': article.get('published'),
                'sentiment_score': score,
                'sentiment_label': score_to_label(score),
                'keywords': extract_keywords(f"{title} {content}"),
            })
        return records

    async def _analyze_market_sentiment(self, collector: NewsCollector) -> tuple[float, int]:
        """
        Track 1: Analyze market-wide sentiment (RSS feeds).

        This is stored separately for Vue3 dashboard visualization.

        Args:
            collector: NewsCollector instance

        Returns:
            tuple[float, int]: (시장 감성점수 (-1.0 ~ 1.0), 분석에 사용된 기사 수)
        """
        try:
            # Collect market news from RSS feeds
            articles = await collector.collect_market_news()

            if not articles:
                logger.warning("No market news collected, using neutral sentiment")
                return 0.0, 0

            # Simple average for market sentiment
            market_score = self.kr_finbert.analyze_multiple_simple_average(articles)

            logger.info(f"Market sentiment score: {market_score:.4f} (from {len(articles)} articles)")

            return market_score, len(articles)

        except Exception as e:
            logger.error(f"Market sentiment analysis failed: {e}")
            return 0.0, 0

    def save_market_sentiment(self, sentiment_score: float, trade_date: datetime):
        """
        Save market-wide sentiment to database (for Vue3 dashboard).

        Deprecated: 실제 영속화는 orchestrator 가 DatabaseRepository
        (update_market_sentiment / save_sentiment_analysis) 를 통해 수행한다.
        본 메서드는 로깅만 담당한다.

        Args:
            sentiment_score: Market sentiment score
            trade_date: Trading date
        """
        logger.info(f"Market sentiment on {trade_date.date()}: {sentiment_score:.4f}")

    def validate_sentiment_scores(self, df: pd.DataFrame) -> Dict[str, bool]:
        """
        Validate sentiment scores for data quality.

        Args:
            df: DataFrame with sentiment_score column

        Returns:
            dict: {validation_metric: is_valid}
        """
        validation_results = {}

        # Check for required column
        validation_results['sentiment_score_exists'] = 'sentiment_score' in df.columns

        if 'sentiment_score' in df.columns and len(df) > 0:
            # Check value range (-1.0 to 1.0)
            validation_results['sentiment_range'] = df['sentiment_score'].between(-1.0, 1.0).all()

            # Check for excessive neutral scores (might indicate collection failure)
            neutral_ratio = (df['sentiment_score'] == 0.0).sum() / len(df)
            validation_results['diverse_sentiments'] = neutral_ratio < 0.8  # Less than 80% neutral

            # Check for NaN values
            validation_results['no_nan'] = not df['sentiment_score'].isna().any()

        return validation_results
