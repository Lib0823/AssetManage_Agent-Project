"""Unit tests for PipelineOrchestrator (Stage 0~6 조율 로직).

orchestrator 는 collectors/analysis/ai/filters/execution/database 의 컴포넌트를
호출·조합하는 역할이므로, 여기서는 그 컴포넌트를 전부 mock 으로 대체하고
**흐름 제어 로직**만 검증한다:
  - 휴장일/KIS 장애 시 중단 여부
  - 보유종목 합집합이 분석 유니버스와 강제 포함 인자로 전달되는지
  - Stage 실패가 이후 Stage 로 어떻게 전파(또는 격리)되는지
  - 활성 유저가 없을 때 Stage 6 가 스킵되는지
"""
from contextlib import ExitStack
from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pandas as pd
import pytest

from collectors import KISUnavailableError
from pipeline.orchestrator import PipelineOrchestrator


TEST_UNIVERSE = ['005930', '000660', '035420']
SELECTED = ['005930', '000660']

# orchestrator 가 생성자에서 실체화하는 협력 객체들 (전부 mock 으로 대체)
COLLABORATORS = [
    'KISClient', 'StockFilter', 'DARTAPIClient', 'QuantitativeAnalyzer',
    'SentimentAnalyzer', 'TimeSeriesAnalyzer', 'TradingDecisionGenerator',
    'SafetyFilter', 'DatabaseRepository', 'InternalApiClient', 'TradeExecutor',
    'KafkaMessagePublisher',
]


def _stock_data_df():
    return pd.DataFrame({
        'stock_code': TEST_UNIVERSE,
        'foreign_net_buy': [1000, -500, 200],
        'institutional_net_buy': [300, 100, -50],
        'vol_avg_multiple': [1.5, 2.0, 0.8],
        'price_volatility': [1.1, 0.9, 1.4],
    })


def _filtered_df():
    return pd.DataFrame({
        'stock_code': TEST_UNIVERSE,
        'final_score': [0.9, 0.7, 0.1],
        'is_selected': [True, True, False],
    })


def _quant_df():
    return pd.DataFrame({
        'stock_code': SELECTED,
        'morning_return': [1.2, -0.4],
        'close_position': [0.8, 0.3],
    })


def _sentiment_df():
    return pd.DataFrame({
        'stock_code': SELECTED,
        'sentiment_score': [0.5, -0.2],
        'news_count': [5, 3],
    })


def _ts_df():
    return pd.DataFrame({
        'stock_code': SELECTED,
        'prophet_price_trend': [0.02, -0.01],
        'prophet_volume_trend': [0.01, 0.0],
        'prophet_price_uncertainty': [100.0, 300.0],
    })


def _dart_df():
    return pd.DataFrame({
        'stock_code': SELECTED,
        'roe': [15.0, 8.0],
        'operating_margin': [8.0, 4.0],
    })


def _passthrough_filter(decisions, features_df, stock_prices=None, order_amount=0):
    """SafetyFilter.filter_decisions 대역: 통과시키되 max_quantity 만 채워준다."""
    buys = []
    for b in decisions.get('buy_top3', []):
        item = dict(b)
        price = (stock_prices or {}).get(b['stock_code'], 0)
        item.setdefault('max_quantity', int(order_amount / price) if price else 0)
        buys.append(item)
    return {
        'buy_top3': buys,
        'sell_top3': list(decisions.get('sell_top3', [])),
        'filter_results': [{'stock_code': b['stock_code'], 'passed': True} for b in buys],
    }


