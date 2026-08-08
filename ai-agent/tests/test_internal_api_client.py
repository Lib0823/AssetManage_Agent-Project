"""Unit tests for collectors.internal_api_client (ai-agent → api-server 채널).

모든 HTTP 호출은 aiohttp.ClientSession 을 가짜 객체로 대체해 네트워크로 나가지
않는다. InternalApiClient 의 계약은 "실패 시 빈 결과로 degrade" 이므로,
정상 경로뿐 아니라 HTTP 4xx/5xx·타임아웃·깨진 페이로드 경로를 함께 검증한다.
"""
import pytest
from unittest.mock import MagicMock, patch

import aiohttp

from collectors import internal_api_client as internal_module
from collectors.internal_api_client import InternalApiClient, _to_float


class FakeResponse:
    """aiohttp 응답 스텁 (async context manager)."""

    def __init__(self, status=200, json_data=None, json_exc=None):
        self.status = status
        self._json_data = json_data
        self._json_exc = json_exc

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def json(self, **kwargs):
        if self._json_exc is not None:
            raise self._json_exc
        return self._json_data


class FakeSession:
    """aiohttp.ClientSession 스텁. 호출 인자를 기록한다."""

    def __init__(self, response=None, exc=None):
        self.response = response
        self.exc = exc
        self.requests = []
        self.closed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        self.closed = True
        return False

    def _handle(self, method, url, kwargs):
        self.requests.append({'method': method, 'url': url, **kwargs})
        if self.exc is not None:
            raise self.exc
        return self.response

    def get(self, url, **kwargs):
        return self._handle('GET', url, kwargs)

    def post(self, url, **kwargs):
        return self._handle('POST', url, kwargs)


def patch_session(session):
    """aiohttp.ClientSession(...) 이 주어진 가짜 세션을 돌려주도록 패치."""
    return patch.object(
        internal_module.aiohttp, 'ClientSession', MagicMock(return_value=session)
    )


@pytest.fixture
def client():
    return InternalApiClient(base_url='http://api-server:7070/', api_key='secret-key')


class TestInit:
    def test_base_url_trailing_slash_stripped(self):
        c = InternalApiClient(base_url='http://api-server:7070/', api_key='k')
        assert c.base_url == 'http://api-server:7070'

    def test_headers_contain_internal_key(self, client):
        assert client._headers == {'X-Internal-Api-Key': 'secret-key'}

    def test_headers_with_none_key_is_empty_string(self):
        c = InternalApiClient(base_url='http://x', api_key=None)
        assert c._headers == {'X-Internal-Api-Key': ''}

    def test_timeout_is_client_timeout(self):
        c = InternalApiClient(base_url='http://x', api_key='k', timeout=7)
        assert isinstance(c.timeout, aiohttp.ClientTimeout)
        assert c.timeout.total == 7


class TestGetActiveAutoTradingUsers:
    async def test_returns_user_list(self, client):
        session = FakeSession(FakeResponse(200, {
            'success': True,
            'data': [
                {'user_id': 1, 'kis_account_id': 10, 'order_amount': 100000,
                 'max_holdings': 5, 'order_type': 'MARKET'},
                {'user_id': 2, 'kis_account_id': 11, 'order_amount': 50000,
                 'max_holdings': 3, 'order_type': 'LIMIT'},
            ]
        }))
        with patch_session(session):
            users = await client.get_active_auto_trading_users()

        assert len(users) == 2
        assert users[0]['user_id'] == 1
        assert session.requests[0]['url'] == 'http://api-server:7070/api/internal/auto-trading/users'
        assert session.requests[0]['headers'] == {'X-Internal-Api-Key': 'secret-key'}

    @pytest.mark.parametrize('status', [401, 403, 404, 429, 500, 503])
    async def test_non_200_returns_empty_list(self, client, status):
        session = FakeSession(FakeResponse(status, {'message': 'nope'}))
        with patch_session(session):
            assert await client.get_active_auto_trading_users() == []

    @pytest.mark.parametrize('body', [None, {}, {'data': None}, {'data': []}])
    async def test_missing_data_field_returns_empty_list(self, client, body):
        session = FakeSession(FakeResponse(200, body))
        with patch_session(session):
            assert await client.get_active_auto_trading_users() == []

    async def test_timeout_returns_empty_list(self, client):
        import asyncio
        session = FakeSession(exc=asyncio.TimeoutError())
        with patch_session(session):
            assert await client.get_active_auto_trading_users() == []

    async def test_json_decode_error_returns_empty_list(self, client):
        session = FakeSession(FakeResponse(200, json_exc=ValueError('bad json')))
        with patch_session(session):
            assert await client.get_active_auto_trading_users() == []


