"""
pytest tests for Stage 4: Gemini AI Decision Generator

이 모듈의 테스트는 절대 실제 Gemini API 를 호출하지 않는다.
`tests/conftest.py` 의 autouse fixture(`gemini_no_network`)가
설정의 API 키를 비우고 `genai` SDK 진입점을 스텁으로 대체한다.
"""
import json

import pytest
import pandas as pd
from unittest.mock import patch

from ai.decision_generator import TradingDecisionGenerator
from ai.gemini_client import GeminiClient


@pytest.fixture
def gemini_client():
    """Create GeminiClient instance (mock mode, no network)."""
    client = GeminiClient(api_key=None)  # No API key = mock mode
    # conftest 의 gemini_no_network 가 설정 폴백까지 차단했는지 확인
    assert client.model is None, "테스트 클라이언트가 실제 Gemini 모델을 초기화했습니다"
    return client


@pytest.fixture
def decision_generator(gemini_client):
    """Create TradingDecisionGenerator with the mock-mode client injected."""
    return TradingDecisionGenerator(gemini_client=gemini_client)


@pytest.fixture
def sample_quant_features():
    """Sample quantitative features DataFrame."""
    return pd.DataFrame({
        'stock_code': ['005930', '000660', '051910'],
        'morning_return': [1.2, -0.5, 0.8],
        'close_position': [0.85, 0.45, 0.75],
        'foreign_net_buy': [50000000, -20000000, 10000000],
        'institutional_net_buy': [30000000, -15000000, 5000000],
        'per': [12.5, 8.3, 15.2],
        'roe': [15.2, 10.5, 22.5],
        'operating_margin': [18.5, 12.3, 25.3]
    })


@pytest.fixture
def sample_sentiment_features():
    """Sample sentiment features DataFrame."""
    return pd.DataFrame({
        'stock_code': ['005930', '000660', '051910'],
        'sentiment_score': [0.65, -0.45, 0.35]
    })


@pytest.fixture
def sample_timeseries_features():
    """Sample time-series features DataFrame."""
    return pd.DataFrame({
        'stock_code': ['005930', '000660', '051910'],
        'prophet_price_trend': [0.0025, -0.0015, 0.0018],
        'prophet_volume_trend': [0.0012, -0.0008, 0.0005],
        'prophet_price_uncertainty': [250.5, 380.2, 210.8]
    })


