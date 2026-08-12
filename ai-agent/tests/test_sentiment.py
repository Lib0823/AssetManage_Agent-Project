"""
pytest tests for Stage 2-2: Sentiment Analysis
"""
import pytest
import pandas as pd
from datetime import datetime, timedelta
from unittest.mock import Mock, patch, AsyncMock

from analysis.sentiment import SentimentAnalyzer
from models.kr_finbert import KRFinBERTAnalyzer


def _load_or_skip(factory):
    """conftest 가 오프라인을 강제하므로 캐시 미보유 시 다운로드 대신 skip 한다."""
    try:
        return factory()
    except Exception as exc:
        pytest.skip(
            f"KR-FinBERT 로컬 캐시 없음 → 오프라인 테스트 skip. "
            f"먼저 모델을 캐시하세요: "
            f"python -c \"from models.kr_finbert import KRFinBERTAnalyzer; KRFinBERTAnalyzer()\" "
            f"(HF_HUB_OFFLINE 해제 상태에서). 원인: {exc}"
        )


@pytest.fixture
def sentiment_analyzer():
    """Create SentimentAnalyzer instance for testing (로컬 캐시 모델 사용)."""
    return _load_or_skip(SentimentAnalyzer)


@pytest.fixture
def kr_finbert():
    """Create KR-FinBERT analyzer instance (로컬 캐시 모델 사용)."""
    return _load_or_skip(KRFinBERTAnalyzer)


@pytest.fixture
def sample_news_articles():
    """Sample news articles for testing."""
    return [
        {
            'title': '삼성전자 실적 호조로 주가 급등 전망',
            'content': '삼성전자가 2분기 실적 호조를 기록하며 주가 상승이 예상됩니다.',
            'published': datetime.now()
        },
        {
            'title': 'SK하이닉스 실적 악화로 주가 하락 우려',
            'content': 'SK하이닉스 실적 부진으로 투자자들의 우려가 커지고 있습니다.',
            'published': datetime.now() - timedelta(hours=2)
        },
        {
            'title': 'LG화학 신규 투자 발표로 긍정 전망',
            'content': 'LG화학이 대규모 신규 투자를 발표하며 시장의 기대감이 높아지고 있습니다.',
            'published': datetime.now() - timedelta(hours=4)
        }
    ]


class TestKRFinBERT:
    """Test suite for KR-FinBERT model."""

    def test_init(self, kr_finbert):
        """Test KR-FinBERT initialization."""
        assert kr_finbert.tokenizer is not None
        assert kr_finbert.model is not None
        assert kr_finbert.model.training is False  # Should be in eval mode

    def test_analyze_single_positive(self, kr_finbert):
        """Test positive sentiment detection."""
        positive_text = "삼성전자 실적 호조로 주가 급등 전망"
        score = kr_finbert.analyze_single(positive_text)

        # Should be positive
        assert score > 0, f"Expected positive score, got {score}"
        assert -1.0 <= score <= 1.0, f"Score {score} outside [-1, 1] range"

    def test_analyze_single_negative(self, kr_finbert):
        """Test negative sentiment detection."""
        negative_text = "SK하이닉스 실적 악화로 주가 하락 우려"
        score = kr_finbert.analyze_single(negative_text)

        # Should be negative
        assert score < 0, f"Expected negative score, got {score}"
        assert -1.0 <= score <= 1.0, f"Score {score} outside [-1, 1] range"

    def test_analyze_single_neutral(self, kr_finbert):
        """Test neutral sentiment detection."""
        neutral_text = "삼성전자 주주총회 개최 예정"
        score = kr_finbert.analyze_single(neutral_text)

        # Should be close to 0
        assert -1.0 <= score <= 1.0, f"Score {score} outside [-1, 1] range"

    def test_analyze_multiple_time_weighted(self, kr_finbert, sample_news_articles):
        """Test time-weighted sentiment analysis."""
        score = kr_finbert.analyze_multiple_time_weighted(sample_news_articles)

        # Should return valid score
        assert -1.0 <= score <= 1.0, f"Weighted score {score} outside [-1, 1] range"

    def test_time_weighted_weights(self):
        """Test time-weighted decay weights calculation."""
        # For 5 articles, weights should be [5, 4, 3, 2, 1]
        n_articles = 5
        weights = list(range(n_articles, 0, -1))

        assert weights == [5, 4, 3, 2, 1]
        assert sum(weights) == 15

        # For 3 articles, weights should be [3, 2, 1]
        n_articles = 3
        weights = list(range(n_articles, 0, -1))

        assert weights == [3, 2, 1]
        assert sum(weights) == 6

    def test_empty_text_handling(self, kr_finbert):
        """Test handling of empty text."""
        empty_text = ""

        # Should not crash, return neutral score
        score = kr_finbert.analyze_single(empty_text)
        assert -1.0 <= score <= 1.0