class TestGetUserHoldings:
    async def test_returns_stock_codes(self, client):
        session = FakeSession(FakeResponse(200, {
            'data': {'holdings': [
                {'stockCode': '005930'},
                {'stockCode': '000660'},
            ]}
        }))
        with patch_session(session):
            codes = await client.get_user_holdings(42)

        assert codes == ['005930', '000660']
        assert session.requests[0]['url'] == 'http://api-server:7070/api/internal/users/42/holdings'

    async def test_entries_without_stock_code_are_dropped(self, client):
        session = FakeSession(FakeResponse(200, {
            'data': {'holdings': [
                {'stockCode': '005930'},
                {'stockCode': ''},
                {'stockName': '이름만'},
                {'stockCode': None},
            ]}
        }))
        with patch_session(session):
            assert await client.get_user_holdings(1) == ['005930']

    @pytest.mark.parametrize('body', [None, {}, {'data': {}}, {'data': {'holdings': None}}])
    async def test_empty_payload_returns_empty_list(self, client, body):
        session = FakeSession(FakeResponse(200, body))
        with patch_session(session):
            assert await client.get_user_holdings(1) == []

    async def test_non_200_returns_empty_list(self, client):
        session = FakeSession(FakeResponse(500, {}))
        with patch_session(session):
            assert await client.get_user_holdings(1) == []

    async def test_client_error_returns_empty_list(self, client):
        session = FakeSession(exc=aiohttp.ClientError('boom'))
        with patch_session(session):
            assert await client.get_user_holdings(1) == []


EMPTY_PORTFOLIO = {
    'holdings': [], 'cash': 0.0, 'total_eval': 0.0,
    'total_assets': 0.0, 'holding_codes': [],
}


class TestGetUserPortfolio:
    async def test_maps_holdings_and_computes_weights(self, client):
        session = FakeSession(FakeResponse(200, {
            'data': {
                'cashBalance': '1,000,000',
                'totalEvaluationAmount': '3,000,000',
                'holdings': [
                    {
                        'stockCode': '005930', 'stockName': '삼성전자',
                        'holdingQuantity': '10', 'availableQuantity': '10',
                        'averagePrice': '70,000', 'currentPrice': '80,000',
                        'evaluationAmount': '800,000', 'profitLossRate': '14.28',
                    },
                    {
                        'stockCode': '000660', 'stockName': 'SK하이닉스',
                        'holdingQuantity': 20, 'availableQuantity': 15,
                        'averagePrice': 100000, 'currentPrice': 110000,
                        'evaluationAmount': 2200000, 'profitLossRate': 10.0,
                    },
                ],
            }
        }))
        with patch_session(session):
            portfolio = await client.get_user_portfolio(7)

        assert portfolio['cash'] == 1_000_000.0
        assert portfolio['total_eval'] == 3_000_000.0
        assert portfolio['total_assets'] == 4_000_000.0
        assert portfolio['holding_codes'] == ['005930', '000660']

        first = portfolio['holdings'][0]
        assert first['stock_code'] == '005930'
        assert first['stock_name'] == '삼성전자'
        assert first['quantity'] == 10
        assert first['avg_price'] == 70000.0
        assert first['eval_amount'] == 800_000.0
        # 800,000 / 4,000,000 * 100 = 20.0
        assert first['weight_pct'] == 20.0
        assert portfolio['holdings'][1]['weight_pct'] == 55.0

    async def test_stock_name_falls_back_to_code(self, client):
        session = FakeSession(FakeResponse(200, {
            'data': {
                'cashBalance': 0,
                'totalEvaluationAmount': 0,
                'holdings': [{'stockCode': '005930', 'evaluationAmount': 0}],
            }
        }))
        with patch_session(session):
            portfolio = await client.get_user_portfolio(1)

        holding = portfolio['holdings'][0]
        assert holding['stock_name'] == '005930'
        # total_assets == 0 → 0 나눗셈 방지로 weight_pct 0.0
        assert holding['weight_pct'] == 0.0

    async def test_holding_without_stock_code_is_skipped(self, client):
        session = FakeSession(FakeResponse(200, {
            'data': {
                'cashBalance': 100,
                'totalEvaluationAmount': 100,
                'holdings': [{'stockName': '코드없음', 'evaluationAmount': 100}],
            }
        }))
        with patch_session(session):
            portfolio = await client.get_user_portfolio(1)

        assert portfolio['holdings'] == []
        assert portfolio['holding_codes'] == []
        assert portfolio['total_assets'] == 200.0

    async def test_non_200_returns_empty_portfolio(self, client):
        session = FakeSession(FakeResponse(503, {}))
        with patch_session(session):
            assert await client.get_user_portfolio(1) == EMPTY_PORTFOLIO

    async def test_network_error_returns_empty_portfolio(self, client):
        session = FakeSession(exc=aiohttp.ClientConnectionError('refused'))
        with patch_session(session):
            assert await client.get_user_portfolio(1) == EMPTY_PORTFOLIO

    async def test_missing_data_key_yields_zeroed_portfolio(self, client):
        session = FakeSession(FakeResponse(200, {'success': True}))
        with patch_session(session):
            portfolio = await client.get_user_portfolio(1)

        assert portfolio == EMPTY_PORTFOLIO


