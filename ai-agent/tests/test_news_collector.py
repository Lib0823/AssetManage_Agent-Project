"""Unit tests for collectors.news_collector (Track 1 RSS / Track 2 네이버 종목뉴스).

실 네트워크로 나가지 않도록 NewsCollector.session 을 가짜 세션으로 직접 주입한다
(NewsCollector 는 aiohttp.ClientSession 을 self.session 에 보관하고 그 객체로만
요청하므로, 세션만 교체하면 모든 HTTP 경로가 차단된다).
"""
import asyncio
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch

import aiohttp
import pytest

from collectors import news_collector as news_module
from collectors.news_collector import NewsCollector


class FakeResponse:
    """aiohttp 응답 스텁."""

    def __init__(self, text_data='', json_data=None, status=200, raise_exc=None):
        self.status = status
        self._text = text_data
        self._json = json_data
        self._raise_exc = raise_exc

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    def raise_for_status(self):
        if self._raise_exc is not None:
            raise self._raise_exc

    async def text(self):
        return self._text

    async def json(self, **kwargs):
        if isinstance(self._json, Exception):
            raise self._json
        return self._json


class FakeSession:
    """URL 별로 응답을 돌려주는 aiohttp.ClientSession 스텁."""

    def __init__(self, responses=None, default=None, exc=None):
        self.responses = responses or {}
        self.default = default
        self.exc = exc
        self.requests = []
        self.closed = False

    def get(self, url, **kwargs):
        self.requests.append({'url': url, **kwargs})
        if self.exc is not None:
            raise self.exc
        for key, response in self.responses.items():
            if key in url:
                return response
        if self.default is None:
            raise AssertionError(f'준비되지 않은 URL 요청: {url}')
        return self.default

    async def close(self):
        self.closed = True


def rss_xml(items):
    """<item> 목록으로 최소 RSS 문서를 만든다."""
    body = ''.join(
        f'<item><title>{t}</title><description>{d}</description>'
        f'<pubDate>{p}</pubDate><link>http://example.com/{i}</link></item>'
        for i, (t, d, p) in enumerate(items)
    )
    return f'<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>{body}</channel></rss>'


@pytest.fixture
def collector():
    c = NewsCollector()
    c.session = FakeSession()
    return c


class TestSessionLifecycle:
    async def test_aenter_creates_session_and_aexit_closes_it(self):
        fake = FakeSession()
        with patch.object(news_module.aiohttp, 'ClientSession', MagicMock(return_value=fake)):
            async with NewsCollector() as collector:
                assert collector.session is fake
        assert fake.closed is True

    async def test_fetch_rss_without_session_returns_empty(self):
        collector = NewsCollector()
        # session 미초기화 시 RuntimeError 를 던지지만 같은 try 블록에서 삼켜 [] 를 반환한다.
        assert await collector._fetch_rss_feed('http://x/rss', datetime(2020, 1, 1)) == []

    async def test_collect_stock_news_without_session_returns_empty(self):
        collector = NewsCollector()
        assert await collector.collect_stock_news('005930') == []