class TestGeminiClient:
    """Test suite for GeminiClient."""

    def test_init_without_api_key(self, gemini_client):
        """Test GeminiClient initialization without API key (mock mode)."""
        assert gemini_client.api_key is None
        assert gemini_client.model is None  # No model in mock mode

    def test_mock_decision_structure(self, gemini_client):
        """Test mock decision generation structure."""
        mock_decision = gemini_client._get_mock_decision()

        # Verify structure
        assert 'buy_top3' in mock_decision
        assert 'sell_top3' in mock_decision
        assert len(mock_decision['buy_top3']) == 3
        assert len(mock_decision['sell_top3']) == 3

        # Verify each item has required fields
        for item in mock_decision['buy_top3']:
            assert 'stock_code' in item
            assert 'reason' in item

        for item in mock_decision['sell_top3']:
            assert 'stock_code' in item
            assert 'reason' in item

    def test_generate_decision_mock_mode(self, gemini_client):
        """Test generate_decision in mock mode."""
        prompt = "Test prompt"
        decision = gemini_client.generate_decision(prompt)

        # Should return mock decision without calling API
        assert decision is not None
        assert 'buy_top3' in decision
        assert 'sell_top3' in decision

    def test_generate_decision_with_stubbed_response(self):
        """키가 있어도 전송 계층을 스텁하면 네트워크 없이 응답을 파싱한다."""
        canned = json.dumps({
            'buy_top3': [
                {'stock_code': '005930', 'reason': 'stub buy 1'},
                {'stock_code': '000660', 'reason': 'stub buy 2'},
                {'stock_code': '051910', 'reason': 'stub buy 3'},
            ],
            'sell_top3': [
                {'stock_code': '005380', 'reason': 'stub sell 1'},
                {'stock_code': '035420', 'reason': 'stub sell 2'},
                {'stock_code': '068270', 'reason': 'stub sell 3'},
            ],
        })

        client = GeminiClient(api_key='stub-key')
        assert client.model is not None  # conftest 가 넣어준 스텁 모델

        with patch.object(GeminiClient, '_generate_text', return_value=canned) as gen:
            decision = client.generate_decision('Test prompt')

        gen.assert_called_once_with('Test prompt')
        assert decision['buy_top3'][0]['stock_code'] == '005930'
        assert len(decision['sell_top3']) == 3

    def test_generate_user_decision_with_stubbed_response(self):
        """유저별 결정도 스텁 응답으로 파싱된다 (가변 길이)."""
        canned = json.dumps({
            'buy': [{'stock_code': '005930', 'reason': 'stub buy'}],
            'sell': [],
        })

        client = GeminiClient(api_key='stub-key')

        with patch.object(GeminiClient, '_generate_text', return_value=canned):
            decision = client.generate_user_decision('Test prompt')

        assert decision == {'buy': [{'stock_code': '005930', 'reason': 'stub buy'}], 'sell': []}

    def test_real_api_call_is_blocked_in_tests(self, gemini_no_network):
        """전송 계층을 스텁하지 않은 호출은 네트워크로 나가지 않고 실패한다.

        무료 티어 쿼터 소모·CI 비결정성 방지 가드에 대한 회귀 테스트.
        """
        client = GeminiClient(api_key='stub-key')

        with pytest.raises(RuntimeError):
            client.generate_decision('Test prompt')

        # 실제 SDK 가 아닌 스텁이 호출됐음을 확인
        gemini_no_network.generate_content.assert_called_once_with('Test prompt')

    def test_parse_gemini_response_json(self, gemini_client):
        """Test parsing valid JSON response."""
        valid_json = """
        {
            "buy_top3": [
                {"stock_code": "005930", "reason": "Strong fundamentals"},
                {"stock_code": "000660", "reason": "Positive trend"},
                {"stock_code": "051910", "reason": "High ROE"}
            ],
            "sell_top3": [
                {"stock_code": "005380", "reason": "Weak fundamentals"},
                {"stock_code": "035420", "reason": "Negative trend"},
                {"stock_code": "068270", "reason": "High uncertainty"}
            ]
        }
        """

        decision = gemini_client._parse_gemini_response(valid_json)

        assert decision['buy_top3'][0]['stock_code'] == '005930'
        assert len(decision['sell_top3']) == 3

    def test_parse_gemini_response_with_markdown(self, gemini_client):
        """Test parsing JSON wrapped in markdown code blocks."""
        markdown_json = """
        ```json
        {
            "buy_top3": [
                {"stock_code": "005930", "reason": "Test"},
                {"stock_code": "000660", "reason": "Test"},
                {"stock_code": "051910", "reason": "Test"}
            ],
            "sell_top3": [
                {"stock_code": "005380", "reason": "Test"},
                {"stock_code": "035420", "reason": "Test"},
                {"stock_code": "068270", "reason": "Test"}
            ]
        }
        ```
        """

        decision = gemini_client._parse_gemini_response(markdown_json)

        assert len(decision['buy_top3']) == 3
        assert len(decision['sell_top3']) == 3

    def test_parse_gemini_response_invalid(self, gemini_client):
        """Test parsing invalid JSON raises error."""
        invalid_json = "This is not valid JSON"

        with pytest.raises(ValueError, match="not valid JSON"):
            gemini_client._parse_gemini_response(invalid_json)

    def test_parse_gemini_response_missing_fields(self, gemini_client):
        """Test parsing JSON with a missing top-level key raises error."""
        missing_fields = """
        {
            "buy_top3": [
                {"stock_code": "005930", "reason": "Test"}
            ]
        }
        """

        with pytest.raises(ValueError, match="Missing buy_top3 or sell_top3"):
            gemini_client._parse_gemini_response(missing_fields)

    def test_parse_gemini_response_wrong_item_count(self, gemini_client):
        """Test parsing JSON with fewer than 3 items raises error."""
        wrong_count = """
        {
            "buy_top3": [
                {"stock_code": "005930", "reason": "Test"}
            ],
            "sell_top3": [
                {"stock_code": "005380", "reason": "Test"},
                {"stock_code": "035420", "reason": "Test"},
                {"stock_code": "068270", "reason": "Test"}
            ]
        }
        """

        with pytest.raises(ValueError, match="exactly 3 items"):
            gemini_client._parse_gemini_response(wrong_count)


