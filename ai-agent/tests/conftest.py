"""Pytest configuration and fixtures."""
import pytest
import os
from unittest.mock import MagicMock
from dotenv import load_dotenv

# Load test environment variables
load_dotenv('.env.test', override=True)

# KR-FinBERT 테스트가 HuggingFace Hub 로 나가지 않도록 로컬 캐시만 쓰게 강제한다.
# transformers/huggingface_hub 가 import 되기 전에 설정해야 효력이 있으므로
# 픽스처가 아니라 모듈 레벨에 둔다. (Gemini 차단과 같은 취지 — CI 비결정성 방지)
os.environ.setdefault('HF_HUB_OFFLINE', '1')
os.environ.setdefault('TRANSFORMERS_OFFLINE', '1')


@pytest.fixture(autouse=True)
def gemini_no_network(monkeypatch):
    """모든 테스트에서 Gemini 실 API 호출을 차단한다 (autouse).

    ai-agent 의 `.env` 에는 실제 `GEMINI_API_KEY` 가 들어 있고
    `GeminiClient.__init__` 은 `api_key or settings.gemini_api_key` 로 폴백하므로,
    아무 조치 없이 pytest 를 돌리면 `GeminiClient(api_key=None)` 조차 실제
    모델을 초기화해 무료 티어 쿼터를 소모하는 네트워크 호출이 나간다.
    (CI 에서는 키 유무·네트워크 상태에 따라 결과가 흔들린다.)

    두 겹으로 막는다:
      1. `settings.gemini_api_key` 를 None 으로 덮어 mock 모드를 강제한다.
      2. `genai.configure` / `genai.GenerativeModel` 을 스텁으로 대체해,
         테스트가 명시적으로 키를 넘기더라도 네트워크 경로를 원천 차단한다.
         스텁 모델의 `generate_content` 는 AssertionError 를 던지므로,
         실호출을 시도하는 테스트는 조용히 통과하지 않고 즉시 실패한다.

    Returns:
        MagicMock: `genai.GenerativeModel(...)` 대신 주입되는 스텁 모델.
    """
    from config.settings import get_settings
    from ai import gemini_client as gemini_module

    # 1) 실 키 폴백 차단 (monkeypatch 라 테스트 종료 시 원복)
    monkeypatch.setattr(get_settings(), 'gemini_api_key', None, raising=False)

    # 2) SDK 진입점 스텁
    def _fail_on_real_call(*args, **kwargs):
        raise AssertionError(
            "테스트 중 실제 Gemini API 호출이 시도되었습니다. "
            "GeminiClient 를 mock/stub 처리하세요 "
            "(예: patch.object(GeminiClient, '_generate_text'))."
        )

    stub_model = MagicMock(name='StubGenerativeModel')
    stub_model.generate_content.side_effect = _fail_on_real_call

    monkeypatch.setattr(gemini_module.genai, 'configure', lambda *a, **kw: None)
    monkeypatch.setattr(gemini_module.genai, 'GenerativeModel', lambda *a, **kw: stub_model)

    return stub_model


@pytest.fixture(scope='session')
def test_config():
    """Test configuration fixture."""
    return {
        'db_url': os.getenv('DB_URL', 'postgresql://postgres:password@localhost:5432/financemanage_test'),
        'kis_mode': 'VIRTUAL',
        'log_level': 'DEBUG'
    }


@pytest.fixture
def mock_kis_response():
    """Mock KIS API response fixture."""
    return {
        'rt_cd': '0',
        'msg_cd': 'MCA00000',
        'msg1': '정상처리 되었습니다.',
        'output': {
            'stck_frgn_ntby_amt': '10000000',
            'stck_orgn_ntby_amt': '5000000'
        }
    }


@pytest.fixture
def sample_ohlcv_response():
    """Mock OHLCV response fixture."""
    return {
        'rt_cd': '0',
        'output': [
            {
                'stck_bsop_date': '20260519',
                'stck_oprc': '70000',
                'stck_hgpr': '71000',
                'stck_lwpr': '69000',
                'stck_clpr': '70500',
                'acml_vol': '10000000'
            }
        ]
    }