class TestFetchRssFeed:
    async def test_parses_entries_after_cutoff(self, collector):
        cutoff = datetime(2026, 6, 1, 0, 0, 0)
        collector.session = FakeSession(default=FakeResponse(text_data=rss_xml([
            ('삼성전자 신고가', '반도체 업황 개선', 'Tue, 02 Jun 2026 09:00:00 +0000'),
            ('오래된 기사', '과거 뉴스', 'Sun, 31 May 2026 09:00:00 +0000'),
        ])))

        articles = await collector._fetch_rss_feed('http://feed/finance', cutoff)

        assert len(articles) == 1
        assert articles[0]['title'] == '삼성전자 신고가'
        assert articles[0]['summary'] == '반도체 업황 개선'
        assert articles[0]['source'] == 'http://feed/finance'
        assert articles[0]['published'] == datetime(2026, 6, 2, 9, 0, 0)

    async def test_summary_truncated_to_200_chars(self, collector):
        long_desc = '가' * 500
        collector.session = FakeSession(default=FakeResponse(text_data=rss_xml([
            ('제목', long_desc, 'Tue, 02 Jun 2026 09:00:00 +0000'),
        ])))

        articles = await collector._fetch_rss_feed('http://feed', datetime(2020, 1, 1))

        assert len(articles[0]['summary']) == 200

    async def test_entry_without_date_is_skipped(self, collector):
        xml = ('<?xml version="1.0"?><rss version="2.0"><channel>'
               '<item><title>날짜없음</title><description>본문</description></item>'
               '</channel></rss>')
        collector.session = FakeSession(default=FakeResponse(text_data=xml))

        assert await collector._fetch_rss_feed('http://feed', datetime(2020, 1, 1)) == []

    async def test_http_error_returns_empty_list(self, collector):
        collector.session = FakeSession(default=FakeResponse(
            status=500,
            raise_exc=aiohttp.ClientResponseError(
                request_info=MagicMock(), history=(), status=500, message='Internal Server Error'
            ),
        ))

        assert await collector._fetch_rss_feed('http://feed', datetime(2020, 1, 1)) == []

    async def test_timeout_returns_empty_list(self, collector):
        collector.session = FakeSession(exc=asyncio.TimeoutError())

        assert await collector._fetch_rss_feed('http://feed', datetime(2020, 1, 1)) == []

    async def test_malformed_xml_returns_empty_list(self, collector):
        collector.session = FakeSession(default=FakeResponse(text_data='not xml at all'))

        assert await collector._fetch_rss_feed('http://feed', datetime(2020, 1, 1)) == []


class TestCollectMarketNews:
    async def test_aggregates_all_rss_sources(self, collector):
        calls = []

        async def fake_fetch(url, cutoff):
            calls.append(url)
            return [{'title': f'기사-{url}', 'summary': '', 'published': datetime(2026, 6, 2), 'source': url}]

        with patch.object(collector, '_fetch_rss_feed', side_effect=fake_fetch):
            articles = await collector.collect_market_news(cutoff_time=datetime(2026, 6, 1))

        assert calls == NewsCollector.RSS_SOURCES
        assert len(articles) == len(NewsCollector.RSS_SOURCES)

    async def test_default_cutoff_is_yesterday_18h(self, collector):
        captured = {}

        async def fake_fetch(url, cutoff):
            captured['cutoff'] = cutoff
            return []

        with patch.object(collector, '_fetch_rss_feed', side_effect=fake_fetch):
            await collector.collect_market_news()

        expected = datetime.now().replace(hour=18, minute=0, second=0, microsecond=0) - timedelta(days=1)
        assert captured['cutoff'] == expected

    async def test_duplicate_titles_removed_across_sources(self, collector):
        async def fake_fetch(url, cutoff):
            return [{
                'title': '똑같은제목으로시작하는기사입니다 그러나 뒷부분이 다름 ' + url,
                'summary': '', 'published': datetime(2026, 6, 2), 'source': url,
            }]

        with patch.object(collector, '_fetch_rss_feed', side_effect=fake_fetch):
            articles = await collector.collect_market_news(cutoff_time=datetime(2026, 6, 1))

        # 제목 앞 20자가 동일하므로 1건만 남는다
        assert len(articles) == 1

    async def test_one_failing_source_does_not_break_others(self, collector):
        async def fake_fetch(url, cutoff):
            if url == NewsCollector.RSS_SOURCES[0]:
                raise RuntimeError('feed down')
            return [{'title': f'ok-{url}', 'summary': '', 'published': datetime(2026, 6, 2), 'source': url}]

        with patch.object(collector, '_fetch_rss_feed', side_effect=fake_fetch):
            articles = await collector.collect_market_news(cutoff_time=datetime(2026, 6, 1))

        assert len(articles) == len(NewsCollector.RSS_SOURCES) - 1


