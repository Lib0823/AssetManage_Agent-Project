"""
pytest tests for ai/gemini_client.py (GeminiClient)

이 모듈의 테스트는 절대 실제 Gemini API 를 호출하지 않는다.
`tests/conftest.py` 의 autouse fixture(`gemini_no_network`)가 설정의 API 키를 비우고
`genai.configure` / `genai.GenerativeModel` 을 스텁으로 대체한다. 스텁 모델의
`generate_content` 는 기본적으로 AssertionError 를 던지므로, 전송 계층을 명시적으로
바꾸지 않은 호출은 조용히 통과하지 않는다.

재시도 경로 테스트는 `ai.gemini_client.time.sleep` 을 패치해 실제로 대기하지 않는다.
"""
import json
import time
from unittest.mock import patch

import pytest

from ai.gemini_client import GeminiClient


class _Resp:
    """`model.generate_content()` 응답 스텁 (필요한 건 .text 뿐)."""

    def __init__(self, text):
        self.text = text


def _market_payload() -> dict:
    return {
        'buy_top3': [
            {'stock_code': '005930', 'reason': 'buy 1'},
            {'stock_code': '000660', 'reason': 'buy 2'},
            {'stock_code': '051910', 'reason': 'buy 3'},
        ],
        'sell_top3': [
            {'stock_code': '005380', 'reason': 'sell 1'},
            {'stock_code': '035420', 'reason': 'sell 2'},
            {'stock_code': '068270', 'reason': 'sell 3'},
        ],
    }


@pytest.fixture
def mock_client():
    """키 없는 mock 모드 클라이언트 (model is None)."""
    client = GeminiClient(api_key=None)
    assert client.model is None, "테스트 클라이언트가 실제 Gemini 모델을 초기화했습니다"
    return client


@pytest.fixture
def stub_client(gemini_no_network):
    """키가 있는 클라이언트 + conftest 가 주입한 스텁 모델."""
    client = GeminiClient(api_key='stub-key')
    assert client.model is gemini_no_network
    return client, gemini_no_network


@pytest.fixture
def no_sleep():
    """재시도/스로틀 대기를 실제로 하지 않도록 time.sleep 패치."""
    with patch('ai.gemini_client.time.sleep') as sleep:
        yield sleep


class TestInitialization:
    """생성자 동작."""

    def test_without_api_key_enters_mock_mode(self, mock_client):
        assert mock_client.api_key is None
        assert mock_client.model is None
        assert mock_client._last_call_ts == 0.0

    def test_with_api_key_builds_model(self, stub_client):
        client, stub = stub_client
        assert client.api_key == 'stub-key'
        assert client.model is stub

    def test_sdk_failure_degrades_to_mock_mode(self):
        """genai 초기화가 실패하면 예외를 올리지 않고 mock 모드로 떨어진다."""
        def _boom(*args, **kwargs):
            raise RuntimeError('sdk init failed')

        with patch('ai.gemini_client.genai.configure', side_effect=_boom):
            client = GeminiClient(api_key='stub-key')

        assert client.model is None

    def test_rest_transport_is_used(self):
        """gRPC DNS 이슈 회피를 위해 REST transport 로 설정한다."""
        with patch('ai.gemini_client.genai.configure') as configure:
            GeminiClient(api_key='stub-key')

        configure.assert_called_once_with(api_key='stub-key', transport='rest')


class TestErrorClassification:
    """재시도 판단용 예외 분류."""

    @pytest.mark.parametrize('message', [
        '429 Too Many Requests',
        'Quota exceeded for quota metric',
        'Rate limit reached for model',
    ])
    def test_rate_limit_detected(self, message):
        assert GeminiClient._is_rate_limit_error(Exception(message)) is True

    def test_non_rate_limit_not_detected(self):
        assert GeminiClient._is_rate_limit_error(Exception('invalid argument')) is False

    def test_resource_exhausted_type_detected(self):
        from ai import gemini_client as module

        if module.ResourceExhausted is None:
            pytest.skip('google.api_core 미설치 환경')
        assert GeminiClient._is_rate_limit_error(module.ResourceExhausted('slow down')) is True

    @pytest.mark.parametrize('message', [
        '503 Service Unavailable',
        '502 Bad Gateway',
        'Deadline Exceeded',
        'request timed out',
        'Could not contact DNS servers',
        'failed to resolve host',
        'connection reset by peer',
        'Network is unreachable',
    ])
    def test_transient_network_detected(self, message):
        assert GeminiClient._is_transient_network_error(Exception(message)) is True

    @pytest.mark.parametrize('exc_name', ['ServiceUnavailable', 'DeadlineExceeded'])
    def test_transient_google_exception_types_detected(self, exc_name):
        from ai import gemini_client as module

        exc_type = getattr(module, exc_name)
        if exc_type is None:
            pytest.skip('google.api_core 미설치 환경')
        assert GeminiClient._is_transient_network_error(exc_type('boom')) is True

    @pytest.mark.parametrize('message', ['invalid api key', 'permission denied'])
    def test_permanent_error_not_treated_as_transient(self, message):
        assert GeminiClient._is_transient_network_error(Exception(message)) is False