@pytest.fixture
def env():
    """모든 협력 객체를 mock 으로 대체한 PipelineOrchestrator 와 그 mock 들."""
    order_log = []

    kis = MagicMock(name='KISClient')
    kis.is_market_open = AsyncMock(return_value=True)
    kis.fetch_stock_data_parallel = AsyncMock(return_value=_stock_data_df())
    kis.get_kospi_index = AsyncMock(return_value={
        'kospi_index': 2500.0, 'kospi_change_rate': 0.53, 'kospi_volume': 123456,
    })
    kis.get_valuations_for_stocks = AsyncMock(return_value={'005930': 12.0, '000660': 20.0})
    kis.get_current_price = AsyncMock(return_value={'current_price': 10000})

    stock_filter = MagicMock(name='StockFilter')
    stock_filter.process = MagicMock(return_value=_filtered_df())

    dart = MagicMock(name='DARTAPIClient')
    dart.collect_financials_with_fallback = MagicMock(
        return_value=(_dart_df(), date(2026, 3, 31))
    )
    dart.save_to_database = MagicMock(return_value=True)

    async def _quant_call(*args, **kwargs):
        order_log.append('quant')
        return _quant_df()

    quant = MagicMock(name='QuantitativeAnalyzer')
    quant.analyze_stocks = AsyncMock(side_effect=_quant_call)

    async def _sentiment_call(*args, **kwargs):
        order_log.append('sentiment')
        return _sentiment_df()

    sentiment = MagicMock(name='SentimentAnalyzer')
    sentiment.analyze_stocks = AsyncMock(side_effect=_sentiment_call)
    sentiment.last_stock_news = {'005930': [{'title': 'news'}]}
    sentiment.last_market_sentiment = 0.1234
    sentiment.last_market_news_count = 7

    async def _ts_call(*args, **kwargs):
        order_log.append('timeseries')
        return _ts_df()

    ts = MagicMock(name='TimeSeriesAnalyzer')
    ts.analyze_stocks = AsyncMock(side_effect=_ts_call)

    def _gen_decisions(**kwargs):
        order_log.append('gemini')
        return {
            'buy_top3': [{'stock_code': '005930', 'reason': 'strong'}],
            'sell_top3': [{'stock_code': '000660', 'reason': 'weak'}],
        }

    decision_gen = MagicMock(name='TradingDecisionGenerator')
    decision_gen.generate_decisions = MagicMock(side_effect=_gen_decisions)
    decision_gen.generate_user_decision = MagicMock(return_value={
        'buy': [{'stock_code': '005930', 'reason': 'buy it'}],
        'sell': [{'stock_code': '000660', 'reason': 'sell it'}],
    })

    def _filter_call(*args, **kwargs):
        order_log.append('safety')
        return _passthrough_filter(*args, **kwargs)

    safety = MagicMock(name='SafetyFilter')
    safety.filter_decisions = MagicMock(side_effect=_filter_call)

    db = MagicMock(name='DatabaseRepository')
    db.get_feature_thresholds = MagicMock(return_value={'per': {'buy_threshold': 30.0}})
    db.save_filter_scores = MagicMock(return_value=True)
    db.save_market_summary = MagicMock(return_value=True)
    db.save_quantitative_features = MagicMock(return_value=True)
    db.save_sentiment_analysis = MagicMock(return_value=True)
    db.save_stock_news = MagicMock(return_value=2)
    db.save_prophet_forecast_detailed = MagicMock(return_value=True)
    db.update_market_sentiment = MagicMock(return_value=True)
    db.save_ai_decisions = MagicMock(return_value=True)
    db.save_safety_filter_results = MagicMock(return_value=True)
    db.get_user_order_amount = MagicMock(return_value=1_000_000)
    db.save_trade_execution_plan = MagicMock(return_value=1)
    db.update_trade_execution_result = MagicMock(return_value=1)

    internal = MagicMock(name='InternalApiClient')
    internal.get_active_auto_trading_users = AsyncMock(return_value=[])
    internal.get_user_portfolio = AsyncMock(return_value={
        'holdings': [], 'cash': 0.0, 'total_assets': 0.0, 'holding_codes': [],
    })

    async def _execute(user_id, buy_orders, sell_orders, trade_date=None):
        order_log.append('execute')
        return {
            'user_id': user_id,
            'trade_date': trade_date.isoformat() if trade_date else None,
            'buy_results': [{'stock_code': o['stock_code'],
                             'result': {'success': True, 'status': 'QUEUED'}}
                            for o in buy_orders],
            'sell_results': [{'stock_code': o['stock_code'],
                              'result': {'success': True, 'status': 'QUEUED'}}
                             for o in sell_orders],
        }

    executor = MagicMock(name='TradeExecutor')
    executor.execute_for_user = AsyncMock(side_effect=_execute)

    publisher = MagicMock(name='KafkaMessagePublisher')

    instances = {
        'KISClient': kis,
        'StockFilter': stock_filter,
        'DARTAPIClient': dart,
        'QuantitativeAnalyzer': quant,
        'SentimentAnalyzer': sentiment,
        'TimeSeriesAnalyzer': ts,
        'TradingDecisionGenerator': decision_gen,
        'SafetyFilter': safety,
        'DatabaseRepository': db,
        'InternalApiClient': internal,
        'TradeExecutor': executor,
        'KafkaMessagePublisher': publisher,
    }

    with ExitStack() as stack:
        classes = {}
        for name in COLLABORATORS:
            cls_mock = MagicMock(name=f'{name}Class', return_value=instances[name])
            classes[name] = cls_mock
            stack.enter_context(patch(f'pipeline.orchestrator.{name}', cls_mock))
        stack.enter_context(patch('pipeline.orchestrator.KOSPI_100', list(TEST_UNIVERSE)))

        orchestrator = PipelineOrchestrator()

        yield SimpleNamespace(
            orchestrator=orchestrator,
            classes=classes,
            order_log=order_log,
            kis=kis,
            stock_filter=stock_filter,
            dart=dart,
            quant=quant,
            sentiment=sentiment,
            ts=ts,
            decision_gen=decision_gen,
            safety=safety,
            db=db,
            internal=internal,
            executor=executor,
            publisher=publisher,
        )


class TestInit:
    """생성자 배선 검증."""

    def test_safety_filter_receives_db_thresholds(self, env):
        """임계값 단일 출처는 feature_threshold_config → SafetyFilter 로 주입된다."""
        env.classes['SafetyFilter'].assert_called_once_with(
            thresholds={'per': {'buy_threshold': 30.0}}
        )

    def test_db_repo_created_before_safety_filter(self, env):
        """SafetyFilter 는 db_repo.get_feature_thresholds() 결과를 필요로 한다."""
        env.db.get_feature_thresholds.assert_called_once()

    def test_kis_client_shared_with_analyzers(self, env):
        """OAuth 토큰 캐시 공유를 위해 동일 KISClient 인스턴스를 넘긴다."""
        env.classes['QuantitativeAnalyzer'].assert_called_once_with(kis_client=env.kis)
        env.classes['TimeSeriesAnalyzer'].assert_called_once_with(kis_client=env.kis)

    def test_trade_executor_publishes_to_kafka(self, env):
        """Stage 6 는 REST 대신 Kafka 프로듀서를 통해 주문을 낸다."""
        env.classes['TradeExecutor'].assert_called_once_with(publisher=env.publisher)

    def test_injected_publisher_is_reused(self, env):
        """lifespan 이 만든 프로듀서를 주입하면 새로 만들지 않고 공유한다."""
        env.classes['KafkaMessagePublisher'].reset_mock()
        shared = MagicMock(name='sharedPublisher')

        orchestrator = PipelineOrchestrator(publisher=shared)

        assert orchestrator.publisher is shared
        env.classes['KafkaMessagePublisher'].assert_not_called()

    def test_explicit_api_server_url_overrides_settings(self, env):
        with patch('pipeline.orchestrator.settings') as s:
            s.internal_api_key = 'k'
            PipelineOrchestrator(api_server_url='http://custom:9999')
        _, kwargs = env.classes['InternalApiClient'].call_args
        assert kwargs['base_url'] == 'http://custom:9999'

    def test_multiuser_context_starts_empty(self, env):
        assert env.orchestrator.active_users == []
        assert env.orchestrator.user_holdings_map == {}
        assert env.orchestrator.user_portfolio_map == {}