class TestDeduplicateArticles:
    def test_dedup_by_title_prefix(self, collector):
        articles = [
            {'title': '삼성전자 실적 발표 사상 최대 기록 영업이익'},
            {'title': '삼성전자 실적 발표 사상 최대 기록 순이익'},  # 앞 20자 동일
            {'title': 'SK하이닉스 신고가 경신'},
        ]
        result = collector._deduplicate_articles(articles, key_length=20)

        assert len(result) == 2

    def test_articles_without_title_are_dropped(self, collector):
        articles = [{'title': ''}, {'summary': '제목 없음'}, {'title': '정상 기사'}]

        result = collector._deduplicate_articles(articles)

        assert len(result) == 1
        assert result[0]['title'] == '정상 기사'

    def test_key_is_case_insensitive_and_trimmed(self, collector):
        articles = [{'title': '  Samsung Rally  '}, {'title': 'samsung rally'}]

        assert len(collector._deduplicate_articles(articles)) == 1

    def test_custom_key_length(self, collector):
        articles = [{'title': 'AB1'}, {'title': 'AB2'}]

        assert len(collector._deduplicate_articles(articles, key_length=2)) == 1
        assert len(collector._deduplicate_articles(articles, key_length=3)) == 2


def naver_item(article_id='0001', office_id='001', title='제목', body='본문',
               dt='202606141440', office_name='한국경제', url=None):
    item = {
        'title': title,
        'body': body,
        'datetime': dt,
        'officeId': office_id,
        'articleId': article_id,
        'officeName': office_name,
    }
    if url is not None:
        item['mobileNewsUrl'] = url
    return item


