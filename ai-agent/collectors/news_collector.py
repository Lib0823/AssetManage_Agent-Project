"""News Collection Module - RSS Feed + Stock-Specific Crawling

Two-track approach:
- Track 1: Market-wide RSS feed (for Vue3 dashboard visualization)
- Track 2: Stock-specific news via Naver mobile stock-news JSON API (for Gemini AI input)
"""
import asyncio
import html
import aiohttp
import feedparser
from bs4 import BeautifulSoup
from datetime import datetime, timedelta
from typing import List, Dict, Optional
from urllib.parse import urlparse
import logging

logger = logging.getLogger(__name__)


class NewsCollector:
    """Collect news from RSS feeds and Naver stock-news API for sentiment analysis."""

    # Track 1: Market-wide RSS sources
    RSS_SOURCES = [
        'https://www.hankyung.com/feed/finance',
        'https://www.mk.co.kr/rss/30000001/',
        'https://www.yonhapnews.co.kr/rss/economy.xml'
    ]

    # Track 2: Naver Finance per-stock news JSON API.
    # 과거 구현이 쓰던 https://finance.naver.com/item/news_news.nhn 는 폐기되어
    # (.nhn→.naver 마이그레이션 + frameset 화) 종목 뉴스를 반환하지 않는다.
    # 현재 네이버 증권 모바일/웹이 실제로 사용하는 JSON 엔드포인트로 교체한다.
    # 응답: [{ "total": N, "items": [{title, body, datetime(YYYYMMDDHHMM),
    #          officeId, articleId, mobileNewsUrl, ...}] }, ...] (클러스터 배열).
    NAVER_STOCK_NEWS_API = 'https://api.stock.naver.com/news/stock/{code}'

    # 종목 뉴스 API/기사 본문 요청에 사용할 공통 헤더 (브라우저 UA 미설정 시 차단/빈 응답 가능)
    _REQUEST_HEADERS = {
        'User-Agent': (
            'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
            'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
        ),
        'Referer': 'https://m.stock.naver.com/'
    }

    def __init__(self):
        self.session: Optional[aiohttp.ClientSession] = None

    async def __aenter__(self):
        self.session = aiohttp.ClientSession()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb):
        if self.session:
            await self.session.close()

    async def collect_market_news(self, cutoff_time: Optional[datetime] = None) -> List[Dict]:
        """
        Track 1: Collect market-wide news from RSS feeds.

        Args:
            cutoff_time: Only include news after this time (default: yesterday 18:00)

        Returns:
            List of articles: [{title, summary, published, source}]
        """
        if cutoff_time is None:
            # Default: yesterday 18:00 KST
            cutoff_time = datetime.now().replace(hour=18, minute=0, second=0, microsecond=0) - timedelta(days=1)

        all_articles = []

        for rss_url in self.RSS_SOURCES:
            try:
                articles = await self._fetch_rss_feed(rss_url, cutoff_time)
                all_articles.extend(articles)
                logger.info(f"Collected {len(articles)} articles from {rss_url}")

            except Exception as e:
                logger.error(f"Failed to fetch RSS feed {rss_url}: {e}")

        # Remove duplicates by title prefix (first 20 chars)
        unique_articles = self._deduplicate_articles(all_articles, key_length=20)

        logger.info(f"Total market news collected: {len(unique_articles)} (after deduplication)")
        return unique_articles

    async def _fetch_rss_feed(self, rss_url: str, cutoff_time: datetime) -> List[Dict]:
        """
        Fetch and parse RSS feed.

        Args:
            rss_url: RSS feed URL
            cutoff_time: Minimum publish time

        Returns:
            List of articles
        """
        try:
            if self.session is None:
                raise RuntimeError("Session not initialized. Use 'async with' context manager.")

            async with self.session.get(rss_url, timeout=aiohttp.ClientTimeout(total=15)) as response:
                response.raise_for_status()
                xml_content = await response.text()

            feed = feedparser.parse(xml_content)
            articles = []

            for entry in feed.entries:
                try:
                    # Parse publication time
                    if hasattr(entry, 'published_parsed') and entry.published_parsed:
                        pub_time = datetime(*entry.published_parsed[:6])
                    elif hasattr(entry, 'updated_parsed') and entry.updated_parsed:
                        pub_time = datetime(*entry.updated_parsed[:6])
                    else:
                        continue  # Skip if no time information

                    # Filter by cutoff time
                    if pub_time < cutoff_time:
                        continue

                    # Extract summary/description
                    summary = ''
                    if hasattr(entry, 'summary'):
                        summary = entry.summary[:200]  # First 200 chars
                    elif hasattr(entry, 'description'):
                        summary = entry.description[:200]

                    articles.append({
                        'title': entry.title if hasattr(entry, 'title') else '',
                        'summary': summary,
                        'published': pub_time,
                        'source': rss_url
                    })

                except Exception as e:
                    logger.warning(f"Failed to parse RSS entry: {e}")

            return articles

        except Exception as e:
            logger.error(f"RSS fetch failed for {rss_url}: {e}")
            return []

    def _deduplicate_articles(self, articles: List[Dict], key_length: int = 20) -> List[Dict]:
        """
        Remove duplicate articles based on title prefix.

        Args:
            articles: List of article dicts
            key_length: Length of title prefix to use as deduplication key

        Returns:
            Deduplicated list of articles
        """
        seen_keys = set()
        unique_articles = []

        for article in articles:
            title = article.get('title', '')
            key = title[:key_length].lower().strip()

            if key and key not in seen_keys:
                seen_keys.add(key)
                unique_articles.append(article)

        return unique_articles

    async def collect_stock_news(self, stock_code: str, max_articles: int = 5) -> List[Dict]:
        """
        Track 2: Collect stock-specific news via Naver Finance stock-news JSON API.

        구 구현(news_news.nhn HTML 스크래핑)은 엔드포인트 폐기로 항상 빈 결과를
        반환했다. 본 구현은 네이버 증권이 실제 사용하는 JSON API
        (api.stock.naver.com/news/stock/{code})를 호출한다. 응답은 뉴스 클러스터
        배열이며, 각 클러스터의 items[] 가 개별 기사다. 기사 본문(body) 일부가
        응답에 포함되어 있어 추가 요청 없이도 감성 분석 입력을 구성할 수 있고,
        mobileNewsUrl 로 전체 본문을 선택적으로 보강한다.

        Args:
            stock_code: 6-digit stock code
            max_articles: Maximum number of articles to collect

        Returns:
            List of articles: [{title, content, published, url, source}] (newest first)
        """
        try:
            if self.session is None:
                raise RuntimeError("Session not initialized. Use 'async with' context manager.")

            url = self.NAVER_STOCK_NEWS_API.format(code=stock_code)
            params = {'pageSize': max(max_articles * 2, 10), 'page': 1}

            async with self.session.get(
                url,
                params=params,
                headers=self._REQUEST_HEADERS,
                timeout=aiohttp.ClientTimeout(total=15)
            ) as response:
                response.raise_for_status()
                payload = await response.json(content_type=None)

            # payload: 뉴스 클러스터 배열. 각 cluster['items'] 가 개별 기사 목록.
            raw_items: List[Dict] = []
            if isinstance(payload, list):
                for cluster in payload:
                    if isinstance(cluster, dict):
                        raw_items.extend(cluster.get('items', []) or [])
            elif isinstance(payload, dict):
                # 방어적: 단일 객체 형태로 내려오는 변형 응답도 허용
                raw_items.extend(payload.get('items', []) or [])

            articles: List[Dict] = []
            seen_keys = set()

            for item in raw_items:
                try:
                    title = html.unescape((item.get('title') or item.get('titleFull') or '').strip())
                    if not title:
                        continue

                    # 중복 기사(클러스터 간 동일 기사) 제거: officeId+articleId 우선, 없으면 제목
                    office_id = str(item.get('officeId', '')).strip()
                    article_id = str(item.get('articleId', '')).strip()
                    dedup_key = f'{office_id}:{article_id}' if office_id and article_id else title[:30]
                    if dedup_key in seen_keys:
                        continue
                    seen_keys.add(dedup_key)

                    published = self._parse_naver_datetime(str(item.get('datetime', '')))

                    # 기사 URL: 응답의 mobileNewsUrl 우선, 없으면 officeId/articleId 로 구성
                    link = item.get('mobileNewsUrl') or ''
                    if not link and office_id and article_id:
                        link = f'https://n.news.naver.com/mnews/article/{office_id}/{article_id}'

                    # 본문: 응답 body(요약) 사용. 비어 있으면 기사 페이지에서 보강.
                    body = html.unescape((item.get('body') or '').strip())
                    if not body and link:
                        body = await self._fetch_article_content(link)

                    content = (body or title)[:200]

                    # 출처(언론사): 응답 officeName 우선, 없으면 기사 URL 도메인,
                    # 그래도 없으면 '네이버' 로 폴백.
                    source = self._resolve_news_source(item, link)

                    articles.append({
                        'title': title,
                        'content': content,
                        'published': published,
                        'url': link,
                        'source': source
                    })

                    if len(articles) >= max_articles:
                        break

                except Exception as e:
                    logger.warning(f"Failed to parse news item for {stock_code}: {e}")

            # 최신순 정렬 (datetime 내림차순) — 시간가중 감성 분석이 newest-first 를 가정
            articles.sort(key=lambda a: a['published'], reverse=True)

            logger.info(f"Collected {len(articles)} stock-specific news for {stock_code}")
            return articles

        except Exception as e:
            logger.error(f"Stock news collection failed for {stock_code}: {e}")
            return []

    async def _fetch_article_content(self, url: str) -> str:
        """
        Fetch full article content from a Naver news article URL.

        새 listing 이 돌려주는 URL 형태(n.news.naver.com/mnews/article/{office}/{article})와
        구 finance.naver.com 상대경로를 모두 처리한다. 본문이 비어 있을 때만 호출되는
        보강용 경로이므로 실패 시 빈 문자열을 반환한다(상위에서 제목으로 폴백).

        Args:
            url: Article URL (absolute or relative)

        Returns:
            Article content text (empty string on failure)
        """
        try:
            if not url:
                return ''

            # Convert legacy relative URL to absolute
            if url.startswith('/'):
                url = f'https://finance.naver.com{url}'

            if self.session is None:
                return ''

            async with self.session.get(
                url,
                headers=self._REQUEST_HEADERS,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                response.raise_for_status()
                html_text = await response.text()

            soup = BeautifulSoup(html_text, 'html.parser')

            # 신형 네이버 뉴스(n.news.naver.com) 본문 컨테이너 우선, 구형 fallback
            article_body = (
                soup.select_one('#dic_area')
                or soup.select_one('#newsct_article')
                or soup.select_one('#news_read')
                or soup.select_one('.articleCont')
            )

            if article_body:
                # Remove scripts and styles
                for tag in article_body(['script', 'style']):
                    tag.decompose()

                text = article_body.get_text(separator=' ', strip=True)
                return text

            return ''

        except Exception as e:
            logger.warning(f"Failed to fetch article content from {url}: {e}")
            return ''

    def _parse_naver_datetime(self, datetime_str: str) -> datetime:
        """
        Parse Naver stock-news API datetime (YYYYMMDDHHMM) to datetime.

        Args:
            datetime_str: e.g. '202606141440'

        Returns:
            datetime object (current time if parsing fails)
        """
        cleaned = (datetime_str or '').strip()
        for fmt in ('%Y%m%d%H%M%S', '%Y%m%d%H%M', '%Y%m%d'):
            try:
                return datetime.strptime(cleaned, fmt)
            except ValueError:
                continue
        return datetime.now()

    def _resolve_news_source(self, item: Dict, url: str) -> str:
        """
        Resolve the news source (언론사) for a Naver stock-news item.

        우선순위: 응답의 officeName(언론사명) → 기사 URL 도메인 → '네이버'.

        Args:
            item: Naver stock-news API item dict.
            url: Resolved article URL (may be empty).

        Returns:
            str: Source/publisher name (never empty).
        """
        office_name = (item.get('officeName') or '').strip()
        if office_name:
            return office_name

        if url:
            try:
                netloc = urlparse(url).netloc
                if netloc:
                    # 'www.' 접두 제거로 도메인 가독성 향상
                    return netloc[4:] if netloc.startswith('www.') else netloc
            except Exception:
                pass

        return '네이버'

    def _parse_naver_date(self, date_str: str) -> datetime:
        """
        Legacy date parser (YYYY.MM.DD HH:MM). 하위 호환용으로 유지.

        Args:
            date_str: Date string from Naver

        Returns:
            datetime object
        """
        try:
            return datetime.strptime(date_str, '%Y.%m.%d %H:%M')
        except ValueError:
            try:
                return datetime.strptime(date_str, '%Y.%m.%d')
            except ValueError:
                return datetime.now()