class TestStage1MarketGate:
    """Stage 0 (휴장일 / KIS 장애) 게이트."""

    async def test_holiday_aborts_pipeline(self, env):
        env.kis.is_market_open = AsyncMock(return_value=False)

        result = await env.orchestrator.run_stage1_filtering(date(2026, 8, 15))

        assert result['success'] is False
        assert result['is_holiday'] is True
        assert result['error'] == '오늘은 휴장일입니다'
        env.kis.fetch_stock_data_parallel.assert_not_called()
        env.internal.get_active_auto_trading_users.assert_not_called()
        env.stock_filter.process.assert_not_called()
        env.db.save_filter_scores.assert_not_called()

    async def test_kis_unavailable_is_distinct_from_holiday(self, env):
        """점검/네트워크 오류는 휴장일과 구분해서 보고해야 한다."""
        env.kis.is_market_open = AsyncMock(side_effect=KISUnavailableError('점검 중'))

        result = await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert result['success'] is False
        assert result['is_holiday'] is False
        assert result['kis_unavailable'] is True
        assert '점검 중' in result['error']
        env.kis.fetch_stock_data_parallel.assert_not_called()

    async def test_generic_exception_is_captured(self, env):
        env.kis.is_market_open = AsyncMock(side_effect=RuntimeError('boom'))

        result = await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert result['success'] is False
        assert 'boom' in result['error']
        assert 'kis_unavailable' not in result

    async def test_trade_date_defaults_to_today(self, env):
        env.kis.is_market_open = AsyncMock(return_value=False)

        result = await env.orchestrator.run_stage1_filtering()

        assert result['trade_date'] == date.today().isoformat()


class TestStage1Filtering:
    """Stage 1 정상/실패 흐름."""

    async def test_happy_path_returns_selected_codes(self, env):
        result = await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert result['success'] is True
        assert result['trade_date'] == '2026-08-05'
        assert result['total_stocks'] == 3
        assert result['selected_stocks'] == 2
        assert result['selected_codes'] == SELECTED
        assert result['score_stats'] == {
            'min': pytest.approx(0.1), 'max': pytest.approx(0.9),
            'mean': pytest.approx(0.5666666, rel=1e-4),
        }
        # Stage 2 재사용을 위해 원본 수집 데이터가 함께 반환된다
        assert isinstance(result['stock_data_df'], pd.DataFrame)

    async def test_empty_stock_data_aborts(self, env):
        env.kis.fetch_stock_data_parallel = AsyncMock(return_value=pd.DataFrame())

        result = await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert result['success'] is False
        assert 'Failed to fetch stock data' in result['error']
        env.stock_filter.process.assert_not_called()

    async def test_save_failure_aborts(self, env):
        env.db.save_filter_scores = MagicMock(return_value=False)

        result = await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert result['success'] is False
        assert 'Failed to save results' in result['error']

    async def test_market_summary_failure_is_non_critical(self, env):
        env.kis.get_kospi_index = AsyncMock(side_effect=RuntimeError('kospi down'))

        result = await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert result['success'] is True

    async def test_market_summary_uses_collector_column_names(self, env):
        await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        summary, summary_date = env.db.save_market_summary.call_args[0]
        assert summary['kospi_index'] == 2500.0
        assert summary['total_stocks'] == 3
        assert summary['total_foreign_net_buy'] == 700  # 1000 - 500 + 200
        assert summary['total_institutional_net_buy'] == 350  # 300 + 100 - 50
        # Stage 1 수집기가 종목별 등락률을 주지 않으므로 임의값 대신 None
        assert summary['rising_stocks'] is None
        assert summary['falling_stocks'] is None
        assert summary['unchanged_stocks'] is None
        # 시장 감성은 Stage 2-2 에서 갱신
        assert summary['market_sentiment_score'] is None
        assert summary_date == date(2026, 8, 5)


class TestStage1Holdings:
    """보유종목 합집합 → 유니버스 및 강제 포함."""

    async def test_no_active_users_means_no_holdings(self, env):
        await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        env.kis.fetch_stock_data_parallel.assert_awaited_once_with(TEST_UNIVERSE)
        assert env.stock_filter.process.call_args.kwargs['holdings'] is None

    async def test_user_holdings_union_is_force_included(self, env):
        env.internal.get_active_auto_trading_users = AsyncMock(return_value=[
            {'user_id': 1}, {'user_id': 2},
        ])
        env.internal.get_user_portfolio = AsyncMock(side_effect=[
            {'holding_codes': ['005930', '068270'], 'holdings': [], 'cash': 0.0},
            {'holding_codes': ['068270', '207940'], 'holdings': [], 'cash': 0.0},
        ])

        await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert env.stock_filter.process.call_args.kwargs['holdings'] == [
            '005930', '068270', '207940'
        ]
        # KOSPI100 밖 보유종목도 수집 유니버스에 포함되며 중복은 제거된다
        universe = env.kis.fetch_stock_data_parallel.await_args[0][0]
        assert universe == TEST_UNIVERSE + ['068270', '207940']
        assert len(universe) == len(set(universe))

    async def test_explicit_holdings_merged_with_user_holdings(self, env):
        env.internal.get_active_auto_trading_users = AsyncMock(return_value=[{'user_id': 1}])
        env.internal.get_user_portfolio = AsyncMock(return_value={
            'holding_codes': ['068270'], 'holdings': [], 'cash': 0.0,
        })

        await env.orchestrator.run_stage1_filtering(date(2026, 8, 5), holdings=['005930'])

        assert env.stock_filter.process.call_args.kwargs['holdings'] == ['005930', '068270']

    async def test_portfolio_fetch_failure_degrades_to_empty(self, env):
        """한 유저의 포트폴리오 조회 실패가 파이프라인을 막지 않는다."""
        env.internal.get_active_auto_trading_users = AsyncMock(return_value=[
            {'user_id': 1}, {'user_id': 2},
        ])
        env.internal.get_user_portfolio = AsyncMock(side_effect=[
            RuntimeError('api down'),
            {'holding_codes': ['068270'], 'holdings': [], 'cash': 5.0},
        ])

        result = await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert result['success'] is True
        assert env.orchestrator.user_holdings_map == {1: [], 2: ['068270']}
        assert env.orchestrator.user_portfolio_map[1] == {
            'holdings': [], 'cash': 0.0, 'total_assets': 0.0, 'holding_codes': []
        }

    async def test_portfolio_snapshot_is_stashed_for_stage6(self, env):
        env.internal.get_active_auto_trading_users = AsyncMock(return_value=[{'user_id': 7}])
        portfolio = {'holding_codes': ['005930'], 'holdings': [], 'cash': 100.0}
        env.internal.get_user_portfolio = AsyncMock(return_value=portfolio)

        await env.orchestrator.run_stage1_filtering(date(2026, 8, 5))

        assert env.orchestrator.active_users == [{'user_id': 7}]
        assert env.orchestrator.user_portfolio_map[7] is portfolio