class TestTradeExecution:
    async def test_execute_buy_posts_expected_payload(self, client):
        session = FakeSession(FakeResponse(200, {'success': True, 'data': {'orderNo': 'A1'}}))
        with patch_session(session):
            result = await client.execute_buy(3, '005930', '삼성전자', 10, 70000)

        assert result == {'success': True, 'data': {'orderNo': 'A1'}}
        req = session.requests[0]
        assert req['method'] == 'POST'
        assert req['url'] == 'http://api-server:7070/api/internal/users/3/trades/buy'
        assert req['json'] == {
            'stock_code': '005930', 'stock_name': '삼성전자',
            'quantity': 10, 'price': 70000,
        }

    async def test_execute_sell_uses_sell_path(self, client):
        session = FakeSession(FakeResponse(200, {'success': True, 'data': None}))
        with patch_session(session):
            result = await client.execute_sell(3, '005930', '삼성전자', 5)

        assert result['success'] is True
        assert session.requests[0]['url'].endswith('/trades/sell')
        # price 미지정 시 0 으로 전송 (시장가)
        assert session.requests[0]['json']['price'] == 0

    async def test_quantity_coerced_to_int(self, client):
        session = FakeSession(FakeResponse(200, {'success': True}))
        with patch_session(session):
            await client.execute_buy(1, '005930', '삼성전자', 3.9, 0)

        assert session.requests[0]['json']['quantity'] == 3

    async def test_http_error_returns_failure_with_message(self, client):
        session = FakeSession(FakeResponse(400, {'success': False, 'message': '주문 수량 오류'}))
        with patch_session(session):
            result = await client.execute_buy(1, '005930', '삼성전자', 0)

        assert result == {'success': False, 'error': '주문 수량 오류'}

    async def test_http_error_without_message_uses_status(self, client):
        session = FakeSession(FakeResponse(500, {}))
        with patch_session(session):
            result = await client.execute_sell(1, '005930', '삼성전자', 1)

        assert result == {'success': False, 'error': 'HTTP 500'}

    async def test_200_with_success_false_is_failure(self, client):
        session = FakeSession(FakeResponse(200, {'success': False, 'message': '잔고 부족'}))
        with patch_session(session):
            result = await client.execute_buy(1, '005930', '삼성전자', 1)

        assert result == {'success': False, 'error': '잔고 부족'}

    async def test_exception_returns_failure_with_error_text(self, client):
        session = FakeSession(exc=aiohttp.ClientError('connection reset'))
        with patch_session(session):
            result = await client.execute_buy(1, '005930', '삼성전자', 1)

        assert result['success'] is False
        assert 'connection reset' in result['error']


class TestToFloat:
    @pytest.mark.parametrize('value,expected', [
        (None, 0.0),
        ('', 0.0),
        ('   ', 0.0),
        ('1,234,567', 1234567.0),
        (' 1,234.56 ', 1234.56),
        ('abc', 0.0),
        (10, 10.0),
        (10.5, 10.5),
        (-3, -3.0),
        ([], 0.0),
    ])
    def test_conversion(self, value, expected):
        assert _to_float(value) == expected