class TestCollectStockNews:
    async def test_parses_clusters_into_articles(self, collector):
        payload = [
            {'items': [naver_item('1', title='삼성전자 상승', body='외국인 순매수', dt='202606141440')]},
            {'items': [naver_item('2', title='삼성전자 하락', body='기관 매도', dt='202606141200')]},
        ]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert len(articles) == 2
        assert articles[0]['title'] == '삼성전자 상승'
        assert articles[0]['content'] == '외국인 순매수'
        assert articles[0]['source'] == '한국경제'
        assert articles[0]['url'] == 'https://n.news.naver.com/mnews/article/001/1'
        # 실제 API 가 보내는 12자리(YYYYMMDDHHMM) 가 분 단위까지 정확히 파싱되어야 한다
        # (과거 14:40 → 14:04 로 잘리던 버그, TestDatetimeParsing 참조).
        assert articles[0]['published'] == datetime(2026, 6, 14, 14, 40)

    async def test_requests_expected_url_and_params(self, collector):
        collector.session = FakeSession(default=FakeResponse(json_data=[]))

        await collector.collect_stock_news('000660', max_articles=5)

        req = collector.session.requests[0]
        assert req['url'] == 'https://api.stock.naver.com/news/stock/000660'
        assert req['params'] == {'pageSize': 10, 'page': 1}
        assert 'User-Agent' in req['headers']

    async def test_page_size_scales_with_max_articles(self, collector):
        collector.session = FakeSession(default=FakeResponse(json_data=[]))

        await collector.collect_stock_news('005930', max_articles=20)

        assert collector.session.requests[0]['params']['pageSize'] == 40

    async def test_sorted_newest_first(self, collector):
        payload = [{'items': [
            naver_item('1', title='오래된 기사', dt='202606140900'),
            naver_item('2', title='최신 기사', dt='202606141800'),
            naver_item('3', title='중간 기사', dt='202606141200'),
        ]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert [a['title'] for a in articles] == ['최신 기사', '중간 기사', '오래된 기사']

    async def test_max_articles_cap(self, collector):
        payload = [{'items': [
            naver_item(str(i), title=f'기사 {i}', dt=f'20260614{10 + i:02d}00') for i in range(10)
        ]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930', max_articles=3)

        assert len(articles) == 3

    async def test_duplicate_articles_removed_by_office_and_article_id(self, collector):
        payload = [
            {'items': [naver_item('100', '009', title='중복 기사 A')]},
            {'items': [naver_item('100', '009', title='중복 기사 B')]},
        ]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert len(articles) == 1

    async def test_dedup_falls_back_to_title_when_ids_missing(self, collector):
        payload = [{'items': [
            {'title': '아이디 없는 기사', 'body': 'x', 'datetime': '202606141440'},
            {'title': '아이디 없는 기사', 'body': 'y', 'datetime': '202606141441'},
        ]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert len(articles) == 1

    async def test_items_without_title_are_skipped(self, collector):
        payload = [{'items': [
            naver_item('1', title='', body='본문만'),
            naver_item('2', title='정상 기사'),
        ]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert len(articles) == 1
        assert articles[0]['title'] == '정상 기사'

    async def test_html_entities_unescaped_in_title_and_body(self, collector):
        payload = [{'items': [naver_item('1', title='LG&amp;삼성', body='&lt;속보&gt;')]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert articles[0]['title'] == 'LG&삼성'
        assert articles[0]['content'] == '<속보>'

    async def test_empty_body_triggers_article_fetch(self, collector):
        payload = [{'items': [naver_item('1', title='본문 없는 기사', body='')]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        with patch.object(collector, '_fetch_article_content', return_value='보강된 본문') as mock_fetch:
            articles = await collector.collect_stock_news('005930')

        mock_fetch.assert_awaited_once_with('https://n.news.naver.com/mnews/article/001/1')
        assert articles[0]['content'] == '보강된 본문'

    async def test_content_falls_back_to_title_when_body_and_fetch_empty(self, collector):
        payload = [{'items': [naver_item('1', title='제목뿐', body='')]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        with patch.object(collector, '_fetch_article_content', return_value=''):
            articles = await collector.collect_stock_news('005930')

        assert articles[0]['content'] == '제목뿐'

    async def test_content_truncated_to_200_chars(self, collector):
        payload = [{'items': [naver_item('1', body='나' * 500)]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert len(articles[0]['content']) == 200

    async def test_mobile_news_url_preferred(self, collector):
        payload = [{'items': [naver_item('1', url='https://m.news.naver.com/custom/1')]}]
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert articles[0]['url'] == 'https://m.news.naver.com/custom/1'

    async def test_dict_payload_variant_supported(self, collector):
        payload = {'items': [naver_item('1', title='단일 객체 응답')]}
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        articles = await collector.collect_stock_news('005930')

        assert len(articles) == 1

    @pytest.mark.parametrize('payload', [[], {}, [{'items': []}], [{'noitems': 1}], ['문자열'], None])
    async def test_empty_or_unexpected_payload_returns_empty(self, collector, payload):
        collector.session = FakeSession(default=FakeResponse(json_data=payload))

        assert await collector.collect_stock_news('005930') == []

    async def test_http_error_returns_empty_list(self, collector):
        collector.session = FakeSession(default=FakeResponse(
            status=404,
            raise_exc=aiohttp.ClientResponseError(
                request_info=MagicMock(), history=(), status=404, message='Not Found'
            ),
        ))

        assert await collector.collect_stock_news('999999') == []

    async def test_rate_limited_429_returns_empty_list(self, collector):
        collector.session = FakeSession(default=FakeResponse(
            status=429,
            raise_exc=aiohttp.ClientResponseError(
                request_info=MagicMock(), history=(), status=429, message='Too Many Requests'
            ),
        ))

        assert await collector.collect_stock_news('005930') == []

    async def test_timeout_returns_empty_list(self, collector):
        collector.session = FakeSession(exc=asyncio.TimeoutError())

        assert await collector.collect_stock_news('005930') == []

    async def test_invalid_json_returns_empty_list(self, collector):
        collector.session = FakeSession(default=FakeResponse(json_data=ValueError('bad json')))

        assert await collector.collect_stock_news('005930') == []


class TestFetchArticleContent:
    async def test_extracts_dic_area_text(self, collector):
        html_text = '<html><body><div id="dic_area">본문 <script>x=1</script>내용</div></body></html>'
        collector.session = FakeSession(default=FakeResponse(text_data=html_text))

        content = await collector._fetch_article_content('https://n.news.naver.com/a/1')

        assert '본문' in content and '내용' in content
        assert 'x=1' not in content

    @pytest.mark.parametrize('selector', ['newsct_article', 'news_read'])
    async def test_fallback_selectors(self, collector, selector):
        html_text = f'<html><body><div id="{selector}">폴백 본문</div></body></html>'
        collector.session = FakeSession(default=FakeResponse(text_data=html_text))

        assert await collector._fetch_article_content('https://n.news.naver.com/a/1') == '폴백 본문'

    async def test_legacy_class_selector(self, collector):
        html_text = '<html><body><div class="articleCont">구형 본문</div></body></html>'
        collector.session = FakeSession(default=FakeResponse(text_data=html_text))

        assert await collector._fetch_article_content('https://x/a') == '구형 본문'

    async def test_relative_url_is_absolutized(self, collector):
        collector.session = FakeSession(default=FakeResponse(
            text_data='<div id="dic_area">본문</div>'))

        await collector._fetch_article_content('/item/news_read.naver?code=005930')

        assert collector.session.requests[0]['url'].startswith('https://finance.naver.com/item/')

    async def test_empty_url_returns_empty_string(self, collector):
        assert await collector._fetch_article_content('') == ''

    async def test_no_session_returns_empty_string(self):
        collector = NewsCollector()
        assert await collector._fetch_article_content('https://x/a') == ''

    async def test_no_matching_container_returns_empty_string(self, collector):
        collector.session = FakeSession(default=FakeResponse(text_data='<html><body>없음</body></html>'))

        assert await collector._fetch_article_content('https://x/a') == ''

    async def test_http_error_returns_empty_string(self, collector):
        collector.session = FakeSession(default=FakeResponse(
            raise_exc=aiohttp.ClientResponseError(
                request_info=MagicMock(), history=(), status=500, message='err'
            ),
        ))

        assert await collector._fetch_article_content('https://x/a') == ''


class TestDatetimeParsing:
    @pytest.mark.parametrize('raw,expected', [
        ('20260614144012', datetime(2026, 6, 14, 14, 40, 12)),
        ('20260614', datetime(2026, 6, 14)),
        (' 20260614144012 ', datetime(2026, 6, 14, 14, 40, 12)),
    ])
    def test_parse_naver_datetime_formats(self, collector, raw, expected):
        assert collector._parse_naver_datetime(raw) == expected

    def test_parse_naver_datetime_12digit_minute_is_not_swallowed_by_seconds_format(self, collector):
        # 회귀 방지: 12자리(YYYYMMDDHHMM) 값이 %Y%m%d%H%M%S 에 잘못 매칭되어
        # 분의 마지막 자리가 초로 밀리던 버그 ('202606141440' → 14:04) 를 길이 기반
        # 포맷 확정으로 고쳤다. 14:40 이 정확히 나와야 한다.
        assert collector._parse_naver_datetime('202606141440') == datetime(2026, 6, 14, 14, 40)

    @pytest.mark.parametrize('raw', ['', 'not-a-date', None])
    def test_parse_naver_datetime_falls_back_to_now(self, collector, raw):
        before = datetime.now()
        parsed = collector._parse_naver_datetime(raw)
        assert before <= parsed <= datetime.now()

    def test_legacy_parse_naver_date(self, collector):
        assert collector._parse_naver_date('2026.06.14 14:40') == datetime(2026, 6, 14, 14, 40)
        assert collector._parse_naver_date('2026.06.14') == datetime(2026, 6, 14)

    def test_legacy_parse_naver_date_falls_back_to_now(self, collector):
        before = datetime.now()
        parsed = collector._parse_naver_date('bad')
        assert before <= parsed <= datetime.now()


class TestResolveNewsSource:
    def test_office_name_wins(self, collector):
        assert collector._resolve_news_source(
            {'officeName': '매일경제'}, 'https://www.mk.co.kr/a') == '매일경제'

    def test_falls_back_to_domain_without_www(self, collector):
        assert collector._resolve_news_source({}, 'https://www.hankyung.com/a/1') == 'hankyung.com'

    def test_keeps_non_www_domain(self, collector):
        assert collector._resolve_news_source({}, 'https://n.news.naver.com/a/1') == 'n.news.naver.com'

    def test_falls_back_to_naver_when_no_url(self, collector):
        assert collector._resolve_news_source({}, '') == '네이버'

    def test_blank_office_name_is_ignored(self, collector):
        assert collector._resolve_news_source({'officeName': '   '}, '') == '네이버'