class TestRunStage1Sync:
    def test_sync_wrapper_delegates_to_async(self, env):
        result = env.orchestrator.run_stage1_sync(date(2026, 8, 5))

        assert result['success'] is True
        env.kis.is_market_open.assert_awaited_once()


class TestCompletePipeline:
    """Stage 1~6 전체 조율."""

    async def test_stage1_failure_stops_pipeline(self, env):
        env.kis.is_market_open = AsyncMock(return_value=False)

        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 15))

        assert result['success'] is False
        assert result['error'] == 'Stage 1 filtering failed'
        assert result['stages']['stage1_filtering']['is_holiday'] is True
        assert 'stage2_analysis' not in result['stages']
        env.quant.analyze_stocks.assert_not_called()
        env.sentiment.analyze_stocks.assert_not_called()
        env.ts.analyze_stocks.assert_not_called()
        env.decision_gen.generate_decisions.assert_not_called()
        env.safety.filter_decisions.assert_not_called()

    async def test_happy_path_runs_all_stages_in_order(self, env):
        env.internal.get_active_auto_trading_users = AsyncMock(return_value=[
            {'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5},
        ])
        env.internal.get_user_portfolio = AsyncMock(return_value={
            'holdings': [], 'cash': 1_000_000.0, 'total_assets': 1_000_000.0,
            'holding_codes': [],
        })

        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        assert result['success'] is True
        assert set(result['stages']) == {
            'stage1_filtering', 'stage2_analysis', 'stage4_gemini',
            'stage5_safety_filter', 'stage6_execution',
        }
        # 3-way 분석은 Gemini 이전에, 안전망은 Gemini 이후에, 실행은 마지막에
        assert env.order_log.index('quant') < env.order_log.index('gemini')
        assert env.order_log.index('sentiment') < env.order_log.index('gemini')
        assert env.order_log.index('timeseries') < env.order_log.index('gemini')
        assert env.order_log.index('gemini') < env.order_log.index('safety')
        assert env.order_log.index('safety') < env.order_log.index('execute')

    async def test_dataframe_is_stripped_from_api_response(self, env):
        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        assert 'stock_data_df' not in result['stages']['stage1_filtering']

    async def test_stage2_receives_only_selected_stocks(self, env):
        await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        codes, trade_date = env.quant.analyze_stocks.await_args[0]
        assert codes == SELECTED
        assert trade_date == date(2026, 8, 5)
        stage1_data = env.quant.analyze_stocks.await_args.kwargs['stage1_data']
        assert stage1_data['stock_code'].tolist() == SELECTED

        assert env.sentiment.analyze_stocks.await_args[0][0] == SELECTED
        assert env.ts.analyze_stocks.await_args[0][0] == SELECTED

    async def test_gemini_receives_all_three_feature_sets(self, env):
        await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        kwargs = env.decision_gen.generate_decisions.call_args.kwargs
        assert set(kwargs) == {'quant_features', 'sentiment_features', 'timeseries_features'}
        assert kwargs['quant_features']['stock_code'].tolist() == SELECTED

    async def test_features_merged_into_11_feature_frame(self, env):
        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        # quant(2) + sentiment(2) + ts(3) = 7 피처 (+ stock_code 제외)
        assert result['stages']['stage2_analysis'] == {
            'features_count': 7, 'stocks_count': 2,
        }

    async def test_dart_per_enrichment_from_kis(self, env):
        """DART 재무만으로는 PER 산출 불가 → KIS 시세로 보강."""
        await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        env.kis.get_valuations_for_stocks.assert_awaited_once_with(SELECTED)
        saved_df = env.dart.save_to_database.call_args[0][0]
        assert saved_df['per'].tolist() == [12.0, 20.0]

    async def test_empty_dart_skips_save_and_per_enrichment(self, env):
        env.dart.collect_financials_with_fallback = MagicMock(
            return_value=(pd.DataFrame(), date(2026, 3, 31))
        )

        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        assert result['success'] is True
        env.dart.save_to_database.assert_not_called()
        env.kis.get_valuations_for_stocks.assert_not_called()

    async def test_market_sentiment_persisted_on_both_tracks(self, env):
        await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        env.db.update_market_sentiment.assert_called_once_with(
            summary_date=date(2026, 8, 5), market_sentiment_score=0.1234
        )
        # 종목별 2건 + 시장 전반 1건(stock_code=None)
        market_rows = [c for c in env.db.save_sentiment_analysis.call_args_list
                       if c.kwargs['stock_code'] is None]
        assert len(market_rows) == 1
        assert market_rows[0].kwargs['news_count'] == 7
        assert env.db.save_sentiment_analysis.call_count == 3

    async def test_stock_news_persistence_failure_is_non_critical(self, env):
        env.db.save_stock_news = MagicMock(side_effect=RuntimeError('news table gone'))

        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        assert result['success'] is True
        env.decision_gen.generate_decisions.assert_called_once()

    async def test_analysis_stage_failure_aborts_remaining_stages(self, env):
        env.sentiment.analyze_stocks = AsyncMock(side_effect=RuntimeError('finbert crashed'))

        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        assert result['success'] is False
        assert 'finbert crashed' in result['error']
        assert 'stage1_filtering' in result['stages']
        assert 'stage4_gemini' not in result['stages']
        env.ts.analyze_stocks.assert_not_called()
        env.decision_gen.generate_decisions.assert_not_called()
        env.executor.execute_for_user.assert_not_called()

    async def test_gemini_failure_aborts_execution(self, env):
        env.decision_gen.generate_decisions = MagicMock(side_effect=RuntimeError('quota'))

        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        assert result['success'] is False
        assert 'quota' in result['error']
        env.safety.filter_decisions.assert_not_called()
        env.executor.execute_for_user.assert_not_called()

    async def test_stage5_fetches_prices_for_buy_candidates_only(self, env):
        await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        awaited = [c.args[0] for c in env.kis.get_current_price.await_args_list]
        assert awaited == ['005930']  # sell 후보(000660)는 조회하지 않는다

    async def test_stage5_uses_configured_order_amount(self, env):
        await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        env.db.get_user_order_amount.assert_called_once_with(user_id=1)
        kwargs = env.safety.filter_decisions.call_args_list[0].kwargs
        assert kwargs['order_amount'] == 1_000_000
        assert kwargs['stock_prices'] == {'005930': 10000}

    async def test_stage5_results_saved_and_reported(self, env):
        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        assert result['stages']['stage5_safety_filter']['buy_passed'] == 1
        assert result['stages']['stage5_safety_filter']['sell_passed'] == 1
        env.db.save_safety_filter_results.assert_called_once()
        assert env.db.save_safety_filter_results.call_args[0][1] == date(2026, 8, 5)

    async def test_stage6_skipped_when_no_active_users(self, env):
        result = await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        assert result['success'] is True
        assert result['stages']['stage6_execution'] == []
        env.decision_gen.generate_user_decision.assert_not_called()
        env.executor.execute_for_user.assert_not_called()

    async def test_trade_date_defaults_to_today(self, env):
        env.kis.is_market_open = AsyncMock(return_value=False)

        result = await env.orchestrator.run_complete_pipeline()

        assert result['trade_date'] == date.today().isoformat()

    def test_sync_wrapper_runs_full_pipeline(self, env):
        result = env.orchestrator.run_complete_pipeline_sync(date(2026, 8, 5))

        assert result['success'] is True
        env.decision_gen.generate_decisions.assert_called_once()


class TestPerUserExecution:
    """Stage 6: 유저별 결정 + 실행 (격리 보장)."""

    @staticmethod
    def _features():
        return _quant_df().merge(_sentiment_df(), on='stock_code').merge(_ts_df(), on='stock_code')

    def _arm(self, env, users, portfolios):
        env.orchestrator.active_users = users
        env.orchestrator.user_portfolio_map = portfolios

    async def test_no_active_users_returns_empty(self, env):
        self._arm(env, [], {})

        results = await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert results == []
        env.decision_gen.generate_user_decision.assert_not_called()

    async def test_buy_quantity_is_min_of_order_limit_and_cash(self, env):
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '000660', 'reason': 'r'}], 'sell': [],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        user_id, buy_orders, sell_orders = env.executor.execute_for_user.await_args[0]
        assert user_id == 1
        # order_amount 100,000 / price 10,000 = 10주 (현금 상한 100주보다 작음)
        # stock_name 은 STOCK_NAMES 매핑에서 채워진다
        assert buy_orders == [{
            'stock_code': '000660', 'stock_name': 'SK하이닉스',
            'quantity': 10, 'price': 10000, 'reason': 'r',
        }]
        assert sell_orders == []

    async def test_trade_date_is_passed_to_executor_for_idempotency_key(self, env):
        """멱등키에 거래일이 들어가야 하므로 Stage 6 는 trade_date 를 넘겨야 한다."""
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '000660', 'reason': 'r'}], 'sell': [],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert env.executor.execute_for_user.await_args.kwargs['trade_date'] == date(2026, 8, 5)

    async def test_unknown_stock_code_falls_back_to_code_as_name(self, env):
        """STOCK_NAMES 에 없는 종목코드는 코드 자체를 이름으로 쓴다."""
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '999999', 'reason': 'r'}], 'sell': [],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert env.executor.execute_for_user.await_args[0][1][0]['stock_name'] == '999999'

    async def test_cash_limits_quantity_when_lower(self, env):
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 1_000_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 25_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '005930', 'reason': 'r'}], 'sell': [],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        buy_orders = env.executor.execute_for_user.await_args[0][1]
        assert buy_orders[0]['quantity'] == 2  # 25,000 / 10,000

    async def test_zero_cash_means_no_buy(self, env):
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 0.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '005930', 'reason': 'r'}], 'sell': [],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert env.executor.execute_for_user.await_args[0][1] == []

    async def test_price_fetch_failure_skips_buy(self, env):
        env.kis.get_current_price = AsyncMock(side_effect=RuntimeError('kis down'))
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '005930', 'reason': 'r'}], 'sell': [],
        })

        results = await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert env.executor.execute_for_user.await_args[0][1] == []
        assert 'error' not in results[0]

    async def test_already_held_stock_is_not_bought_again(self, env):
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {
                'holdings': [{'stock_code': '005930', 'stock_name': '삼성전자',
                              'quantity': 3, 'available_quantity': 3}],
                'cash': 1_000_000.0,
                'holding_codes': ['005930'],
            }},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '005930', 'reason': 'r'}], 'sell': [],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert env.executor.execute_for_user.await_args[0][1] == []
        # 매수 후보가 없으므로 시세 조회도 하지 않는다
        env.kis.get_current_price.assert_not_awaited()

    async def test_sell_uses_available_quantity_of_held_stock(self, env):
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {
                'holdings': [{'stock_code': '000660', 'stock_name': 'SK하이닉스',
                              'quantity': 10, 'available_quantity': 7}],
                'cash': 0.0,
                'holding_codes': ['000660'],
            }},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [], 'sell': [{'stock_code': '000660', 'reason': 'take profit'}],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        sell_orders = env.executor.execute_for_user.await_args[0][2]
        assert sell_orders == [{
            'stock_code': '000660', 'stock_name': 'SK하이닉스',
            'quantity': 7, 'reason': 'take profit',
        }]

    async def test_sell_of_unheld_stock_is_dropped(self, env):
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 0.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [], 'sell': [{'stock_code': '000660', 'reason': 'x'}],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert env.executor.execute_for_user.await_args[0][2] == []

    async def test_decision_failure_is_isolated_per_user(self, env):
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5},
             {'user_id': 2, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []},
             2: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(side_effect=[
            RuntimeError('gemini 429'),
            {'buy': [{'stock_code': '005930', 'reason': 'r'}], 'sell': []},
        ])

        results = await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert len(results) == 2
        assert results[0] == {'user_id': 1, 'error': 'decision failed: gemini 429'}
        assert results[1]['user_id'] == 2
        # 실패 유저는 실행되지 않고, 다음 유저는 정상 실행된다
        assert env.executor.execute_for_user.await_count == 1
        assert env.executor.execute_for_user.await_args[0][0] == 2

    async def test_execution_failure_recorded_and_persisted(self, env):
        env.executor.execute_for_user = AsyncMock(side_effect=RuntimeError('api-server 500'))
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '005930', 'reason': 'r'}], 'sell': [],
        })

        results = await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert results == [{'user_id': 1, 'error': 'execution failed: api-server 500'}]
        # 계획 행은 발행 전에 QUEUED 로 심어져 있고...
        user_id, exec_date, records = env.db.save_trade_execution_plan.call_args[0]
        assert user_id == 1
        assert exec_date == date(2026, 8, 5)
        assert records[0]['execution_status'] == 'QUEUED'
        # ...발행이 통째로 실패했으므로 단건 UPDATE 로 FAILED 확정된다 (배치 재삽입 아님)
        assert env.db.save_trade_execution_plan.call_count == 1
        kwargs = env.db.update_trade_execution_result.call_args.kwargs
        assert kwargs['stock_code'] == '005930'
        assert kwargs['trade_type'] == 'BUY'
        assert kwargs['execution_status'] == 'FAILED'
        assert kwargs['error_message'] == 'execution failed: api-server 500'

    async def test_persistence_failure_does_not_break_execution(self, env):
        env.db.save_trade_execution_plan = MagicMock(side_effect=RuntimeError('db down'))
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '005930', 'reason': 'r'}], 'sell': [],
        })

        results = await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert results[0]['user_id'] == 1
        assert 'error' not in results[0]

    async def test_price_is_cached_across_users(self, env):
        self._arm(
            env,
            [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5},
             {'user_id': 2, 'order_amount': 100_000, 'max_holdings': 5}],
            {1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []},
             2: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}},
        )
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': '005930', 'reason': 'r'}], 'sell': [],
        })

        await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        assert env.kis.get_current_price.await_count == 1

    async def test_missing_portfolio_falls_back_to_empty(self, env):
        env.orchestrator.active_users = [{'user_id': 99}]
        env.orchestrator.user_portfolio_map = {}
        env.decision_gen.generate_user_decision = MagicMock(return_value={'buy': [], 'sell': []})

        results = await env.orchestrator._run_per_user_execution(self._features(), date(2026, 8, 5))

        portfolio = env.decision_gen.generate_user_decision.call_args.kwargs['portfolio']
        assert portfolio == {'holdings': [], 'cash': 0.0, 'total_assets': 0.0, 'holding_codes': []}
        assert results[0]['user_id'] == 99


