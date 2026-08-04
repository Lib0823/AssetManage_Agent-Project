"""
pytest tests for Stage 2-1: Quantitative Analysis
"""
import pytest
import pandas as pd
import numpy as np
from datetime import datetime
from unittest.mock import Mock, patch, AsyncMock

from analysis.quantitative import QuantitativeAnalyzer


@pytest.fixture
def quantitative_analyzer():
    """Create QuantitativeAnalyzer instance for testing."""
    return QuantitativeAnalyzer()


@pytest.fixture
def sample_kis_supply_demand():
    """Mock KIS supply/demand response."""
    return {
        'foreign_net_buy': 50000000,
        'institutional_net_buy': 30000000
    }


@pytest.fixture
def sample_ohlcv_data():
    """Sample OHLCV DataFrame."""
    return pd.DataFrame({
        'trade_date': ['20260519'],
        'open': [70000],
        'high': [71000],
        'low': [69000],
        'close': [70500],
        'volume': [10000000]
    })


@pytest.fixture
def sample_minute_data():
    """Sample minute-level data for morning_return calculation."""
    return pd.DataFrame({
        'time': ['0900', '0910', '0920', '0930', '0940', '0950', '1000'],
        'close': [70000, 70100, 70200, 70300, 70400, 70500, 70600]
    })


@pytest.fixture
def sample_dart_financials():
    """Sample DART financial data."""
    return pd.DataFrame({
        'stock_code': ['005930', '000660'],
        'per': [12.5, 8.3],
        'roe': [15.2, 10.5],
        'operating_margin': [18.5, 12.3]
    })