class TestThrottle:
    """무료 티어 RPM 보호 스로틀."""

    def test_sleeps_when_called_too_soon(self, stub_client, no_sleep):
        client, _ = stub_client
        client._last_call_ts = time.monotonic()

        client._throttle()

        no_sleep.assert_called_once()
        waited = no_sleep.call_args.args[0]
        assert 0 < waited <= GeminiClient.MIN_CALL_INTERVAL_SEC

    def test_does_not_sleep_when_interval_elapsed(self, stub_client, no_sleep):
        client, _ = stub_client
        client._last_call_ts = time.monotonic() - (GeminiClient.MIN_CALL_INTERVAL_SEC + 1)

        client._throttle()

        no_sleep.assert_not_called()


class TestGenerateText:
    """_generate_text: 스로틀 + 재시도 래퍼."""

    def test_raises_when_model_not_initialized(self, mock_client):
        with pytest.raises(RuntimeError, match='not initialized'):
            mock_client._generate_text('prompt')

    def test_returns_response_text(self, stub_client, no_sleep):
        client, stub = stub_client
        stub.generate_content.side_effect = None
        stub.generate_content.return_value = _Resp('hello')

        assert client._generate_text('prompt') == 'hello'
        stub.generate_content.assert_called_once_with('prompt')
        assert client._last_call_ts > 0

    @pytest.mark.parametrize('text', ['', None])
    def test_empty_response_raises_without_retry(self, stub_client, no_sleep, text):
        """빈 응답은 일시적 오류로 보지 않으므로 재시도 없이 즉시 실패한다."""
        client, stub = stub_client
        stub.generate_content.side_effect = None
        stub.generate_content.return_value = _Resp(text)

        with pytest.raises(ValueError, match='Empty response'):
            client._generate_text('prompt')

        assert stub.generate_content.call_count == 1

    def test_rate_limit_retried_then_succeeds(self, stub_client, no_sleep):
        client, stub = stub_client
        stub.generate_content.side_effect = [Exception('429 quota exceeded'), _Resp('ok')]

        assert client._generate_text('prompt') == 'ok'
        assert stub.generate_content.call_count == 2
        assert GeminiClient.BACKOFF_BASE_SEC in [c.args[0] for c in no_sleep.call_args_list]

    def test_rate_limit_exhausts_retries_and_raises(self, stub_client, no_sleep):
        client, stub = stub_client
        stub.generate_content.side_effect = Exception('429 quota exceeded')

        with pytest.raises(Exception, match='429'):
            client._generate_text('prompt')

        assert stub.generate_content.call_count == GeminiClient.MAX_RETRIES + 1
        backoffs = [c.args[0] for c in no_sleep.call_args_list]
        assert [10, 20, 40] == [b for b in backoffs if b in (10, 20, 40)]

    def test_transient_network_error_retried_with_short_backoff(self, stub_client, no_sleep):
        client, stub = stub_client
        stub.generate_content.side_effect = [Exception('503 Service Unavailable'), _Resp('ok')]

        assert client._generate_text('prompt') == 'ok'
        assert GeminiClient.NETWORK_BACKOFF_BASE_SEC in [c.args[0] for c in no_sleep.call_args_list]

    def test_permanent_error_is_not_retried(self, stub_client, no_sleep):
        client, stub = stub_client
        stub.generate_content.side_effect = Exception('invalid api key')

        with pytest.raises(Exception, match='invalid api key'):
            client._generate_text('prompt')

        assert stub.generate_content.call_count == 1


class TestGenerateDecision:
    """시장 전반 결정 (대시보드용)."""

    def test_mock_mode_returns_mock_without_touching_model(self, mock_client, gemini_no_network):
        decision = mock_client.generate_decision('prompt')

        gemini_no_network.generate_content.assert_not_called()
        assert len(decision['buy_top3']) == 3
        assert decision['buy_top3'][0]['reason'].startswith('[MOCK]')

    def test_valid_response_is_parsed(self, stub_client):
        client, _ = stub_client
        canned = json.dumps(_market_payload())

        with patch.object(GeminiClient, '_generate_text', return_value=canned):
            decision = client.generate_decision('prompt')

        assert [i['stock_code'] for i in decision['sell_top3']] == ['005380', '035420', '068270']

    def test_unparseable_response_raises_runtime_error(self, stub_client):
        client, _ = stub_client

        with patch.object(GeminiClient, '_generate_text', return_value='죄송합니다, JSON 아님'):
            with pytest.raises(RuntimeError, match='Gemini decision generation failed'):
                client.generate_decision('prompt')

    def test_call_failure_raises_runtime_error(self, stub_client):
        client, _ = stub_client

        with patch.object(GeminiClient, '_generate_text', side_effect=TimeoutError('boom')):
            with pytest.raises(RuntimeError, match='Gemini decision generation failed'):
                client.generate_decision('prompt')