class _FakeExecutionPlanTable:
    """`trade_execution_plan` 최소 재현 — 실제 repository SQL 시맨틱을 그대로 흉내낸다.

    - `save_trade_execution_plan`: (user_id, execution_date) 일괄 DELETE 후 INSERT
      (`repository.py` 의 DELETE→INSERT 배치와 동일)
    - `update_trade_execution_result`: (user_id, execution_date, stock_code, trade_type)
      4키 UPDATE. 행이 없으면 **0행** 을 돌려준다(= 실 DB 에서 경고만 남고 끝나는 상황).
    """

    def __init__(self):
        self.rows = {}   # (user_id, date, stock_code, trade_type) -> row dict

    def save_trade_execution_plan(self, user_id, execution_date, records):
        for key in [k for k in self.rows if k[0] == user_id and k[1] == execution_date]:
            del self.rows[key]
        for r in records:
            self.rows[(user_id, execution_date, r['stock_code'], r['trade_type'])] = {
                'execution_status': r.get('execution_status') or 'PENDING',
                'order_no': r.get('order_no'),
                'execution_result': dict(r.get('execution_result') or {}),
            }
        return len(records)

    def update_trade_execution_result(self, user_id, execution_date, stock_code, trade_type,
                                      execution_status, order_no=None, error_message=None,
                                      raw_result=None):
        row = self.rows.get((user_id, execution_date, stock_code, trade_type))
        if row is None:
            return 0
        row['execution_status'] = execution_status
        row['order_no'] = order_no or row.get('order_no')
        row['execution_result'].update({'result_message': raw_result or {}})
        if error_message:
            row['execution_result']['error_message'] = error_message
        return 1

    def status_of(self, user_id, execution_date, stock_code, trade_type='BUY'):
        row = self.rows.get((user_id, execution_date, stock_code, trade_type))
        return row['execution_status'] if row else None


