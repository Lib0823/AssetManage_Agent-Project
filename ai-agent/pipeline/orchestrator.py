"""Pipeline orchestrator for complete AI trading pipeline."""
import logging
import asyncio
import pandas as pd
from datetime import date
from typing import Optional, Dict, List

from collectors import KISClient, InternalApiClient, KISUnavailableError
from collectors.dart_client import DARTAPIClient
from analysis import StockFilter, QuantitativeAnalyzer, SentimentAnalyzer, TimeSeriesAnalyzer
from ai import TradingDecisionGenerator
from filters import SafetyFilter
from execution import TradeExecutor
from messaging import KafkaMessagePublisher
from messaging.messages import SIDE_BUY, SIDE_SELL, build_idempotency_key
from database import DatabaseRepository
from config.constants import KOSPI_100, STOCK_NAMES
from config import settings

logger = logging.getLogger(__name__)


class PipelineOrchestrator:
    """
    Orchestrates Stage 1 pipeline execution.

    Workflow:
    1. Fetch KOSPI 100 stock data from KIS API
    2. Calculate weighted scores using StandardScaler
    3. Select top 30 stocks (+ holdings)
    4. Save results to database
    """

    def __init__(
        self,
        api_server_url: Optional[str] = None,
        publisher: Optional[KafkaMessagePublisher] = None,
    ):
        resolved_url = api_server_url or settings.api_server_url

        # Stage 1: Filtering
        self.kis_client = KISClient()  # Single shared instance for OAuth token caching
        self.stock_filter = StockFilter()

        # Stage 2: Analysis - pass kis_client to share OAuth token cache
        self.dart_client = DARTAPIClient()  # DART API client for financial data
        self.quant_analyzer = QuantitativeAnalyzer(kis_client=self.kis_client)
        self.sentiment_analyzer = SentimentAnalyzer()
        self.ts_analyzer = TimeSeriesAnalyzer(kis_client=self.kis_client)

        # Stage 4: AI Decision
        self.decision_generator = TradingDecisionGenerator()

        # Database (Stage 5 임계값 조회를 위해 SafetyFilter 보다 먼저 생성)
        self.db_repo = DatabaseRepository()

        # Stage 5: Safety Filter
        # 임계값 단일 출처는 feature_threshold_config 테이블이다. 조회가 실패하거나
        # 행이 없으면 get_feature_thresholds()가 {} 를 돌려주고 SafetyFilter 는
        # 기존 하드코딩 기본값으로 폴백한다 (파이프라인 중단 없음).
        self.safety_filter = SafetyFilter(
            thresholds=self.db_repo.get_feature_thresholds()
        )

        # Internal api-server channel (multi-user holdings / portfolio 조회 전용).
        # 매매 실행은 더 이상 이 REST 채널을 쓰지 않고 Kafka 로 나간다.
        self.internal_api = InternalApiClient(
            base_url=resolved_url, api_key=settings.internal_api_key
        )

        # Stage 6: Trade Execution (per-user, Kafka `trade.order.requested`)
        # lifespan 이 만든 프로듀서를 공유받는다. 단독 실행(테스트/스크립트)이면
        # 자체 인스턴스를 만들고 첫 발행 시 lazy 하게 기동한다.
        self.publisher = publisher or KafkaMessagePublisher()
        self.trade_executor = TradeExecutor(publisher=self.publisher)

        # 멀티유저 컨텍스트 (Step 0-1 에서 채워지고 이후 단계가 사용)
        self.active_users: List[Dict] = []
        self.user_holdings_map: Dict[int, List[str]] = {}
        self.user_portfolio_map: Dict[int, Dict] = {}

        logger.info("PipelineOrchestrator initialized with full pipeline")

    async def run_stage1_filtering(
        self,
        trade_date: Optional[date] = None,
        holdings: Optional[List[str]] = None
    ) -> Dict:
        """
        Execute Stage 1: Stock filtering pipeline.

        Args:
            trade_date: Trade date (defaults to today)
            holdings: Optional list of holdings to always include

        Returns:
            Dict with execution results:
                - success: bool
                - trade_date: date
                - total_stocks: int
                - selected_stocks: int
                - selected_codes: List[str]
                - error: Optional error message
        """
        if trade_date is None:
            trade_date = date.today()

        logger.info(f"Starting Stage 1 filtering for {trade_date}")

        try:
            # Step 0: Check if market is open (avoid unnecessary API calls on holidays)
            logger.info("⏳ Checking market status...")
            try:
                is_open = await self.kis_client.is_market_open(trade_date=trade_date)
            except KISUnavailableError as e:
                # KIS 접속 실패(점검/네트워크) — 휴장일과 명확히 구분해서 보고한다.
                logger.error(f"❌ KIS API 접속 실패로 개장 여부 확인 불가: {e}")
                return {
                    'success': False,
                    'trade_date': trade_date.isoformat(),
                    'error': f'KIS API 접속 실패 (점검 또는 네트워크 문제): {e}',
                    'is_holiday': False,
                    'kis_unavailable': True
                }

            if not is_open:
                logger.warning(f"⚠️ Market is closed on {trade_date} (holiday or non-trading day)")
                return {
                    'success': False,
                    'trade_date': trade_date.isoformat(),
                    'error': '오늘은 휴장일입니다',
                    'is_holiday': True
                }

            # Step 0-1: 활성 자동매매 유저 + 보유종목 합집합 수집 (멀티유저).
            # 사용자 KIS 키는 api-server(DB)에만 있고, ai-agent 는 내부 서비스 채널로
            # 식별자/보유종목만 받는다. 보유종목 합집합을 Top30 분석 유니버스에 강제 포함한다.
            logger.info("Fetching active auto-trading users and holdings from api-server...")
            active_users = await self.internal_api.get_active_auto_trading_users()
            self.active_users = active_users

            # 유저별 포트폴리오 전체(보유상세 + 현금)를 한 번에 조회해 stash.
            # Step 0-1 의 보유 합집합과 Stage 6 의 유저별 매수/매도 판단이 같은 스냅샷을 재사용.
            user_portfolio_map: Dict[int, Dict] = {}
            user_holdings_map: Dict[int, List[str]] = {}
            if active_users:
                portfolios = await asyncio.gather(
                    *[self.internal_api.get_user_portfolio(u['user_id']) for u in active_users],
                    return_exceptions=True
                )
                for user, pf in zip(active_users, portfolios):
                    if isinstance(pf, Exception):
                        logger.error(f"Portfolio fetch failed for user {user['user_id']}: {pf}")
                        pf = {'holdings': [], 'cash': 0.0, 'total_assets': 0.0, 'holding_codes': []}
                    user_portfolio_map[user['user_id']] = pf
                    user_holdings_map[user['user_id']] = pf.get('holding_codes', [])
            self.user_portfolio_map = user_portfolio_map
            self.user_holdings_map = user_holdings_map

            union_holdings = sorted({code for codes in user_holdings_map.values() for code in codes})

            # Merge with explicit parameter holdings (if provided)
            merged = set(holdings or []) | set(union_holdings)
            holdings = sorted(merged) if merged else None

            logger.info(
                f"Active users: {len(active_users)}, "
                f"union holdings ({len(union_holdings)}): {union_holdings}"
            )
            if holdings:
                logger.info(f"{len(holdings)} holdings will be force-included in analysis universe")
            else:
                logger.info("No holdings found, will use only Top 30 filtered stocks")

            # Step 1: Fetch data for KOSPI 100 ∪ holdings (so holdings outside KOSPI100
            # are also analyzable and force-includable in the final selection)
            universe = list(dict.fromkeys(KOSPI_100 + (holdings or [])))
            logger.info(
                f"Fetching data for {len(universe)} stocks "
                f"(KOSPI100 {len(KOSPI_100)} + {len(holdings or [])} holdings)"
            )
            stock_data_df = await self.kis_client.fetch_stock_data_parallel(universe)

            if stock_data_df.empty:
                error_msg = "Failed to fetch stock data from KIS API"
                logger.error(error_msg)
                return {
                    'success': False,
                    'trade_date': trade_date.isoformat(),
                    'error': error_msg
                }

            logger.info(f"Fetched data for {len(stock_data_df)} stocks")

            # Step 2: Calculate scores and filter top 30
            logger.info("Calculating scores and filtering top 30 stocks")
            filtered_df = self.stock_filter.process(stock_data_df, holdings=holdings)

            # Step 3: Save results to database
            logger.info("Saving results to database")
            save_success = self.db_repo.save_filter_scores(filtered_df, trade_date)

            if not save_success:
                error_msg = "Failed to save results to database"
                logger.error(error_msg)
                return {
                    'success': False,
                    'trade_date': trade_date.isoformat(),
                    'error': error_msg
                }

            # Step 4: Save market summary (KOSPI index + market statistics)
            logger.info("Fetching and saving market summary data")
            try:
                # Get KOSPI index data (pass trade_date in YYYYMMDD format)
                kospi_data = await self.kis_client.get_kospi_index(trade_date.strftime('%Y%m%d'))

                # Calculate market statistics from stock_data_df
                market_summary = {
                    'kospi_index': kospi_data.get('kospi_index'),  # Fixed: was 'index'
                    'kospi_change_rate': kospi_data.get('kospi_change_rate'),  # Fixed: was 'change_rate'
                    'kospi_volume': kospi_data.get('kospi_volume'),  # Fixed: was 'volume'
                    'total_stocks': len(stock_data_df),
                    # NOTE: rising/falling/unchanged 은 종목별 등락률(price_change_rate)이
                    # 필요하나, Stage 1 수집기(kis_client.fetch_stock_data_parallel)는 현재
                    # 해당 필드를 반환하지 않는다. 값을 임의로 만들지 않고 None 으로 둔다.
                    # [Cross-team] 이 3개 필드를 채우려면 Stage 1 수집 단계에서 종목별
                    # 등락률을 반환하도록 kis_client 보강이 선행되어야 한다.
                    'rising_stocks': None,
                    'falling_stocks': None,
                    'unchanged_stocks': None,
                    'total_foreign_net_buy': int(stock_data_df['foreign_net_buy'].sum()) if 'foreign_net_buy' in stock_data_df.columns else None,
                    # Fixed: 수집기 컬럼명은 'institutional_net_buy' (이전엔 'institution_net_buy' 오타로 항상 NULL)
                    'total_institutional_net_buy': int(stock_data_df['institutional_net_buy'].sum()) if 'institutional_net_buy' in stock_data_df.columns else None,
                    'market_sentiment_score': None  # Stage 2-2 에서 update_market_sentiment 로 갱신
                }

                market_save_success = self.db_repo.save_market_summary(market_summary, trade_date)
                if not market_save_success:
                    logger.warning("Failed to save market summary (non-critical)")
                else:
                    logger.info(f"Market summary saved: KOSPI={market_summary['kospi_index']:.2f}")
            except Exception as e:
                logger.warning(f"Failed to save market summary: {e} (non-critical)")

            # Extract selected stocks
            selected_df = filtered_df[filtered_df['is_selected'] == True]
            selected_codes = selected_df['stock_code'].tolist()

            logger.info(f"Stage 1 filtering completed successfully: {len(selected_codes)} stocks selected")

            return {
                'success': True,
                'trade_date': trade_date.isoformat(),
                'total_stocks': len(filtered_df),
                'selected_stocks': len(selected_codes),
                'selected_codes': selected_codes,
                'stock_data_df': stock_data_df,  # Pass full Stage 1 data for reuse in Stage 2
                'score_stats': {
                    'min': float(filtered_df['final_score'].min()),
                    'max': float(filtered_df['final_score'].max()),
                    'mean': float(filtered_df['final_score'].mean())
                }
            }

        except Exception as e:
            error_msg = f"Pipeline execution failed: {str(e)}"
            logger.exception(error_msg)
            return {
                'success': False,
                'trade_date': trade_date.isoformat(),
                'error': error_msg
            }

    def run_stage1_sync(
        self,
        trade_date: Optional[date] = None,
        holdings: Optional[List[str]] = None
    ) -> Dict:
        """
        Synchronous wrapper for run_stage1_filtering (for scheduler).

        Args:
            trade_date: Trade date (defaults to today)
            holdings: Optional list of holdings to always include

        Returns:
            Dict with execution results
        """
        return asyncio.run(self.run_stage1_filtering(trade_date, holdings))

    async def run_complete_pipeline(
        self,
        trade_date: Optional[date] = None,
        holdings: Optional[List[str]] = None,
        conn=None
    ) -> Dict:
        """
        Execute complete AI trading pipeline (Stages 1-6).

        Workflow:
        1. Stock Filtering (KOSPI 100 → Top 30)
        2. 3-Way Analysis (Quantitative, Sentiment, Time-Series)
        3. Chart Generation (skipped for now, add later)
        4. Gemini AI Decision (TOP3 buy/sell)
        5. Safety Filter (Feature-based validation)
        6. Trade Execution (KIS API via Spring Boot)

        Args:
            trade_date: Trade date (defaults to today)
            holdings: Optional list of holdings to always include
            conn: Database connection for saving results

        Returns:
            Dict with complete execution results
        """
        if trade_date is None:
            trade_date = date.today()

        logger.info(f"=== Starting Complete Pipeline for {trade_date} ===")

        pipeline_result = {
            'success': False,
            'trade_date': trade_date.isoformat(),
            'stages': {}
        }

        # 유저 조회 실패는 빈 결과로 degrade 되어 반환값으로는 정상과 구분되지 않는다
        # (cash=0 → 매수 스킵, holdings=[] → 매도 후보 전멸). 이번 실행분만 모아
        # 결과에 실어 보낸다.
        self.internal_api.reset_degradations()

        try:
            # Stage 1: Filtering
            logger.info("[Stage 1] Stock Filtering")
            stage1_result = await self.run_stage1_filtering(trade_date, holdings)

            if not stage1_result['success']:
                pipeline_result['stages']['stage1_filtering'] = stage1_result
                pipeline_result['error'] = "Stage 1 filtering failed"
                return pipeline_result

            # Extract DataFrame for Stage 2 (internal use only, not in API response)
            selected_codes = stage1_result['selected_codes']
            stock_data_df = stage1_result.pop('stock_data_df')  # Remove from dict before adding to response

            # Now add stage1_result to response (without DataFrame)
            pipeline_result['stages']['stage1_filtering'] = stage1_result
            logger.info(f"[Stage 1] Selected {len(selected_codes)} stocks")

            # Stage 2: 3-Way Analysis
            logger.info("[Stage 2] 3-Way Analysis")

            # Stage 2-1: Quantitative Analysis (with Stage 1 data reuse)
            logger.info("[Stage 2-1] Quantitative Analysis")

            # Step 1: Collect DART financial data for selected stocks
            logger.info("[Stage 2-1-A] DART financial data collection")
            dart_start_base_date = self._calculate_latest_dart_quarter(trade_date)
            logger.info(f"Using DART start base_date: {dart_start_base_date}")

            # 최신 분기가 아직 공시/적재되지 않은 DART 환경에 대비해, 데이터가 실제로
            # 잡히는 가장 최신 분기까지 분기 단위로 거슬러 올라가며 탐색한다.
            dart_financials_df, dart_used_base_date = self.dart_client.collect_financials_with_fallback(
                stock_codes=selected_codes,
                start_base_date=dart_start_base_date,
                max_lookback_quarters=5
            )

            # Step 1b: Enrich PER from KIS 시세 (DART 재무제표만으로는 PER 산출 불가:
            # 현재가·발행주식수 미포함). KIS inquire-price output.per 로 보강한다.
            # 적자/결측 종목은 None 유지(null-safe).
            if not dart_financials_df.empty:
                per_codes = dart_financials_df['stock_code'].astype(str).tolist()
                per_map = await self.kis_client.get_valuations_for_stocks(per_codes)
                dart_financials_df['per'] = dart_financials_df['stock_code'].astype(str).map(per_map)
                filled = dart_financials_df['per'].notna().sum()
                logger.info(
                    f"[Stage 2-1-A] Enriched PER from KIS for {filled}/{len(dart_financials_df)} stocks"
                )

            # Save DART data to stock_financial table
            if not dart_financials_df.empty:
                dart_save_success = self.dart_client.save_to_database(dart_financials_df)
                logger.info(
                    f"[Stage 2-1-A] Saved {len(dart_financials_df)} DART financial records "
                    f"for quarter base_date={dart_used_base_date}"
                )
            else:
                # 모든 fallback 분기가 실패한 경우에만 경고
                logger.warning(
                    f"[Stage 2-1-A] No DART data collected after fallback "
                    f"(start={dart_start_base_date}, looked back up to 5 quarters)"
                )

            # Step 2: Collect KIS quantitative features (reuse Stage 1 data)
            logger.info("[Stage 2-1-B] KIS quantitative features")
            selected_stage1_data = stock_data_df[stock_data_df['stock_code'].isin(selected_codes)]
            quant_df = await self.quant_analyzer.analyze_stocks(selected_codes, trade_date, stage1_data=selected_stage1_data)

            # Save quantitative features to database (iterate through each stock)
            logger.info("[Stage 2-1-B] Saving quantitative features to database")
            quant_saved_count = 0
            for _, row in quant_df.iterrows():
                if self.db_repo.save_quantitative_features(
                    stock_code=row['stock_code'],
                    trade_date=trade_date,
                    morning_return=row['morning_return'],
                    close_position=row['close_position']
                ):
                    quant_saved_count += 1
            logger.info(f"[Stage 2-1-B] Saved {quant_saved_count} quantitative records")

            logger.info(f"[Stage 2-1] Quantitative analysis complete: {len(quant_df)} stocks")

            # Stage 2-2: Sentiment Analysis
            logger.info("[Stage 2-2] Sentiment Analysis")
            sentiment_df = await self.sentiment_analyzer.analyze_stocks(selected_codes)
            logger.info(f"[Stage 2-2] Sentiment analysis complete: {len(sentiment_df)} stocks")

            # Save sentiment results to database (Track 2: 종목별)
            logger.info("Saving sentiment analysis results to database")
            sentiment_saved_count = 0
            for _, row in sentiment_df.iterrows():
                if self.db_repo.save_sentiment_analysis(
                    stock_code=row['stock_code'],
                    analysis_date=trade_date,
                    sentiment_score=row['sentiment_score'],
                    news_count=int(row['news_count'])  # Fixed: 실제 분석 기사 수 (이전엔 0 하드코딩)
                ):
                    sentiment_saved_count += 1
            logger.info(f"Saved {sentiment_saved_count} sentiment records to news_analysis table")

            # 부가 저장: 종목별 뉴스 기사 원문 + 기사별 감성/태그 (stock_news 테이블).
            # 보조 기능이므로 실패해도 파이프라인을 중단하지 않는다(try/except 내부 격리).
            try:
                stock_news_map = self.sentiment_analyzer.last_stock_news
                news_articles_saved = 0
                news_stocks_saved = 0
                for stock_code, news_records in stock_news_map.items():
                    stock_name = STOCK_NAMES.get(stock_code)
                    inserted = self.db_repo.save_stock_news(
                        stock_code=stock_code,
                        stock_name=stock_name,
                        analysis_date=trade_date,
                        articles=news_records
                    )
                    if inserted > 0:
                        news_stocks_saved += 1
                        news_articles_saved += inserted
                logger.info(
                    f"Saved {news_articles_saved} stock_news articles "
                    f"across {news_stocks_saved} stocks"
                )
            except Exception as e:
                logger.error(f"Failed to persist stock_news (non-critical): {e}")

            # Track 1(시장 전반) 영속화: analyze_stocks 가 인스턴스 속성에 저장한 값을 사용
            market_sentiment = self.sentiment_analyzer.last_market_sentiment
            market_news_count = self.sentiment_analyzer.last_market_news_count

            # (a) market_daily_summary 의 market_sentiment_score 갱신 (Stage 1 에서 insert 된 행)
            self.db_repo.update_market_sentiment(
                summary_date=trade_date,
                market_sentiment_score=market_sentiment
            )

            # (b) news_analysis 에 시장 전반 행 저장 (stock_code=NULL, 설계 §8)
            self.db_repo.save_sentiment_analysis(
                stock_code=None,  # NULL = 시장 전반 (트랙 1)
                analysis_date=trade_date,
                sentiment_score=market_sentiment,
                news_count=market_news_count
            )
            logger.info(
                f"Persisted market sentiment: score={market_sentiment:.4f}, "
                f"news_count={market_news_count}"
            )

            # Stage 2-3: Time-Series Analysis
            logger.info("[Stage 2-3] Time-Series Analysis")
            ts_df = await self.ts_analyzer.analyze_stocks(selected_codes)
            logger.info(f"[Stage 2-3] Time-series analysis complete: {len(ts_df)} stocks")

            # Save time-series results to database
            logger.info("Saving time-series forecast results to database")
            ts_saved_count = 0
            for _, row in ts_df.iterrows():
                forecast_data = row.to_dict()
                if self.db_repo.save_prophet_forecast_detailed(forecast_data, trade_date):
                    ts_saved_count += 1
            logger.info(f"Saved {ts_saved_count} time-series records to prophet_forecast table")

            # Merge all features (for logging purposes)
            features_df = quant_df.merge(sentiment_df, on='stock_code', how='left')
            features_df = features_df.merge(ts_df, on='stock_code', how='left')

            logger.info(f"[Stage 2] Analysis complete: {len(features_df)} stocks with {len(features_df.columns)-1} features")
            pipeline_result['stages']['stage2_analysis'] = {
                'features_count': len(features_df.columns) - 1,  # Exclude stock_code
                'stocks_count': len(features_df)
            }

            # Stage 4: Gemini AI Decision
            logger.info("[Stage 4] Gemini AI Decision")
            gemini_decisions = self.decision_generator.generate_decisions(
                quant_features=quant_df,
                sentiment_features=sentiment_df,
                timeseries_features=ts_df
            )

            # Save to DB
            self.db_repo.save_ai_decisions(gemini_decisions, trade_date)

            logger.info(f"[Stage 4] Gemini decisions: {len(gemini_decisions.get('buy_top3', []))} buys, {len(gemini_decisions.get('sell_top3', []))} sells")
            pipeline_result['stages']['stage4_gemini'] = gemini_decisions

            # Stage 5: Safety Filter
            logger.info("[Stage 5] Safety Filter")

            # Fetch order_amount from user_trade_config (user_id=1 for MVP)
            order_amount = self.db_repo.get_user_order_amount(user_id=1)
            logger.info(f"[Stage 5] User order amount: {order_amount:,}원")

            # Fetch current stock prices for buy candidates
            buy_stock_codes = [item['stock_code'] for item in gemini_decisions.get('buy_top3', [])]
            stock_prices = {}
            if buy_stock_codes:
                for stock_code in buy_stock_codes:
                    price_data = await self.kis_client.get_current_price(stock_code)
                    stock_prices[stock_code] = price_data['current_price']
                logger.info(f"[Stage 5] Fetched prices for {len(stock_prices)} buy candidates")

            # Apply safety filter with investment limit check
            filtered_decisions = self.safety_filter.filter_decisions(
                gemini_decisions,
                features_df,
                stock_prices=stock_prices,
                order_amount=order_amount
            )

            # Save filter results to DB
            self.db_repo.save_safety_filter_results(filtered_decisions['filter_results'], trade_date)

            buy_passed = len(filtered_decisions['buy_top3'])
            sell_passed = len(filtered_decisions['sell_top3'])
            logger.info(f"[Stage 5] Safety filter passed: {buy_passed} buys, {sell_passed} sells")
            pipeline_result['stages']['stage5_safety_filter'] = {
                'buy_passed': buy_passed,
                'sell_passed': sell_passed,
                'filter_results': filtered_decisions['filter_results']
            }

            # Stage 6: Per-user decision + execution (multi-user).
            # 무거운 분석(Stage 1~3)은 공유, Stage 4~5(전역 결정/안전망)는 대시보드용으로 유지.
            # 여기서 각 활성 유저의 포트폴리오 맥락으로 Gemini 1콜 → 안전망(유저 예산/현금) → 실행.
            logger.info("[Stage 6] Per-user decision and execution")
            execution_results = await self._run_per_user_execution(features_df, trade_date)

            logger.info(f"[Stage 6] Per-user execution complete: {len(execution_results)} users")
            pipeline_result['stages']['stage6_execution'] = execution_results

            # Final success
            pipeline_result['success'] = True
            logger.info("=== Complete Pipeline Finished Successfully ===")

            return pipeline_result

        except Exception as e:
            error_msg = f"Pipeline execution failed at stage: {str(e)}"
            logger.exception(error_msg)
            pipeline_result['error'] = error_msg
            return pipeline_result

        finally:
            # 어느 경로로 끝나든 조회 실패 목록은 결과에 남긴다.
            failures = list(self.internal_api.degradations)
            pipeline_result['internal_api_failures'] = failures
            if failures:
                logger.warning(
                    f"[Pipeline] {len(failures)} user lookup(s) degraded to empty results — "
                    f"매수/매도 후보가 실제보다 적을 수 있다: {failures}"
                )

    async def _run_per_user_execution(self, features_df: pd.DataFrame, trade_date: date) -> List[Dict]:
        """
        활성 유저별 맞춤 매수/매도 결정 + 실행 (Stage 6, 멀티유저).

        분석(features_df)은 공유. 각 유저의 포트폴리오 맥락으로 Gemini 1콜(throttle 적용),
        매수는 안전망(SafetyFilter) + 1회 한도/현금으로 수량 산정, 매도는 보유(매도가능) 수량만큼.
        유저 단위로 격리한다(한 유저의 결정/실행 실패가 다른 유저를 막지 않음).

        Args:
            features_df: 분석 유니버스 11피처 (매수 후보 풀)

        Returns:
            유저별 실행 결과 리스트
        """
        results: List[Dict] = []
        if not self.active_users:
            logger.info("[Stage 6] No active auto-trading users; nothing to execute")
            return results

        price_cache: Dict[str, int] = {}

        async def get_price(code: str) -> int:
            if code not in price_cache:
                try:
                    data = await self.kis_client.get_current_price(code)
                    price_cache[code] = int(data.get('current_price') or 0)
                except Exception as e:
                    logger.warning(f"Price fetch failed for {code}: {e}")
                    price_cache[code] = 0
            return price_cache[code]

        for user in self.active_users:
            user_id = user['user_id']
            order_amount = int(user.get('order_amount') or 0)
            max_holdings = int(user.get('max_holdings') or 0)
            portfolio = self.user_portfolio_map.get(
                user_id, {'holdings': [], 'cash': 0.0, 'total_assets': 0.0, 'holding_codes': []}
            )

            # 1) 유저 맞춤 매수/매도 결정 (Gemini 1콜, throttle/백오프는 클라이언트 내부)
            try:
                decision = self.decision_generator.generate_user_decision(
                    candidate_features=features_df,
                    portfolio=portfolio,
                    order_amount=order_amount,
                    max_holdings=max_holdings,
                    user_id=user_id,
                )
            except Exception as e:
                logger.error(f"[Stage 6] Decision failed for user {user_id}: {e}")
                results.append({'user_id': user_id, 'error': f'decision failed: {e}'})
                continue

            held_codes = set(portfolio.get('holding_codes', []))
            held_map = {h['stock_code']: h for h in portfolio.get('holdings', [])}

            # 2) 매수 주문: 이미 보유한 종목 제외 → 안전망(SafetyFilter) → 한도/현금으로 수량 산정
            buy_candidates = [b for b in decision.get('buy', []) if b['stock_code'] not in held_codes]
            buy_prices = {b['stock_code']: await get_price(b['stock_code']) for b in buy_candidates}
            filtered = self.safety_filter.filter_decisions(
                {'buy_top3': buy_candidates, 'sell_top3': []},
                features_df,
                stock_prices=buy_prices,
                order_amount=order_amount,
            )
            cash = float(portfolio.get('cash') or 0)
            buy_orders: List[Dict] = []
            for b in filtered.get('buy_top3', []):
                code = b['stock_code']
                price = buy_prices.get(code, 0)
                if price <= 0:
                    continue
                max_q = int(b.get('max_quantity') or 0)  # order_amount 기반 상한
                cash_q = int(cash / price)               # 가용 현금 기반 상한
                # 둘 중 작은 값. 현금 0/조회 실패 시 cash_q<=0 → qty<=0 → 매수 안 함(안전).
                qty = min(max_q, cash_q)
                if qty <= 0:
                    continue
                cash -= qty * price
                buy_orders.append({
                    'stock_code': code,
                    'stock_name': STOCK_NAMES.get(code, code),
                    'quantity': qty,
                    'price': price,
                    'reason': b.get('reason', ''),
                })

            # 3) 매도 주문: 보유 종목 중 AI 매도 결정, 매도가능 수량만큼
            sell_orders: List[Dict] = []
            for s in decision.get('sell', []):
                h = held_map.get(s['stock_code'])
                if not h:
                    continue
                qty = int(h.get('available_quantity') or h.get('quantity') or 0)
                if qty <= 0:
                    continue
                sell_orders.append({
                    'stock_code': s['stock_code'],
                    'stock_name': h.get('stock_name', s['stock_code']),
                    'quantity': qty,
                    'reason': s.get('reason', ''),
                })

            logger.info(f"[Stage 6] user {user_id}: {len(buy_orders)} buys, {len(sell_orders)} sells planned")

            # 4) 계획 행 선기록 — **반드시 발행보다 먼저**.
            #
            # api-server 가 KIS 를 호출조차 하지 않고 즉시 실패 결과를 내는 경로(계약 위반
            # 메시지, DB claim 실패 등)에서는 `trade.order.result` 가 밀리초 안에 돌아온다.
            # 계획 저장이 발행 뒤에 오면 결과 컨슈머의 UPDATE 가 0행으로 끝나고, 이어지는
            # 배치 INSERT(DELETE 후 INSERT)가 그 종목을 QUEUED 로 되살려 **이미 FAILED 로
            # 확정된 주문이 영구히 QUEUED 로 남는다**. 행이 먼저 존재해야 결과 UPDATE 가
            # 항상 매칭된다 — "행 존재 시점 < 결과 도착 가능 시점".
            try:
                planned_records = self._build_planned_records(
                    user_id, trade_date, buy_orders, sell_orders
                )
                if planned_records:
                    self.db_repo.save_trade_execution_plan(user_id, trade_date, planned_records)
            except Exception as e:
                logger.error(f"[Stage 6] Failed to pre-persist execution plan for user {user_id}: {e}")

            # 5) 실행 요청 발행 (api-server 가 소비해 유저 KIS 키로 대행).
            # 체결 결과는 기다리지 않는다 — `trade.order.result` 컨슈머가 나중에 확정한다.
            try:
                exec_result = await self.trade_executor.execute_for_user(
                    user_id, buy_orders, sell_orders, trade_date=trade_date
                )
            except Exception as e:
                logger.error(f"[Stage 6] Execution failed for user {user_id}: {e}")
                exec_result = {'user_id': user_id, 'error': f'execution failed: {e}'}

            # 6) 발행 자체가 실패한 주문만 단건 UPDATE 로 FAILED 확정 (배치 재삽입 금지)
            self._mark_publish_failures(user_id, trade_date, buy_orders, sell_orders, exec_result)

            results.append(exec_result)

        return results

    # 발행 전에 심는 초기 상태. 'QUEUED' 로 두는 이유:
    #   - 발행 성공이 압도적 다수인데, 'PENDING' 으로 심고 성공 시 QUEUED 로 올리면
    #     그 UPDATE 가 이미 도착한 결과(EXECUTED/FAILED)를 되돌릴 수 있다 — 같은 레이스 재발.
    #   - 발행 실패는 소수이고 결과 메시지가 영영 오지 않으므로, 그때만 FAILED 로 내린다.
    INITIAL_EXECUTION_STATUS = 'QUEUED'

    @staticmethod
    def _build_planned_records(
        user_id: int,
        trade_date: date,
        buy_orders: List[Dict],
        sell_orders: List[Dict],
    ) -> List[Dict]:
        """주문 목록만으로 trade_execution_plan 초기 레코드를 만든다 (발행 결과 불필요).

        발행 전에 INSERT 해야 하므로 발행 결과에 의존할 수 없다. 멱등키는
        `{userId}:{stockCode}:{tradeDate}:{side}` 로 결정적이라 미리 계산해 넣어 두고,
        결과 메시지와 대조할 수 있게 한다. 최종 상태(EXECUTED/FAILED)와 KIS 주문번호는
        `trade.order.result` 컨슈머가 갱신한다.
        """
        records: List[Dict] = []

        def _base(order: Dict, side: str, rank: int) -> Dict:
            return {
                'stock_code': order['stock_code'],
                'stock_name': order.get('stock_name'),
                'trade_type': side,
                'planned_quantity': int(order.get('quantity') or 0),
                'gemini_reason': order.get('reason', ''),
                'gemini_rank': rank,
                'safety_filter_passed': True,
                'execution_status': PipelineOrchestrator.INITIAL_EXECUTION_STATUS,
                # 주문번호는 api-server 가 KIS 주문을 낸 뒤에야 나온다 (결과 메시지에서 채움)
                'order_no': None,
                'execution_result': {
                    'status': PipelineOrchestrator.INITIAL_EXECUTION_STATUS,
                    'idempotency_key': build_idempotency_key(
                        user_id, order['stock_code'], trade_date, side
                    ),
                },
            }

        for i, o in enumerate(buy_orders, 1):
            price = int(o.get('price') or 0)
            qty = int(o.get('quantity') or 0)
            record = _base(o, SIDE_BUY, i)
            record['reference_price'] = price or None
            record['estimated_amount'] = (price * qty) or None
            records.append(record)

        for i, o in enumerate(sell_orders, 1):
            record = _base(o, SIDE_SELL, i)
            record['reference_price'] = None
            record['estimated_amount'] = None
            records.append(record)

        return records

    @staticmethod
    def _publish_failed(result: Dict) -> bool:
        """발행 결과 → 발행 실패인가.

        결과 자체가 없으면(executor 예외로 통째로 못 받은 경우 포함) 실패로 본다.
        """
        if not result:
            return True
        status = result.get('status')
        if status:
            return str(status).upper() != PipelineOrchestrator.INITIAL_EXECUTION_STATUS
        return not result.get('success')

    def _mark_publish_failures(
        self,
        user_id: int,
        trade_date: date,
        buy_orders: List[Dict],
        sell_orders: List[Dict],
        exec_result: Dict,
    ) -> int:
        """Kafka 발행에 실패한 주문만 골라 **해당 행 하나씩** FAILED 로 확정한다.

        발행 실패는 브로커에 메시지가 없다는 뜻이므로 `trade.order.result` 가 영영 오지
        않는다. 여기서 확정하지 않으면 QUEUED 로 방치된다. 배치 재삽입
        (`save_trade_execution_plan`)을 쓰면 그 사이 도착한 다른 주문의 결과가 지워지므로
        반드시 단건 UPDATE 를 쓴다.

        Returns:
            int: FAILED 로 내린 행 수
        """
        marked = 0
        groups = (
            (SIDE_BUY, buy_orders, 'buy_results'),
            (SIDE_SELL, sell_orders, 'sell_results'),
        )
        for side, orders, result_key in groups:
            published = {
                r['stock_code']: (r.get('result') or {})
                for r in (exec_result.get(result_key) or [])
            }
            for order in orders:
                res = published.get(order['stock_code'], {})
                if not self._publish_failed(res):
                    continue
                error_message = (
                    res.get('error') or exec_result.get('error') or 'kafka publish failed'
                )
                try:
                    marked += self.db_repo.update_trade_execution_result(
                        user_id=user_id,
                        execution_date=trade_date,
                        stock_code=order['stock_code'],
                        trade_type=side,
                        execution_status='FAILED',
                        error_message=error_message,
                        raw_result=res or {'success': False},
                    ) or 0
                except Exception as e:
                    logger.error(
                        f"[Stage 6] Failed to mark publish failure "
                        f"(user={user_id} {order['stock_code']} {side}): {e}"
                    )
        if marked:
            logger.warning(f"[Stage 6] user {user_id}: {marked} order(s) marked FAILED (publish failed)")
        return marked

    def run_complete_pipeline_sync(
        self,
        trade_date: Optional[date] = None,
        holdings: Optional[List[str]] = None,
        conn=None
    ) -> Dict:
        """
        Synchronous wrapper for run_complete_pipeline (for scheduler).

        Args:
            trade_date: Trade date (defaults to today)
            holdings: Optional list of holdings to always include
            conn: Database connection

        Returns:
            Dict with complete execution results
        """
        return asyncio.run(self.run_complete_pipeline(trade_date, holdings, conn))

    def _calculate_latest_dart_quarter(self, today: date) -> date:
        """
        Calculate latest available DART quarter based on current date.

        DART disclosure timeline: ~45 days after quarter end
        - Q1 (3/31): Available from mid-May
        - Q2 (6/30): Available from mid-August
        - Q3 (9/30): Available from mid-November
        - Q4 (12/31): Available from early March (next year)

        Args:
            today: Current date

        Returns:
            Latest available quarter end date
        """
        year = today.year
        month = today.month

        # Consider 45-day disclosure delay
        if month >= 11:  # November onwards
            return date(year, 9, 30)  # Q3
        elif month >= 8:  # August onwards
            return date(year, 6, 30)  # Q2
        elif month >= 5:  # May onwards
            return date(year, 3, 31)  # Q1
        else:  # January-April
            return date(year - 1, 12, 31)  # Previous year Q4