class TestGenerateUserDecision:
    """유저별 결정 — 실패해도 예외 대신 빈 결정으로 degrade (실거래 안전)."""

    def test_mock_mode_returns_empty_decision(self, mock_client, gemini_no_network):
        assert mock_client.generate_user_decision('prompt') == {'buy': [], 'sell': []}
        gemini_no_network.generate_content.assert_not_called()

    def test_variable_length_lists_are_parsed(self, stub_client):
        client, _ = stub_client
        canned = json.dumps({
            'buy': [
                {'stock_code': '005930', 'reason': '매수 사유 1'},
                {'stock_code': '000660', 'reason': '매수 사유 2'},
            ],
            'sell': [{'stock_code': '035420', 'reason': '매도 사유'}],
        })

        with patch.object(GeminiClient, '_generate_text', return_value=canned):
            decision = client.generate_user_decision('prompt')

        assert len(decision['buy']) == 2
        assert len(decision['sell']) == 1

    def test_invalid_json_degrades_to_empty(self, stub_client):
        client, _ = stub_client

        with patch.object(GeminiClient, '_generate_text', return_value='not json at all'):
            assert client.generate_user_decision('prompt') == {'buy': [], 'sell': []}

    def test_json_array_response_degrades_to_empty(self, stub_client):
        """최상위가 객체가 아닌 배열이어도 빈 결정으로 흡수된다."""
        client, _ = stub_client

        with patch.object(GeminiClient, '_generate_text', return_value='[1, 2, 3]'):
            assert client.generate_user_decision('prompt') == {'buy': [], 'sell': []}

    def test_call_failure_degrades_to_empty(self, stub_client):
        client, _ = stub_client

        with patch.object(GeminiClient, '_generate_text', side_effect=RuntimeError('quota gone')):
            assert client.generate_user_decision('prompt') == {'buy': [], 'sell': []}


class TestParsing:
    """JSON 추출 및 응답 검증."""

    def test_extract_plain_json(self, mock_client):
        assert mock_client._extract_json('{"a": 1}') == {'a': 1}

    def test_extract_json_from_labeled_fence(self, mock_client):
        text = 'here you go:\n```json\n{"a": 1}\n```\nthanks'
        assert mock_client._extract_json(text) == {'a': 1}

    def test_extract_json_from_bare_fence(self, mock_client):
        text = '```\n{"a": 1}\n```'
        assert mock_client._extract_json(text) == {'a': 1}

    def test_market_response_missing_top_level_key_raises(self, mock_client):
        payload = _market_payload()
        del payload['sell_top3']

        with pytest.raises(ValueError, match='Missing buy_top3 or sell_top3'):
            mock_client._parse_gemini_response(json.dumps(payload))

    def test_market_response_item_missing_reason_raises(self, mock_client):
        payload = _market_payload()
        del payload['buy_top3'][0]['reason']

        with pytest.raises(ValueError, match='stock_code and reason'):
            mock_client._parse_gemini_response(json.dumps(payload))

    def test_market_response_extra_items_raise(self, mock_client):
        payload = _market_payload()
        payload['buy_top3'].append({'stock_code': '105560', 'reason': 'extra'})

        with pytest.raises(ValueError, match='exactly 3 items'):
            mock_client._parse_gemini_response(json.dumps(payload))

    def test_user_decision_drops_incomplete_items(self, mock_client):
        canned = json.dumps({
            'buy': [
                {'stock_code': '005930', 'reason': 'keep'},
                {'stock_code': '000660'},          # reason 없음
                {'reason': 'no code'},             # stock_code 없음
                {'stock_code': '', 'reason': 'x'},  # 빈 코드
                'not a dict',
            ],
            'sell': [],
        })

        decision = mock_client._parse_user_decision(canned)

        assert decision['buy'] == [{'stock_code': '005930', 'reason': 'keep'}]

    def test_user_decision_coerces_and_strips(self, mock_client):
        canned = json.dumps({'buy': [{'stock_code': 5930, 'reason': '  이유  '}]})

        decision = mock_client._parse_user_decision(canned)

        assert decision['buy'] == [{'stock_code': '5930', 'reason': '이유'}]

    def test_user_decision_missing_keys_yield_empty_lists(self, mock_client):
        assert mock_client._parse_user_decision('{}') == {'buy': [], 'sell': []}

    def test_user_decision_non_list_value_yields_empty_list(self, mock_client):
        canned = json.dumps({'buy': {'stock_code': '005930', 'reason': 'dict not list'}, 'sell': []})

        assert mock_client._parse_user_decision(canned)['buy'] == []


class TestConnectionCheck:
    """test_connection 헬퍼."""

    def test_returns_false_without_model(self, mock_client):
        assert mock_client.test_connection() is False

    def test_returns_true_on_successful_call(self, stub_client):
        client, _ = stub_client

        with patch.object(GeminiClient, '_generate_text', return_value='OK'):
            assert client.test_connection() is True

    def test_returns_false_on_failure(self, stub_client):
        client, _ = stub_client

        with patch.object(GeminiClient, '_generate_text', side_effect=RuntimeError('down')):
            assert client.test_connection() is False


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