class TestFastResultRace:
    """회귀: 결과 메시지가 계획 INSERT 를 앞질러도 최종 상태가 뒤집히지 않아야 한다.

    api-server 가 KIS 를 호출조차 하지 않고 즉시 실패를 내는 경로(계약 위반 메시지,
    DB claim 실패 등)에서는 `trade.order.result` 가 Stage 6 의 계획 저장보다 먼저
    도착할 수 있다. 계획 저장이 발행 **뒤** 에 오면:
      결과 UPDATE 0행 → 배치 INSERT 가 QUEUED 로 (재)생성 → 실패가 영원히 QUEUED.
    여기서는 컨슈머(프로덕션 코드 `TradeResultConsumer.handle`)를 발행 시점에 끼워
    넣어 그 순서를 결정적으로 재현한다.
    """

    TRADE_DATE = date(2026, 8, 5)

    @staticmethod
    def _features():
        return _quant_df().merge(_sentiment_df(), on='stock_code').merge(_ts_df(), on='stock_code')

    @staticmethod
    def _consumer(db_repo):
        from messaging.trade_result_consumer import TradeResultConsumer

        return TradeResultConsumer(db_repo=db_repo, bootstrap_servers='unused:9092')

    def _arm(self, env, table, executor_factory, buy_codes=('005930',)):
        """유저 1명 + 매수 주문 + 지정한 실행 대역으로 Stage 6 를 무장한다."""
        env.orchestrator.db_repo = table
        env.orchestrator.active_users = [{'user_id': 1, 'order_amount': 100_000, 'max_holdings': 5}]
        env.orchestrator.user_portfolio_map = {
            1: {'holdings': [], 'cash': 1_000_000.0, 'holding_codes': []}
        }
        env.decision_gen.generate_user_decision = MagicMock(return_value={
            'buy': [{'stock_code': c, 'reason': 'r'} for c in buy_codes], 'sell': [],
        })
        env.executor.execute_for_user = AsyncMock(side_effect=executor_factory)

    def _instant_result_executor(self, table, delivered, status='FAILED', error='계약 위반'):
        """발행 직후(= Stage 6 가 돌아오기 전) 결과 메시지가 처리되는 실행 대역."""
        from messaging.messages import build_idempotency_key

        consumer = self._consumer(table)

        async def _execute(user_id, buy_orders, sell_orders, trade_date=None):
            buy_results = []
            for o in buy_orders:
                key = build_idempotency_key(user_id, o['stock_code'], trade_date, 'BUY')
                buy_results.append({
                    'stock_code': o['stock_code'],
                    'result': {'success': True, 'status': 'QUEUED', 'idempotency_key': key},
                })
                # ── 레이스 지점: api-server 초고속 실패 → 결과가 즉시 도착 ──
                await consumer.handle({
                    'idempotencyKey': key,
                    'userId': user_id,
                    'stockCode': o['stock_code'],
                    'side': 'BUY',
                    'status': status,
                    'kisOrderNo': None,
                    'errorMessage': error,
                    'processedAt': '2026-08-05T08:55:01+09:00',
                })
                delivered.append(dict(consumer.last_result))
            return {
                'user_id': user_id,
                'trade_date': trade_date.isoformat() if trade_date else None,
                'buy_results': buy_results,
                'sell_results': [],
            }

        return _execute

    async def test_immediate_failure_result_is_not_overwritten_by_plan_insert(self, env):
        """레이스 재현: 즉시 FAILED 결과가 와도 최종 상태는 FAILED 여야 한다 (QUEUED 잔류 금지)."""
        table = _FakeExecutionPlanTable()
        delivered = []
        self._arm(env, table, self._instant_result_executor(table, delivered))

        await env.orchestrator._run_per_user_execution(self._features(), self.TRADE_DATE)

        assert table.status_of(1, self.TRADE_DATE, '005930') == 'FAILED', (
            '결과가 배치 INSERT 보다 먼저 도착해 실패가 QUEUED 로 덮여 버렸다'
        )

    async def test_result_consumer_finds_the_row_already_inserted(self, env):
        """행이 결과 도착 시점에 이미 존재해야 한다 — UPDATE 가 0행이면 안 된다."""
        table = _FakeExecutionPlanTable()
        delivered = []
        self._arm(env, table, self._instant_result_executor(table, delivered))

        await env.orchestrator._run_per_user_execution(self._features(), self.TRADE_DATE)

        assert delivered and delivered[0]['updated_rows'] == 1, (
            f'결과 UPDATE 가 계획 행을 찾지 못했다: {delivered}'
        )

    async def test_immediate_success_result_survives_too(self, env):
        """성공(EXECUTED) 결과가 먼저 도착한 경우도 QUEUED 로 되돌아가면 안 된다."""
        table = _FakeExecutionPlanTable()
        delivered = []
        self._arm(env, table, self._instant_result_executor(table, delivered, status='SUCCESS', error=None))

        await env.orchestrator._run_per_user_execution(self._features(), self.TRADE_DATE)

        assert table.status_of(1, self.TRADE_DATE, '005930') == 'EXECUTED'

    async def test_publish_failure_marks_only_that_row_failed(self, env):
        """발행 실패 주문은 단건 UPDATE 로 FAILED — 이미 확정된 다른 행을 건드리지 않는다."""
        from messaging.messages import build_idempotency_key

        table = _FakeExecutionPlanTable()
        consumer = self._consumer(table)

        async def _execute(user_id, buy_orders, sell_orders, trade_date=None):
            buy_results = []
            for o in buy_orders:
                key = build_idempotency_key(user_id, o['stock_code'], trade_date, 'BUY')
                if o['stock_code'] == '000660':
                    # 프로듀서 레벨 실패 — 결과 메시지는 영영 오지 않는다
                    buy_results.append({'stock_code': o['stock_code'],
                                        'result': {'success': False, 'status': 'FAILED',
                                                   'idempotency_key': key}})
                    continue
                buy_results.append({'stock_code': o['stock_code'],
                                    'result': {'success': True, 'status': 'QUEUED',
                                               'idempotency_key': key}})
                await consumer.handle({
                    'idempotencyKey': key, 'userId': user_id, 'stockCode': o['stock_code'],
                    'side': 'BUY', 'status': 'SUCCESS', 'kisOrderNo': '0000123',
                    'errorMessage': None, 'processedAt': '2026-08-05T08:55:01+09:00',
                })
            return {'user_id': user_id, 'buy_results': buy_results, 'sell_results': []}

        self._arm(env, table, _execute, buy_codes=('005930', '000660'))

        await env.orchestrator._run_per_user_execution(self._features(), self.TRADE_DATE)

        assert table.status_of(1, self.TRADE_DATE, '000660') == 'FAILED'
        # 이미 EXECUTED 로 확정된 행이 배치 재삽입으로 QUEUED 가 되면 안 된다
        assert table.status_of(1, self.TRADE_DATE, '005930') == 'EXECUTED'

    async def test_plan_rows_are_inserted_before_publishing(self, env):
        """호출 순서 자체를 못박는다: save_trade_execution_plan → execute_for_user."""
        calls = []
        table = _FakeExecutionPlanTable()

        class _Recording(_FakeExecutionPlanTable):
            def save_trade_execution_plan(self, user_id, execution_date, records):
                calls.append('save')
                return super().save_trade_execution_plan(user_id, execution_date, records)

        recording = _Recording()

        async def _execute(user_id, buy_orders, sell_orders, trade_date=None):
            calls.append('publish')
            return {'user_id': user_id, 'buy_results': [
                {'stock_code': o['stock_code'], 'result': {'success': True, 'status': 'QUEUED'}}
                for o in buy_orders], 'sell_results': []}

        self._arm(env, recording, _execute)
        del table

        await env.orchestrator._run_per_user_execution(self._features(), self.TRADE_DATE)

        assert calls == ['save', 'publish'], f'발행이 계획 저장보다 먼저 일어났다: {calls}'
        assert recording.status_of(1, self.TRADE_DATE, '005930') == 'QUEUED'