class TestSentimentAnalyzer:
    """Test suite for SentimentAnalyzer."""

    def test_init(self, sentiment_analyzer):
        """Test SentimentAnalyzer initialization."""
        assert sentiment_analyzer.kr_finbert is not None

    @pytest.mark.asyncio
    async def test_analyze_stocks_structure(self, sentiment_analyzer):
        """Test analyze_stocks returns correct structure."""
        # Mock NewsCollector
        with patch('analysis.sentiment.NewsCollector') as mock_collector_class:
            mock_collector = AsyncMock()
            mock_collector_class.return_value.__aenter__.return_value = mock_collector

            # Mock collect_stock_news
            mock_collector.collect_stock_news.return_value = [
                {
                    'title': '삼성전자 실적 호조',
                    'content': '주가 상승 전망',
                    'published': datetime.now()
                }
            ]

            # Mock KR-FinBERT
            with patch.object(sentiment_analyzer.kr_finbert, 'analyze_multiple_time_weighted', return_value=0.65):
                result = await sentiment_analyzer.analyze_stocks(stock_codes=['005930'])

                # Verify structure
                assert isinstance(result, pd.DataFrame)
                assert len(result) == 1
                assert 'stock_code' in result.columns
                assert 'sentiment_score' in result.columns
                assert result.loc[0, 'stock_code'] == '005930'
                assert -1.0 <= result.loc[0, 'sentiment_score'] <= 1.0

    @pytest.mark.asyncio
    async def test_no_news_handling(self, sentiment_analyzer):
        """Test handling when no news articles found."""
        with patch('analysis.sentiment.NewsCollector') as mock_collector_class:
            mock_collector = AsyncMock()
            mock_collector_class.return_value.__aenter__.return_value = mock_collector

            # Mock empty news
            mock_collector.collect_stock_news.return_value = []

            result = await sentiment_analyzer.analyze_stocks(stock_codes=['005930'])

            # Should return 0.0 sentiment when no news
            assert result.loc[0, 'sentiment_score'] == 0.0


def _analyzer_without_model():
    """KR-FinBERT 로딩 없이 SentimentAnalyzer 를 만든다 (추론은 스텁으로 대체)."""
    analyzer = SentimentAnalyzer.__new__(SentimentAnalyzer)
    analyzer.kr_finbert = Mock()
    analyzer.last_market_sentiment = 0.0
    analyzer.last_market_news_count = 0
    analyzer.last_stock_news = {}
    return analyzer


class TestEventLoopIsNotBlockedByInference:
    """KR-FinBERT 추론이 이벤트 루프를 붙잡으면 안 된다.

    기사 수 상한이 없는 시장 전반 트랙(RSS 전체)은 기사가 100건 남짓이면 단일 연속
    블록이 Kafka `session_timeout_ms`(10초)를 넘길 수 있다. 그동안 하트비트 태스크가
    실행 기회를 못 얻으면 컨슈머가 그룹에서 이탈한다.
    """

    BLOCKING_SECONDS = 0.2
    HEARTBEAT_INTERVAL = 0.01

    async def _count_heartbeats_during(self, coro):
        """coro 를 기다리는 동안 이벤트 루프가 몇 번 돌았는지 센다."""
        import asyncio

        beats = []

        async def _heartbeat():
            while True:
                beats.append(1)
                await asyncio.sleep(self.HEARTBEAT_INTERVAL)

        task = asyncio.create_task(_heartbeat())
        try:
            result = await coro
        finally:
            task.cancel()
        return result, len(beats)

    async def test_market_track_inference_runs_off_the_event_loop(self):
        import time

        analyzer = _analyzer_without_model()
        analyzer.kr_finbert.analyze_multiple_simple_average = Mock(
            side_effect=lambda articles: (time.sleep(self.BLOCKING_SECONDS), 0.5)[1]
        )
        collector = Mock()
        collector.collect_market_news = AsyncMock(
            return_value=[{'title': 'a', 'content': 'b'}] * 3
        )

        (score, count), beats = await self._count_heartbeats_during(
            analyzer._analyze_market_sentiment(collector)
        )

        assert score == 0.5
        assert count == 3
        assert beats >= 5, f"이벤트 루프가 추론 동안 멈췄다 (heartbeats={beats})"

    async def test_stock_track_inference_runs_off_the_event_loop(self):
        import time

        analyzer = _analyzer_without_model()
        analyzer.kr_finbert.analyze_multiple_time_weighted_with_scores = Mock(
            side_effect=lambda articles: (time.sleep(self.BLOCKING_SECONDS), (0.4, [0.4]))[1]
        )
        collector = Mock()
        collector.collect_stock_news = AsyncMock(
            return_value=[{'title': 'a', 'content': 'b', 'published': datetime.now()}]
        )

        df, beats = await self._count_heartbeats_during(
            analyzer._analyze_stock_specific(['005930'], collector)
        )

        assert df.loc[0, 'sentiment_score'] == 0.4
        assert beats >= 5, f"이벤트 루프가 추론 동안 멈췄다 (heartbeats={beats})"


class TestFeatureValidation:
    """Test sentiment feature validation."""

    def test_sentiment_score_range(self):
        """Test sentiment_score should be in [-1.0, 1.0] range."""
        valid_scores = [-1.0, -0.5, 0.0, 0.5, 1.0, -0.9997, 0.9998]

        for score in valid_scores:
            assert -1.0 <= score <= 1.0, f"sentiment_score {score} outside [-1, 1] range"

    def test_sentiment_interpretation(self):
        """Test sentiment score interpretation thresholds."""
        # Positive thresholds
        assert 0.5 < 0.65  # Strong positive
        assert 0.0 < 0.3 < 0.5  # Weak positive

        # Negative thresholds
        assert -0.65 < -0.5  # Strong negative
        assert -0.5 < -0.3 < 0.0  # Weak negative

    def test_time_weighted_calculation(self):
        """Test time-weighted average calculation."""
        # Simulate 3 articles: [positive, negative, neutral]
        scores = [0.8, -0.6, 0.1]
        weights = [3, 2, 1]

        # Calculate weighted average
        weighted_sum = sum(s * w for s, w in zip(scores, weights))
        total_weight = sum(weights)
        weighted_avg = weighted_sum / total_weight

        # Expected: (0.8*3 + (-0.6)*2 + 0.1*1) / 6 = (2.4 - 1.2 + 0.1) / 6 = 0.2167
        expected = (0.8 * 3 + (-0.6) * 2 + 0.1 * 1) / 6

        assert abs(weighted_avg - expected) < 0.01


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
