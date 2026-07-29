"""KR-FinBERT Model Wrapper for Korean Financial Sentiment Analysis

Model: snunlp/KR-FinBert-SC (Seoul National University NLP Lab)
Output: 3-class classification (negative, neutral, positive)
"""
import torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from typing import List
import logging

logger = logging.getLogger(__name__)


class KRFinBERTAnalyzer:
    """Korean Financial BERT sentiment analyzer."""

    MODEL_NAME = 'snunlp/KR-FinBert-SC'

    def __init__(self):
        """Initialize KR-FinBERT model and tokenizer."""
        logger.info(f"Loading KR-FinBERT model: {self.MODEL_NAME}")

        try:
            self.tokenizer = AutoTokenizer.from_pretrained(self.MODEL_NAME)
            self.model = AutoModelForSequenceClassification.from_pretrained(self.MODEL_NAME)
            self.model.eval()  # Set to evaluation mode

            # Move to GPU if available
            self.device = 'cuda' if torch.cuda.is_available() else 'cpu'
            self.model.to(self.device)

            logger.info(f"KR-FinBERT loaded successfully (device: {self.device})")

        except Exception as e:
            logger.error(f"Failed to load KR-FinBERT model: {e}")
            raise

    def analyze_single(self, text: str) -> float:
        """
        Analyze sentiment for a single text.

        Args:
            text: Input text (title + summary/content, max 512 tokens)

        Returns:
            float: Sentiment score from -1.0 (negative) to 1.0 (positive)
        """
        if not text or len(text.strip()) == 0:
            return 0.0  # Neutral for empty text

        try:
            # Tokenize input
            inputs = self.tokenizer(
                text,
                return_tensors='pt',
                max_length=512,
                truncation=True,
                padding=True
            )
            inputs = {k: v.to(self.device) for k, v in inputs.items()}

            # Run inference
            with torch.no_grad():
                outputs = self.model(**inputs)
                logits = outputs.logits

            # Convert logits to probabilities
            probs = torch.nn.functional.softmax(logits, dim=1)

            # KR-FinBert-SC classes: [negative, neutral, positive]
            prob_negative = probs[0][0].item()
            prob_neutral = probs[0][1].item()
            prob_positive = probs[0][2].item()

            # Calculate sentiment score: P(positive) - P(negative)
            # Range: -1.0 to 1.0
            sentiment_score = prob_positive - prob_negative

            return round(sentiment_score, 4)

        except Exception as e:
            logger.error(f"Sentiment analysis failed: {e}")
            return 0.0

    def analyze_multiple_time_weighted_with_scores(
        self, articles: List[dict]
    ) -> tuple[float, List[float]]:
        """
        Analyze multiple articles, returning both the aggregate and per-article scores.

        각 기사에 대해 ``analyze_single`` 추론을 정확히 한 번만 수행한 뒤,
        ``analyze_multiple_time_weighted`` 와 동일한 시간가중 공식으로 집계 점수를
        계산한다. 종목별 감성 집계값(Gemini 입력)과 기사별 점수(stock_news 저장)를
        한 번의 추론으로 함께 얻기 위한 메서드로, 중복 추론을 방지하고 집계값이
        기존 구현과 비트 단위로 동일함을 보장한다.

        Args:
            articles: List of article dicts with 'title', 'content'/'summary' keys.
                Ordered from newest to oldest.

        Returns:
            tuple[float, list[float]]: ``(weighted_score, per_article_scores)``.
                ``weighted_score`` 는 시간가중 평균(-1.0 ~ 1.0, 소수 4자리 반올림),
                ``per_article_scores`` 는 입력 순서(newest-first)대로의 기사별 점수.
                입력이 비어 있으면 ``(0.0, [])`` 을 반환한다.
        """
        if not articles:
            return 0.0, []

        scores = []
        for article in articles:
            # Combine title and content/summary
            title = article.get('title', '')
            content = article.get('content', article.get('summary', ''))
            text = f"{title} {content}"

            score = self.analyze_single(text)
            scores.append(score)

        # Time-weighted average: [5, 4, 3, 2, 1] for 5 articles (newest = highest weight)
        weights = list(range(len(scores), 0, -1))
        weighted_score = sum(s * w for s, w in zip(scores, weights)) / sum(weights)

        logger.debug(f"Analyzed {len(scores)} articles → weighted score: {weighted_score:.4f}")

        return round(weighted_score, 4), scores

    def analyze_multiple_time_weighted(self, articles: List[dict]) -> float:
        """
        Analyze multiple articles with time-weighted averaging.

        Recent articles have higher weight (linear decay).

        Note:
            중복 추론을 막고 결과 동일성을 보장하기 위해
            ``analyze_multiple_time_weighted_with_scores`` 에 위임하고 집계값만
            반환한다(반환값은 기존 구현과 비트 단위로 동일).

        Args:
            articles: List of article dicts with 'title', 'content'/'summary' keys
                     Ordered from newest to oldest

        Returns:
            float: Time-weighted sentiment score (-1.0 to 1.0)
        """
        weighted_score, _ = self.analyze_multiple_time_weighted_with_scores(articles)
        return weighted_score

    def analyze_multiple_simple_average(self, articles: List[dict]) -> float:
        """
        Analyze multiple articles with simple averaging (equal weights).

        Args:
            articles: List of article dicts with 'title', 'content'/'summary' keys

        Returns:
            float: Average sentiment score (-1.0 to 1.0)
        """
        if not articles:
            return 0.0

        scores = []
        for article in articles:
            title = article.get('title', '')
            content = article.get('content', article.get('summary', ''))
            text = f"{title} {content}"

            score = self.analyze_single(text)
            scores.append(score)

        avg_score = sum(scores) / len(scores)

        logger.debug(f"Analyzed {len(scores)} articles → avg score: {avg_score:.4f}")

        return round(avg_score, 4)


def score_to_label(score: float) -> str:
    """
    Map a continuous sentiment score to a discrete label.

    프로젝트 전반에서 사용하는 0.3 / -0.3 임계값을 따른다.

    Args:
        score: Sentiment score in [-1.0, 1.0].

    Returns:
        str: 'positive' (score >= 0.3), 'negative' (score <= -0.3), 'neutral' otherwise.
    """
    if score >= 0.3:
        return "positive"
    if score <= -0.3:
        return "negative"
    return "neutral"