class TestBuildPlannedRecords:
    """trade_execution_plan 초기 레코드 변환 (발행 결과 없이 주문 목록만으로)."""

    TRADE_DATE = date(2026, 8, 9)

    def _build(self, buy_orders, sell_orders):
        return PipelineOrchestrator._build_planned_records(
            1, self.TRADE_DATE, buy_orders, sell_orders
        )

    def test_buy_and_sell_records(self):
        records = self._build(
            [{'stock_code': '005930', 'stock_name': '삼성전자',
              'quantity': 10, 'price': 70000, 'reason': 'buy reason'}],
            [{'stock_code': '000660', 'stock_name': 'SK하이닉스',
              'quantity': 5, 'reason': 'sell reason'}],
        )

        assert len(records) == 2
        buy, sell = records
        assert buy['trade_type'] == 'BUY'
        assert buy['planned_quantity'] == 10
        assert buy['reference_price'] == 70000
        assert buy['estimated_amount'] == 700000
        # 발행 전 초기 상태는 QUEUED — 체결(EXECUTED)은 결과 메시지가 확정한다
        assert buy['execution_status'] == 'QUEUED'
        assert buy['order_no'] is None
        assert buy['gemini_rank'] == 1
        # 멱등키는 결정적이므로 발행 결과 없이도 미리 채울 수 있다
        assert buy['execution_result']['idempotency_key'] == '1:005930:2026-08-09:BUY'

        assert sell['trade_type'] == 'SELL'
        assert sell['planned_quantity'] == 5
        assert sell['reference_price'] is None
        assert sell['estimated_amount'] is None
        assert sell['execution_status'] == 'QUEUED'
        assert sell['order_no'] is None
        assert sell['gemini_rank'] == 1  # 매도 랭크는 별도로 1부터
        assert sell['execution_result']['idempotency_key'] == '1:000660:2026-08-09:SELL'

    def test_zero_price_leaves_nullable_fields_none(self):
        records = self._build([{'stock_code': '005930', 'quantity': 0, 'price': 0}], [])

        assert records[0]['reference_price'] is None
        assert records[0]['estimated_amount'] is None

    def test_empty_orders_produce_no_records(self):
        assert self._build([], []) == []

    def test_ranks_are_sequential(self):
        records = self._build(
            [{'stock_code': 'A', 'quantity': 1, 'price': 1},
             {'stock_code': 'B', 'quantity': 1, 'price': 1}],
            [{'stock_code': 'C', 'quantity': 1}],
        )

        assert [r['gemini_rank'] for r in records] == [1, 2, 1]


