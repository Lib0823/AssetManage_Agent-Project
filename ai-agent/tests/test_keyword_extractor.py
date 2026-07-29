"""Unit tests for the lightweight keyword extractor and sentiment label helper.

These tests are pure-stdlib: they do NOT load KR-FinBERT, hit the network, or
touch the database.
"""
import pytest

from analysis.keyword_extractor import extract_keywords, KOREAN_FINANCE_STOPWORDS
from models.kr_finbert import score_to_label


class TestExtractKeywords:
    """Tests for extract_keywords."""

    def test_frequency_ranking(self):
        """가장 자주 등장한 토큰이 앞에 온다."""
        text = "반도체 반도체 반도체 배당 배당 인수"
        result = extract_keywords(text, top_n=3)
        assert result == ["반도체", "배당", "인수"]

    def test_stopword_removal(self):
        """내장 금융 불용어는 결과에서 제외된다."""
        text = "주가 주가 시장 시장 반도체"
        result = extract_keywords(text, top_n=5)
        assert "주가" not in result
        assert "시장" not in result
        assert "반도체" in result

    def test_top_n_limit(self):
        """top_n 개수만큼만 반환한다."""
        text = "반도체 배당 인수 신제품 합병 실적"
        result = extract_keywords(text, top_n=2)
        assert len(result) == 2

    def test_korean_and_latin_tokens(self):
        """한글 토큰과 라틴/티커 토큰을 모두 추출한다(라틴은 소문자 정규화)."""
        text = "삼성전자 SK SK AI 신제품"
        result = extract_keywords(text, top_n=5)
        assert "삼성전자" in result
        assert "sk" in result  # 소문자 정규화 + 빈도 2
        assert "ai" in result

    def test_tie_break_longer_token_first(self):
        """동률 빈도에서는 더 긴 토큰을 우선한다."""
        # 두 토큰 모두 빈도 1 → 더 긴 '반도체장비'가 먼저
        text = "반도체장비 배당"
        result = extract_keywords(text, top_n=2)
        assert result[0] == "반도체장비"

    def test_unique_tokens(self):
        """반환 키워드는 중복 없이 고유하다."""
        text = "배당 배당 배당 인수 인수"
        result = extract_keywords(text, top_n=4)
        assert len(result) == len(set(result))

    def test_single_char_tokens_ignored(self):
        """1글자 토큰은 후보에서 제외된다(정규식 2글자 이상)."""
        text = "이 가 반도체"
        result = extract_keywords(text, top_n=5)
        assert result == ["반도체"]

    def test_empty_input(self):
        """빈 문자열은 빈 리스트를 반환한다."""
        assert extract_keywords("", top_n=4) == []

    def test_none_like_whitespace(self):
        """공백/기호만 있으면 빈 리스트를 반환한다."""
        assert extract_keywords("   !!! ??? ", top_n=4) == []

    def test_zero_top_n(self):
        """top_n 이 0 이하이면 빈 리스트를 반환한다."""
        assert extract_keywords("반도체 배당", top_n=0) == []

    def test_custom_stopwords(self):
        """사용자 지정 불용어 집합이 내장 집합을 대체한다."""
        text = "반도체 배당 인수"
        result = extract_keywords(text, top_n=5, stopwords={"반도체"})
        assert "반도체" not in result
        # 내장 불용어였던 토큰은 custom 집합 사용 시 제거되지 않음
        assert "배당" in result and "인수" in result

    def test_deterministic(self):
        """같은 입력에 대해 항상 같은 결과를 낸다."""
        text = "반도체 배당 인수 배당 반도체 신제품"
        first = extract_keywords(text, top_n=4)
        second = extract_keywords(text, top_n=4)
        assert first == second

    def test_builtin_stopwords_nonempty(self):
        """내장 불용어 집합이 합리적 크기로 구성되어 있다."""
        assert len(KOREAN_FINANCE_STOPWORDS) >= 30
        # 의미 있는 명사는 불용어에 포함하지 않는다
        assert "반도체" not in KOREAN_FINANCE_STOPWORDS
        assert "배당" not in KOREAN_FINANCE_STOPWORDS


class TestScoreToLabel:
    """Tests for score_to_label thresholds (0.3 / -0.3)."""

    def test_positive(self):
        assert score_to_label(0.3) == "positive"
        assert score_to_label(0.9) == "positive"

    def test_negative(self):
        assert score_to_label(-0.3) == "negative"
        assert score_to_label(-1.0) == "negative"

    def test_neutral(self):
        assert score_to_label(0.0) == "neutral"
        assert score_to_label(0.29) == "neutral"
        assert score_to_label(-0.29) == "neutral"

    def test_boundary_inclusive(self):
        """경계값 0.3 / -0.3 은 각각 positive / negative 에 포함된다."""
        assert score_to_label(0.3) == "positive"
        assert score_to_label(-0.3) == "negative"
