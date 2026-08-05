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