class TestPublishFailureMarking:
    """발행 실패 판정 + 단건 FAILED 확정."""

    TRADE_DATE = date(2026, 8, 9)

    @pytest.mark.parametrize('result,expected', [
        ({'success': True, 'status': 'QUEUED'}, False),
        ({'success': False, 'status': 'FAILED'}, True),
        ({}, True),                       # 결과 자체가 없음 = 발행 안 됨
        ({'success': True}, False),       # status 없으면 success 플래그로 판정
        ({'success': False}, True),
        ({'status': 'QUEUED'}, False),    # status 가 있으면 status 우선
    ])
    def test_publish_failure_detection(self, result, expected):
        assert PipelineOrchestrator._publish_failed(result) is expected

    def _run(self, env, exec_result, buy_orders=None, sell_orders=None):
        return env.orchestrator._mark_publish_failures(
            1, self.TRADE_DATE,
            buy_orders if buy_orders is not None else [{'stock_code': '005930', 'quantity': 1}],
            sell_orders or [],
            exec_result,
        )

    def test_failed_publish_is_updated_one_row_at_a_time(self, env):
        marked = self._run(env, {'buy_results': [
            {'stock_code': '005930', 'result': {'success': False, 'status': 'FAILED',
                                                'error': 'broker unreachable'}},
        ]})

        assert marked == 1
        env.db.save_trade_execution_plan.assert_not_called()   # 배치 재삽입 금지
        kwargs = env.db.update_trade_execution_result.call_args.kwargs
        assert kwargs['execution_status'] == 'FAILED'
        assert kwargs['error_message'] == 'broker unreachable'
        assert kwargs['execution_date'] == self.TRADE_DATE

    def test_successful_publish_is_left_alone(self, env):
        marked = self._run(env, {'buy_results': [
            {'stock_code': '005930', 'result': {'success': True, 'status': 'QUEUED'}},
        ]})

        assert marked == 0
        env.db.update_trade_execution_result.assert_not_called()

    def test_missing_result_is_treated_as_publish_failure(self, env):
        """executor 가 통째로 예외를 냈을 때 — 결과가 없으니 전부 FAILED."""
        marked = self._run(env, {'user_id': 1, 'error': 'execution failed: boom'})

        assert marked == 1
        assert env.db.update_trade_execution_result.call_args.kwargs['error_message'] == \
            'execution failed: boom'

    def test_sell_side_is_marked_with_sell_trade_type(self, env):
        marked = self._run(
            env,
            {'sell_results': [{'stock_code': '000660', 'result': {'success': False}}]},
            buy_orders=[],
            sell_orders=[{'stock_code': '000660', 'quantity': 3}],
        )

        assert marked == 1
        assert env.db.update_trade_execution_result.call_args.kwargs['trade_type'] == 'SELL'

    def test_db_error_does_not_propagate(self, env):
        env.db.update_trade_execution_result = MagicMock(side_effect=RuntimeError('db down'))

        assert self._run(env, {'buy_results': [
            {'stock_code': '005930', 'result': {'success': False}},
        ]}) == 0


class TestDartQuarterCalculation:
    """DART 공시 45일 지연을 감안한 최신 분기 계산."""

    @pytest.mark.parametrize('today,expected', [
        (date(2026, 1, 15), date(2025, 12, 31)),
        (date(2026, 4, 30), date(2025, 12, 31)),
        (date(2026, 5, 1), date(2026, 3, 31)),
        (date(2026, 7, 31), date(2026, 3, 31)),
        (date(2026, 8, 1), date(2026, 6, 30)),
        (date(2026, 10, 31), date(2026, 6, 30)),
        (date(2026, 11, 1), date(2026, 9, 30)),
        (date(2026, 12, 31), date(2026, 9, 30)),
    ])
    def test_quarter_boundaries(self, env, today, expected):
        assert env.orchestrator._calculate_latest_dart_quarter(today) == expected

    async def test_pipeline_uses_calculated_quarter(self, env):
        await env.orchestrator.run_complete_pipeline(date(2026, 8, 5))

        kwargs = env.dart.collect_financials_with_fallback.call_args.kwargs
        assert kwargs['start_base_date'] == date(2026, 6, 30)
        assert kwargs['stock_codes'] == SELECTED
        assert kwargs['max_lookback_quarters'] == 5