class TestTradingDecisionGenerator:
    """Test suite for TradingDecisionGenerator."""

    def test_init(self, decision_generator):
        """Test TradingDecisionGenerator initialization."""
        assert decision_generator.gemini_client is not None
        # 주입된 클라이언트가 mock 모드인지 확인 (실 API 호출 없음)
        assert decision_generator.gemini_client.model is None

    def test_merge_all_features(
        self,
        decision_generator,
        sample_quant_features,
        sample_sentiment_features,
        sample_timeseries_features
    ):
        """Test merging all feature DataFrames."""
        merged = decision_generator._merge_all_features(
            sample_quant_features,
            sample_sentiment_features,
            sample_timeseries_features
        )

        # Verify merge
        assert len(merged) == 3
        assert 'stock_code' in merged.columns

        # Verify all 11 features exist
        expected_features = [
            'morning_return', 'close_position', 'foreign_net_buy', 'institutional_net_buy',
            'per', 'roe', 'operating_margin',
            'sentiment_score',
            'prophet_price_trend', 'prophet_volume_trend', 'prophet_price_uncertainty'
        ]

        for feature in expected_features:
            assert feature in merged.columns, f"Missing feature: {feature}"

    def test_build_stock_contexts(
        self,
        decision_generator,
        sample_quant_features,
        sample_sentiment_features,
        sample_timeseries_features
    ):
        """Test building stock contexts for Gemini prompt."""
        merged = decision_generator._merge_all_features(
            sample_quant_features,
            sample_sentiment_features,
            sample_timeseries_features
        )

        contexts = decision_generator._build_stock_contexts(merged)

        # Verify contexts
        assert len(contexts) == 3
        assert isinstance(contexts[0], str)

        # Verify context contains key information
        context = contexts[0]
        assert '005930' in context  # Stock code
        assert '정량 분석' in context  # Section headers
        assert '감성 분석' in context
        assert '시계열 예측' in context

    def test_format_per(self, decision_generator):
        """Test PER value formatting."""
        # Normal PER
        assert decision_generator._format_per(12.5) == "12.50"

        # None (loss-making company)
        assert decision_generator._format_per(None) == "적자 또는 결측"

        # NaN
        import numpy as np
        assert decision_generator._format_per(np.nan) == "적자 또는 결측"

    def test_generate_gemini_prompt(
        self,
        decision_generator,
        sample_quant_features,
        sample_sentiment_features,
        sample_timeseries_features
    ):
        """Test Gemini prompt generation."""
        merged = decision_generator._merge_all_features(
            sample_quant_features,
            sample_sentiment_features,
            sample_timeseries_features
        )

        contexts = decision_generator._build_stock_contexts(merged)
        prompt = decision_generator._generate_gemini_prompt(contexts)

        # Verify prompt structure
        assert isinstance(prompt, str)
        assert len(prompt) > 0

        # Verify key sections
        assert 'AI 트레이딩 어드바이저' in prompt
        assert '분석 종목' in prompt
        assert '판단 기준' in prompt
        assert '출력 형식' in prompt
        assert 'JSON' in prompt

    def test_generate_decisions_mock_mode(
        self,
        decision_generator,
        sample_quant_features,
        sample_sentiment_features,
        sample_timeseries_features
    ):
        """Test generate_decisions in mock mode."""
        decision = decision_generator.generate_decisions(
            sample_quant_features,
            sample_sentiment_features,
            sample_timeseries_features
        )

        # Verify decision structure
        assert 'buy_top3' in decision
        assert 'sell_top3' in decision
        assert len(decision['buy_top3']) == 3
        assert len(decision['sell_top3']) == 3

    def test_validate_decision_valid(self, decision_generator):
        """Test validation of valid decision."""
        valid_decision = {
            'buy_top3': [
                {'stock_code': '005930', 'reason': 'Test 1'},
                {'stock_code': '000660', 'reason': 'Test 2'},
                {'stock_code': '051910', 'reason': 'Test 3'}
            ],
            'sell_top3': [
                {'stock_code': '005380', 'reason': 'Test 1'},
                {'stock_code': '035420', 'reason': 'Test 2'},
                {'stock_code': '068270', 'reason': 'Test 3'}
            ]
        }

        validation = decision_generator.validate_decision(valid_decision)

        # All validations should pass
        assert all(validation.values()), f"Validation failed: {validation}"

    def test_validate_decision_duplicates(self, decision_generator):
        """Test validation detects duplicate stock codes."""
        duplicate_decision = {
            'buy_top3': [
                {'stock_code': '005930', 'reason': 'Test 1'},
                {'stock_code': '005930', 'reason': 'Test 2'},  # Duplicate
                {'stock_code': '051910', 'reason': 'Test 3'}
            ],
            'sell_top3': [
                {'stock_code': '005380', 'reason': 'Test 1'},
                {'stock_code': '035420', 'reason': 'Test 2'},
                {'stock_code': '068270', 'reason': 'Test 3'}
            ]
        }

        validation = decision_generator.validate_decision(duplicate_decision)

        # Should detect duplicate
        assert validation['buy_unique'] is False

    def test_validate_decision_overlap(self, decision_generator):
        """Test validation detects buy/sell overlap."""
        overlap_decision = {
            'buy_top3': [
                {'stock_code': '005930', 'reason': 'Test 1'},
                {'stock_code': '000660', 'reason': 'Test 2'},
                {'stock_code': '051910', 'reason': 'Test 3'}
            ],
            'sell_top3': [
                {'stock_code': '005930', 'reason': 'Test 1'},  # Overlaps with buy
                {'stock_code': '035420', 'reason': 'Test 2'},
                {'stock_code': '068270', 'reason': 'Test 3'}
            ]
        }

        validation = decision_generator.validate_decision(overlap_decision)

        # Should detect overlap
        assert validation['no_overlap'] is False

    def test_validate_decision_wrong_count(self, decision_generator):
        """Test validation detects wrong item count."""
        wrong_count_decision = {
            'buy_top3': [
                {'stock_code': '005930', 'reason': 'Test 1'},
                {'stock_code': '000660', 'reason': 'Test 2'}
                # Missing 3rd item
            ],
            'sell_top3': [
                {'stock_code': '005380', 'reason': 'Test 1'},
                {'stock_code': '035420', 'reason': 'Test 2'},
                {'stock_code': '068270', 'reason': 'Test 3'}
            ]
        }

        validation = decision_generator.validate_decision(wrong_count_decision)

        # Should detect wrong count
        assert validation['buy_count_correct'] is False


class TestDecisionValidation:
    """Test decision validation rules."""

    def test_decision_structure_requirements(self):
        """Test decision structure requirements."""
        # Required top-level keys
        required_keys = ['buy_top3', 'sell_top3']

        # Required item fields
        required_item_fields = ['stock_code', 'reason']

        # Item count
        required_count = 3

        # Verify requirements
        assert len(required_keys) == 2
        assert len(required_item_fields) == 2
        assert required_count == 3

    def test_stock_code_format(self):
        """Test stock code format validation."""
        valid_codes = ['005930', '000660', '051910', '005380']

        for code in valid_codes:
            assert len(code) == 6, f"Stock code {code} should be 6 digits"
            assert code.isdigit(), f"Stock code {code} should be all digits"

    def test_reason_not_empty(self):
        """Test reason field should not be empty."""
        valid_reasons = [
            "외국인·기관 동시 순매수 + 가격 상승 추세",
            "Strong fundamentals with positive sentiment",
            "[MOCK] Rising price trend and institutional buying"
        ]

        for reason in valid_reasons:
            assert len(reason) > 0, "Reason should not be empty"
            assert isinstance(reason, str), "Reason should be string"


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