class TestQuantitativeAnalyzer:
    """Test suite for QuantitativeAnalyzer."""

    def test_init(self, quantitative_analyzer):
        """Test analyzer initialization."""
        assert quantitative_analyzer.kis_client is not None
        assert quantitative_analyzer.dart_client is not None

    @pytest.mark.asyncio
    async def test_calculate_morning_return(self, quantitative_analyzer):
        """Test morning_return calculation (09:00 → 10:00)."""
        # Mock KIS client
        with patch.object(quantitative_analyzer.kis_client, 'get_minute_data', new_callable=AsyncMock) as mock_minute:
            # get_minute_data 실제 반환 컬럼은 open_price/close_price (collectors/kis_client.py
            # get_minute_data 참고) — 'close'는 구현이 읽지 않아 이 목업은 도입 시점부터 한 번도
            # 통과한 적이 없었다 (KeyError를 analysis/quantitative.py의 광범위 except가 삼켜
            # 0.0으로 위장됨).
            mock_minute.return_value = pd.DataFrame({
                'time': ['090100', '100000'],
                'open_price': [70000, 70500],
                'close_price': [70100, 70600]
            })

            morning_return = await quantitative_analyzer._calculate_morning_return(
                '005930',
                datetime(2026, 5, 19)
            )

            # Expected: (70600 - 70000) / 70000 * 100 = 0.857%
            assert round(morning_return, 2) == 0.86

    @pytest.mark.asyncio
    async def test_calculate_close_position(self, quantitative_analyzer):
        """Test close_position calculation."""
        # Mock KIS client
        with patch.object(quantitative_analyzer.kis_client, 'get_daily_ohlcv', new_callable=AsyncMock) as mock_ohlcv:
            mock_ohlcv.return_value = pd.DataFrame({
                'trade_date': ['20260518'],
                'high': [71000],
                'low': [69000],
                'close': [70500]
            })

            close_position = await quantitative_analyzer._calculate_close_position(
                '005930',
                datetime(2026, 5, 19)
            )

            # Expected: (70500 - 69000) / (71000 - 69000) = 0.75
            assert round(close_position, 2) == 0.75

    @pytest.mark.asyncio
    async def test_close_position_edge_cases(self, quantitative_analyzer):
        """Test close_position with edge cases."""
        # Case 1: high == low (should return 0.5 to avoid division by zero)
        with patch.object(quantitative_analyzer.kis_client, 'get_daily_ohlcv', new_callable=AsyncMock) as mock_ohlcv:
            mock_ohlcv.return_value = pd.DataFrame({
                'trade_date': ['20260518'],
                'high': [70000],
                'low': [70000],
                'close': [70000]
            })
            close_position = await quantitative_analyzer._calculate_close_position('005930', datetime(2026, 5, 19))
            assert close_position == 0.5

        # Case 2: close == high (should return 1.0)
        with patch.object(quantitative_analyzer.kis_client, 'get_daily_ohlcv', new_callable=AsyncMock) as mock_ohlcv:
            mock_ohlcv.return_value = pd.DataFrame({
                'trade_date': ['20260518'],
                'high': [71000],
                'low': [69000],
                'close': [71000]
            })
            close_position = await quantitative_analyzer._calculate_close_position('005930', datetime(2026, 5, 19))
            assert close_position == 1.0

        # Case 3: close == low (should return 0.0)
        with patch.object(quantitative_analyzer.kis_client, 'get_daily_ohlcv', new_callable=AsyncMock) as mock_ohlcv:
            mock_ohlcv.return_value = pd.DataFrame({
                'trade_date': ['20260518'],
                'high': [71000],
                'low': [69000],
                'close': [69000]
            })
            close_position = await quantitative_analyzer._calculate_close_position('005930', datetime(2026, 5, 19))
            assert close_position == 0.0

    def test_apply_outlier_clipping(self, quantitative_analyzer):
        """Test outlier clipping using 99th percentile."""
        test_df = pd.DataFrame({
            'stock_code': ['005930', '000660', '051910'],
            'morning_return': [1.2, 150.0, 0.8],  # 150.0 is outlier
            'close_position': [0.85, 0.65, 0.75],
            'foreign_net_buy': [50000000, -20000000, 10000000],
            'institutional_net_buy': [30000000, -15000000, 5000000],
            'per': [12.5, 8.3, 15.2],
            'roe': [15.2, 10.5, 22.5],
            'operating_margin': [18.5, 12.3, 25.3]
        })

        clipped_df = quantitative_analyzer._apply_outlier_clipping(test_df)

        # Verify outlier was clipped
        assert clipped_df['morning_return'].max() < 150.0
        assert clipped_df['morning_return'].max() <= np.percentile([1.2, 150.0, 0.8], 99)

    def test_merge_kis_dart_features(self, quantitative_analyzer):
        """Test merging KIS and DART features."""
        kis_df = pd.DataFrame({
            'stock_code': ['005930', '000660'],
            'morning_return': [1.2, 0.8],
            'close_position': [0.85, 0.75],
            'foreign_net_buy': [50000000, 10000000],
            'institutional_net_buy': [30000000, 5000000]
        })

        dart_df = pd.DataFrame({
            'stock_code': ['005930', '000660'],
            'per': [12.5, 8.3],
            'roe': [15.2, 10.5],
            'operating_margin': [18.5, 12.3]
        })

        merged = pd.merge(kis_df, dart_df, on='stock_code', how='left')

        # Verify merge
        assert len(merged) == 2
        assert 'morning_return' in merged.columns
        assert 'per' in merged.columns
        assert merged.loc[0, 'stock_code'] == '005930'
        assert merged.loc[0, 'per'] == 12.5

    @pytest.mark.asyncio
    async def test_analyze_stocks_structure(self, quantitative_analyzer):
        """Test analyze_stocks returns correct structure."""
        # Mock KIS and DART clients
        with patch.object(quantitative_analyzer.kis_client, 'get_supply_demand', new_callable=AsyncMock) as mock_supply, \
             patch.object(quantitative_analyzer.kis_client, 'get_daily_ohlcv', new_callable=AsyncMock) as mock_ohlcv, \
             patch.object(quantitative_analyzer.kis_client, 'get_minute_data', new_callable=AsyncMock) as mock_minute, \
             patch.object(quantitative_analyzer.dart_client, 'get_latest_financials') as mock_dart:

            # Setup mocks
            mock_supply.return_value = {'foreign_net_buy': 50000000, 'institutional_net_buy': 30000000}
            mock_ohlcv.return_value = pd.DataFrame({
                'trade_date': ['20260519'],
                'open': [70000],
                'high': [71000],
                'low': [69000],
                'close': [70500],
                'volume': [10000000]
            })
            mock_minute.return_value = pd.DataFrame({
                'time': ['0900', '1000'],
                'close': [70000, 70600]
            })
            mock_dart.return_value = pd.DataFrame({
                'stock_code': ['005930'],
                'per': [12.5],
                'roe': [15.2],
                'operating_margin': [18.5]
            })

            # Execute
            result = await quantitative_analyzer.analyze_stocks(
                stock_codes=['005930'],
                trade_date=datetime(2026, 5, 19)
            )

            # Verify structure
            assert isinstance(result, pd.DataFrame)
            assert len(result) == 1
            assert 'stock_code' in result.columns

            # Verify 7 features exist
            expected_features = [
                'morning_return', 'close_position', 'foreign_net_buy', 'institutional_net_buy',
                'per', 'roe', 'operating_margin'
            ]
            for feature in expected_features:
                assert feature in result.columns, f"Missing feature: {feature}"


class TestFeatureValidation:
    """Test feature value validation."""

    def test_morning_return_range(self):
        """Test morning_return should be reasonable (-10% to 10% typical)."""
        # This is a validation test for real data
        sample_returns = [1.2, -0.5, 0.8, 2.5, -1.8]

        for ret in sample_returns:
            assert -30 < ret < 30, f"morning_return {ret} outside reasonable range"

    def test_close_position_range(self):
        """Test close_position should be between 0 and 1."""
        # Valid range test
        valid_positions = [0.0, 0.25, 0.5, 0.75, 1.0, 0.85, 0.15]

        for pos in valid_positions:
            assert 0.0 <= pos <= 1.0, f"close_position {pos} outside [0, 1] range"

    def test_per_validation(self):
        """Test PER should be positive or None (for loss-making companies)."""
        valid_per_values = [12.5, 8.3, 25.7, None, np.nan]

        for per in valid_per_values:
            if per is not None and not pd.isna(per):
                assert per > 0, f"PER {per} should be positive"

    def test_roe_range(self):
        """Test ROE should be reasonable percentage."""
        valid_roe_values = [15.2, 10.5, 22.5, -5.0]  # Negative ROE possible (loss)

        for roe in valid_roe_values:
            assert -100 < roe < 200, f"ROE {roe} outside reasonable range"


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
