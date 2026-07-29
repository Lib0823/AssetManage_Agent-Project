"""Lightweight keyword extraction for stock news tags.

순수 표준 라이브러리(`re`, `collections.Counter`)만 사용하는 결정적(deterministic)
키워드 추출기. 한국어 형태소 분석기(konlpy/mecab/kiwipiepy)나 JVM 의존성을 도입하지
않기 위해, 정규식 토큰화 + 금융 도메인 불용어 제거 + 빈도 기반 랭킹으로 동작한다.

뉴스 기사 제목/본문에서 태그로 쓸 만한 명사성 토큰을 빈도순으로 추출하며,
KR-FinBERT 같은 무거운 모델이나 네트워크 호출 없이 즉시 동작한다.
"""
import re
from collections import Counter
from typing import Optional

# 금융 뉴스 전반에 흔하지만 태그로서의 변별력이 낮은 일반어 집합(~50개).
# 의미 있는 명사(반도체, 배당, 인수, 신제품 등)는 의도적으로 제외해 과도한
# 제거(over-strip)를 피한다.
KOREAN_FINANCE_STOPWORDS = {
    # 시장/가격 일반어
    "주가", "상승", "하락", "강세", "약세", "급등", "급락", "보합", "반등",
    "투자", "종목", "시장", "증시", "코스피", "코스닥", "지수", "거래", "공시",
    "전망", "분석", "상장", "영업", "관련", "대비", "기록", "발표", "예정",
    "확대", "축소", "이날", "지난", "최근", "이번", "대표", "사업", "기업",
    "회사", "오늘", "내일", "기자", "뉴스", "보도", "마감", "출발", "장중",
    "원화", "달러", "수준", "기준", "경우", "가능", "전일", "당일", "올해",
}

# 한글 2글자 이상 / 라틴(영문·티커) 2글자 이상 토큰
_TOKEN_PATTERN = re.compile(r"[가-힣]{2,}|[A-Za-z]{2,}")


def extract_keywords(
    text: str,
    top_n: int = 4,
    stopwords: Optional[set] = None,
) -> list:
    """Extract top keyword tags from a piece of text.

    한국어/라틴 토큰을 정규식으로 추출하고, 금융 도메인 불용어를 제거한 뒤
    빈도순으로 랭킹해 상위 ``top_n`` 개의 고유 토큰을 반환한다. 동률일 경우 더 긴
    토큰을 우선하며(더 구체적인 명사일 가능성), 결정적(deterministic)으로 동작한다.

    Args:
        text: 키워드를 추출할 원문 텍스트(보통 ``제목 + ' ' + 본문``).
        top_n: 반환할 최대 키워드 개수. 0 이하이면 빈 리스트를 반환한다.
        stopwords: 사용자 지정 불용어 집합. ``None`` 이면 내장 금융 불용어 집합
            (:data:`KOREAN_FINANCE_STOPWORDS`)을 사용한다.

    Returns:
        빈도 내림차순(동률 시 길이 내림차순)으로 정렬된 고유 키워드 문자열 리스트.
        입력이 비어 있거나 후보 토큰이 없으면 빈 리스트를 반환한다.

    Examples:
        >>> extract_keywords("삼성전자 반도체 실적 반도체 호조", top_n=2)
        ['반도체', '실적']
    """
    if not text or top_n <= 0:
        return []

    active_stopwords = KOREAN_FINANCE_STOPWORDS if stopwords is None else stopwords

    counts: Counter = Counter()
    for raw_token in _TOKEN_PATTERN.findall(text):
        # 라틴 토큰은 소문자 정규화(티커/영문 표기 흔들림 방지), 한글은 그대로 둔다.
        token = raw_token.lower() if raw_token.isascii() else raw_token

        if token in active_stopwords:
            continue
        counts[token] += 1

    if not counts:
        return []

    # 1차 빈도 내림차순, 2차 토큰 길이 내림차순, 3차 사전순(완전 결정성 보장)
    ranked = sorted(
        counts.items(),
        key=lambda item: (-item[1], -len(item[0]), item[0]),
    )

    return [token for token, _ in ranked[:top_n]]
