package com.inbeom.apiserver.service;

import com.inbeom.apiserver.dto.market.LatestDateResponse;
import com.inbeom.apiserver.dto.market.MarketDecisionsResponse;
import com.inbeom.apiserver.dto.market.MarketHeatmapResponse;
import com.inbeom.apiserver.dto.market.MarketSentimentResponse;
import com.inbeom.apiserver.dto.market.MarketSummaryResponse;
import com.inbeom.apiserver.dto.market.StockAnalysisResponse;
import com.inbeom.apiserver.dto.market.StockDetailAnalysisResponse;
import com.inbeom.apiserver.repository.MarketAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MarketAnalysisService 단위 테스트")
class MarketAnalysisServiceTest {

    @Mock
    private MarketAnalysisRepository marketAnalysisRepository;

    @InjectMocks
    private MarketAnalysisService marketAnalysisService;

    private LocalDate date;

    @BeforeEach
    void setUp() {
        date = LocalDate.of(2026, 8, 6);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ─── getMarketSummary ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getMarketSummary")
    class GetMarketSummaryTest {

        @Test
        @DisplayName("market_daily_summary 존재 - KOSPI/공급수요 포함")
        void withSummary_IncludesKospiAndSupplyDemand() {
            Map<String, Object> summary = mapOf(
                    "kospi_index", new BigDecimal("2650.50"),
                    "kospi_change_rate", new BigDecimal("0.85"),
                    "kospi_volume", 500_000_000L,
                    "total_foreign_net_buy", 1_000_000_000L,
                    "total_institutional_net_buy", 500_000_000L,
                    "market_sentiment_score", new BigDecimal("0.2"),
                    "total_stocks", 30,
                    "rising_stocks", 18,
                    "falling_stocks", 10,
                    "unchanged_stocks", 2);
            Map<String, Object> statistics = mapOf(
                    "total_stocks", 30, "buy_candidate", 3, "sell_candidate", 3, "neutral", 24);

            when(marketAnalysisRepository.getMarketSummary(date)).thenReturn(summary);
            when(marketAnalysisRepository.getStockStatistics(date)).thenReturn(statistics);

            MarketSummaryResponse response = marketAnalysisService.getMarketSummary(date);

            assertThat(response.getDate()).isEqualTo(date);
            assertThat(response.getKospi().getIndex()).isEqualByComparingTo("2650.50");
            assertThat(response.getKospi().getVolume()).isEqualTo(500_000_000L);
            assertThat(response.getSupplyDemand().getForeignNetBuy()).isEqualTo(1_000_000_000L);
            assertThat(response.getMarketSentiment()).isEqualByComparingTo("0.2");
            assertThat(response.getStatistics().getTotal()).isEqualTo(30);
            assertThat(response.getStatistics().getRising()).isEqualTo(18);
            assertThat(response.getStatistics().getBuyCandidate()).isEqualTo(3);
        }

        @Test
        @DisplayName("market_daily_summary 없음 - KOSPI/공급수요 null, stock_filter_score 기반 total 만 채움")
        void withoutSummary_PartialResponse() {
            Map<String, Object> statistics = mapOf(
                    "total_stocks", 30, "buy_candidate", 3, "sell_candidate", 3, "neutral", 24);

            when(marketAnalysisRepository.getMarketSummary(date)).thenReturn(null);
            when(marketAnalysisRepository.getStockStatistics(date)).thenReturn(statistics);

            MarketSummaryResponse response = marketAnalysisService.getMarketSummary(date);

            assertThat(response.getKospi()).isNull();
            assertThat(response.getSupplyDemand()).isNull();
            assertThat(response.getMarketSentiment()).isNull();
            assertThat(response.getStatistics().getTotal()).isEqualTo(30);
            assertThat(response.getStatistics().getRising()).isEqualTo(0);
        }

        @Test
        @DisplayName("summary/statistics 모두 없음 - 0 값으로 안전하게 응답")
        void neitherSummaryNorStatistics_ZeroedResponse() {
            when(marketAnalysisRepository.getMarketSummary(date)).thenReturn(null);
            when(marketAnalysisRepository.getStockStatistics(date)).thenReturn(null);

            MarketSummaryResponse response = marketAnalysisService.getMarketSummary(date);

            assertThat(response.getKospi()).isNull();
            assertThat(response.getStatistics().getTotal()).isEqualTo(0);
            assertThat(response.getStatistics().getBuyCandidate()).isEqualTo(0);
        }

        @Test
        @DisplayName("repository 예외 시 RuntimeException 으로 래핑")
        void repositoryThrows_WrapsInRuntimeException() {
            when(marketAnalysisRepository.getMarketSummary(date))
                    .thenThrow(new RuntimeException("DB down"));

            assertThatThrownBy(() -> marketAnalysisService.getMarketSummary(date))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Failed to get market summary")
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    // ─── getMarketSentiment ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getMarketSentiment")
    class GetMarketSentimentTest {

        @Test
        @DisplayName("시장 전반 sentiment_score 존재 - 분포와 함께 반환")
        void withSentimentScore_ReturnsDistribution() {
            Map<String, Object> distribution = mapOf(
                    "total_count", 30, "positive_count", 12, "neutral_count", 12, "negative_count", 6);
            Map<String, Object> sentiment = mapOf(
                    "sentiment_score", new BigDecimal("0.45"), "distribution", distribution);

            when(marketAnalysisRepository.getMarketSentiment(date)).thenReturn(sentiment);

            MarketSentimentResponse response = marketAnalysisService.getMarketSentiment(date);

            assertThat(response.getScore()).isEqualByComparingTo("0.45");
            assertThat(response.getLabel()).isEqualTo("긍정 우세");
            assertThat(response.getDistribution().getPositive().getCount()).isEqualTo(12);
            assertThat(response.getDistribution().getPositive().getPercent()).isEqualTo(40);
            assertThat(response.getDistribution().getNegative().getPercent()).isEqualTo(20);
        }

        @Test
        @DisplayName("sentiment_score 없으면 종목별 평균(avg_sentiment)으로 폴백")
        void nullScore_FallsBackToStockAverage() {
            Map<String, Object> distribution = mapOf(
                    "total_count", 10, "positive_count", 2, "neutral_count", 6, "negative_count", 2,
                    "avg_sentiment", new BigDecimal("-0.4"));
            Map<String, Object> sentiment = mapOf("sentiment_score", null, "distribution", distribution);

            when(marketAnalysisRepository.getMarketSentiment(date)).thenReturn(sentiment);

            MarketSentimentResponse response = marketAnalysisService.getMarketSentiment(date);

            assertThat(response.getScore()).isEqualByComparingTo("-0.4");
            assertThat(response.getLabel()).isEqualTo("부정 우세");
        }

        @Test
        @DisplayName("시장 감성 데이터 자체가 없으면 0/중립으로 안전하게 응답")
        void noData_ReturnsZeroedNeutralResponse() {
            when(marketAnalysisRepository.getMarketSentiment(date)).thenReturn(null);

            MarketSentimentResponse response = marketAnalysisService.getMarketSentiment(date);

            assertThat(response.getScore()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(response.getLabel()).isEqualTo("중립");
            assertThat(response.getDistribution().getPositive().getCount()).isEqualTo(0);
            assertThat(response.getDistribution().getPositive().getPercent()).isEqualTo(0);
        }
    }

    // ─── getMarketDecisions ─────────────────────────────────────────────────

    @Nested
    @DisplayName("getMarketDecisions")
    class GetMarketDecisionsTest {

        @Test
        @DisplayName("buy/sell 각 1건뿐이면 나머지 2건은 빈 슬롯으로 채운다")
        void fewerThanThree_PadsWithEmptySlots() {
            List<Map<String, Object>> buy = List.of(mapOf(
                    "rank", 1, "stock_code", "005930", "stock_name", "삼성전자",
                    "reason", "긍정적 수급", "confidence_score", new BigDecimal("0.8"),
                    "current_price", 70_000L, "change_rate", new BigDecimal("1.2")));
            List<Map<String, Object>> sell = List.of(mapOf(
                    "rank", 1, "stock_code", "000660", "stock_name", "SK하이닉스",
                    "reason", "차익 실현", "confidence_score", new BigDecimal("0.6"),
                    "current_price", 120_000L, "change_rate", new BigDecimal("-0.5")));

            when(marketAnalysisRepository.getDecisionTop3(date, "buy")).thenReturn(buy);
            when(marketAnalysisRepository.getDecisionTop3(date, "sell")).thenReturn(sell);

            MarketDecisionsResponse response = marketAnalysisService.getMarketDecisions(date);

            assertThat(response.getBuyTop3()).hasSize(3);
            assertThat(response.getBuyTop3().get(0).getStockCode()).isEqualTo("005930");
            assertThat(response.getBuyTop3().get(1).getStockName()).isEqualTo("해당 없음");
            assertThat(response.getBuyTop3().get(1).getStockCode()).isNull();
            assertThat(response.getBuyTop3().get(1).getRank()).isEqualTo(2);
            assertThat(response.getSellTop3()).hasSize(3);
            assertThat(response.getSellTop3().get(0).getStockCode()).isEqualTo("000660");
        }

        @Test
        @DisplayName("confidence_score 가 없으면 0점으로 처리")
        void missingConfidenceScore_DefaultsToZero() {
            List<Map<String, Object>> buy = List.of(mapOf(
                    "rank", 1, "stock_code", "005930", "stock_name", "삼성전자",
                    "reason", "긍정적 수급", "confidence_score", null,
                    "current_price", 70_000L, "change_rate", null));

            when(marketAnalysisRepository.getDecisionTop3(date, "buy")).thenReturn(buy);
            when(marketAnalysisRepository.getDecisionTop3(date, "sell")).thenReturn(List.of());

            MarketDecisionsResponse response = marketAnalysisService.getMarketDecisions(date);

            assertThat(response.getBuyTop3().get(0).getScore()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(response.getSellTop3()).hasSize(3);
            assertThat(response.getSellTop3().get(0).getStockName()).isEqualTo("해당 없음");
        }
    }

    // ─── resolveDate / getLatestAnalysisDate ────────────────────────────────

    @Nested
    @DisplayName("resolveDate / getLatestAnalysisDate")
    class DateResolutionTest {

        @Test
        @DisplayName("resolveDate - date 가 주어지면 그대로 반환하고 repository 를 조회하지 않는다")
        void resolveDate_WithDate_ReturnsAsIs() {
            LocalDate result = marketAnalysisService.resolveDate(date);

            assertThat(result).isEqualTo(date);
            verify(marketAnalysisRepository, never()).getLatestAnalysisDate();
        }

        @Test
        @DisplayName("resolveDate - date 가 null 이면 최신 분석일로 대체")
        void resolveDate_NullDate_FallsBackToLatest() {
            when(marketAnalysisRepository.getLatestAnalysisDate()).thenReturn(date);

            LocalDate result = marketAnalysisService.resolveDate(null);

            assertThat(result).isEqualTo(date);
        }

        @Test
        @DisplayName("resolveDate - 최신 분석일도 없으면 오늘 날짜")
        void resolveDate_NoLatest_FallsBackToToday() {
            when(marketAnalysisRepository.getLatestAnalysisDate()).thenReturn(null);

            LocalDate result = marketAnalysisService.resolveDate(null);

            assertThat(result).isEqualTo(LocalDate.now());
        }

        @Test
        @DisplayName("getLatestAnalysisDate - repository 값을 그대로 감싸 반환")
        void getLatestAnalysisDate_WrapsRepositoryValue() {
            when(marketAnalysisRepository.getLatestAnalysisDate()).thenReturn(date);

            LatestDateResponse response = marketAnalysisService.getLatestAnalysisDate();

            assertThat(response.getLatestDate()).isEqualTo(date);
        }
    }

    // ─── getHeatmapData ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getHeatmapData")
    class GetHeatmapDataTest {

        @Test
        @DisplayName("종목이 없으면 모든 요약 필드가 기본값(0/null)인 빈 응답")
        void emptyStocks_ReturnsZeroedSummary() {
            when(marketAnalysisRepository.getHeatmapData(date)).thenReturn(List.of());

            MarketHeatmapResponse response = marketAnalysisService.getHeatmapData(date);

            assertThat(response.getStocks()).isEmpty();
            MarketHeatmapResponse.HeatmapSummary summary = response.getSummary();
            assertThat(summary.getAvgForeignNetBuy()).isEqualTo(0L);
            assertThat(summary.getAvgSentimentScore()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(summary.getTopStock()).isNull();
            assertThat(summary.getForecastOutlook()).isNotNull();
            assertThat(summary.getForecastOutlook().getRisingCount()).isEqualTo(0);
            assertThat(summary.getFinancialHealth()).isNotNull();
            assertThat(summary.getFinancialHealth().getAvgPer()).isNull();
            assertThat(summary.getSmartMoneyFlow()).isNotNull();
            assertThat(summary.getSmartMoneyFlow().getDominantSignal()).isNull();
            assertThat(summary.getMarketForecastTrend()).isNotNull();
            assertThat(summary.getMarketForecastTrend().getDataCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("수급 양쪽 모두 순매수인 종목이 topStock 과 스마트머니 BOTH_BUY 로 집계된다")
        void bothBuyStock_DrivesTopStockAndSmartMoneySignal() {
            Map<String, Object> bothBuy = mapOf(
                    "stock_code", "005930", "stock_name", "삼성전자",
                    "foreign_net_buy", 1000L, "institutional_net_buy", 2000L,
                    "sentiment_score", new BigDecimal("0.5"),
                    "morning_return", new BigDecimal("0.01"),
                    "per", new BigDecimal("10"), "roe", new BigDecimal("20"), "operating_margin", new BigDecimal("15"),
                    "yhat_d1", new BigDecimal("100"), "yhat_d2", new BigDecimal("101"),
                    "yhat_d3", new BigDecimal("102"), "yhat_d4", new BigDecimal("103"),
                    "yhat_d5", new BigDecimal("110"),
                    "yhat_upper_d5", new BigDecimal("115"), "yhat_lower_d5", new BigDecimal("105"));
            Map<String, Object> bothSell = mapOf(
                    "stock_code", "000660", "stock_name", "SK하이닉스",
                    "foreign_net_buy", -500L, "institutional_net_buy", -300L,
                    "sentiment_score", new BigDecimal("-0.4"),
                    "morning_return", new BigDecimal("-0.01"),
                    "per", new BigDecimal("20"), "roe", new BigDecimal("5"), "operating_margin", new BigDecimal("3"),
                    "yhat_d1", new BigDecimal("200"), "yhat_d2", new BigDecimal("198"),
                    "yhat_d3", new BigDecimal("196"), "yhat_d4", new BigDecimal("194"),
                    "yhat_d5", new BigDecimal("190"),
                    "yhat_upper_d5", new BigDecimal("195"), "yhat_lower_d5", new BigDecimal("185"));

            when(marketAnalysisRepository.getHeatmapData(date)).thenReturn(List.of(bothBuy, bothSell));

            MarketHeatmapResponse response = marketAnalysisService.getHeatmapData(date);
            MarketHeatmapResponse.HeatmapSummary summary = response.getSummary();

            // 수급 평균: (1000-500)/2=250, (2000-300)/2=850
            assertThat(summary.getAvgForeignNetBuy()).isEqualTo(250L);
            assertThat(summary.getAvgInstitutionalNetBuy()).isEqualTo(850L);
            assertThat(summary.getTopStock().getStockCode()).isEqualTo("005930");
            assertThat(summary.getTopStock().getPositiveFeatures()).isEqualTo(5);

            // 스마트머니: 005930 = Q1(both buy), 000660 = Q3(both sell) → consensus 100%, 신호는 Q1==Q3 동률이라 MIXED
            assertThat(summary.getSmartMoneyFlow().getBothBuyCount()).isEqualTo(1);
            assertThat(summary.getSmartMoneyFlow().getBothSellCount()).isEqualTo(1);
            assertThat(summary.getSmartMoneyFlow().getConsensusPct()).isEqualTo(100);
            assertThat(summary.getSmartMoneyFlow().getSmartMoneyTopStock().getStockCode()).isEqualTo("005930");

            // 예측: 005930 expectedReturn5d=+10%, 000660 expectedReturn5d=-5%
            assertThat(summary.getForecastOutlook().getRisingCount()).isEqualTo(1);
            assertThat(summary.getForecastOutlook().getFallingCount()).isEqualTo(1);
            assertThat(summary.getForecastOutlook().getTopOutlookStock().getStockCode()).isEqualTo("005930");

            // 재무: PER 둘 다 양수(10, 20) → median=(10+20)/2=15, 둘 다 15미만은 아님(10만 undervalued)
            assertThat(summary.getFinancialHealth().getAvgPer()).isEqualByComparingTo("15");
            assertThat(summary.getFinancialHealth().getUndervaluedCount()).isEqualTo(1);
            assertThat(summary.getFinancialHealth().getHighRoeCount()).isEqualTo(1);
            assertThat(summary.getFinancialHealth().getDataCoverage()).isEqualTo(100);

            assertThat(summary.getMarketForecastTrend().getDataCount()).isEqualTo(2);
            assertThat(response.getStocks()).hasSize(2);
            assertThat(response.getStocks().get(0).getExpectedReturn5d()).isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("음수 PER(적자기업)은 저평가/평균 계산에서 제외된다")
        void negativePer_ExcludedFromMedianAndUndervalued() {
            Map<String, Object> lossmaker = mapOf(
                    "stock_code", "999999", "stock_name", "적자기업",
                    "foreign_net_buy", null, "institutional_net_buy", null,
                    "per", new BigDecimal("-8"));
            Map<String, Object> healthy = mapOf(
                    "stock_code", "005930", "stock_name", "삼성전자",
                    "foreign_net_buy", null, "institutional_net_buy", null,
                    "per", new BigDecimal("12"));

            when(marketAnalysisRepository.getHeatmapData(date)).thenReturn(List.of(lossmaker, healthy));

            MarketHeatmapResponse response = marketAnalysisService.getHeatmapData(date);

            assertThat(response.getSummary().getFinancialHealth().getAvgPer()).isEqualByComparingTo("12");
            assertThat(response.getSummary().getFinancialHealth().getUndervaluedCount()).isEqualTo(1);
        }
    }

    // ─── getStockAnalysis ───────────────────────────────────────────────────

    @Nested
    @DisplayName("getStockAnalysis")
    class GetStockAnalysisTest {

        @Test
        @DisplayName("date 지정 시 getLatestStockScoreDate 를 호출하지 않는다")
        void withDate_DoesNotResolveLatestDate() {
            when(marketAnalysisRepository.getStockFeatures("005930", date)).thenReturn(null);

            marketAnalysisService.getStockAnalysis("005930", date);

            verify(marketAnalysisRepository, never()).getLatestStockScoreDate(anyString());
        }

        @Test
        @DisplayName("분석 이력이 전혀 없으면 hasAnalysis=false")
        void neverAnalyzed_HasAnalysisFalse() {
            when(marketAnalysisRepository.getLatestStockScoreDate("999999")).thenReturn(null);

            StockAnalysisResponse response = marketAnalysisService.getStockAnalysis("999999", null);

            assertThat(response.getHasAnalysis()).isFalse();
            assertThat(response.getMetrics()).isEmpty();
            assertThat(response.getHeadline()).contains("분석 대상");
        }

        @Test
        @DisplayName("정상 분석 데이터 - headline/metrics 구성")
        void withFeatures_BuildsHeadlineAndMetrics() {
            when(marketAnalysisRepository.getLatestStockScoreDate("005930")).thenReturn(date);
            Map<String, Object> features = mapOf(
                    "stock_name", "삼성전자",
                    "foreign_net_buy", 1_000_000_000L, "institutional_net_buy", 500_000_000L,
                    "vol_avg_multiple", new BigDecimal("1.8"),
                    "sentiment_score", new BigDecimal("0.5"),
                    "news_count", 12,
                    "price_trend", new BigDecimal("0.3"),
                    "per", new BigDecimal("10"), "roe", new BigDecimal("18"), "operating_margin", new BigDecimal("12"),
                    "yhat_d1", new BigDecimal("70000"), "yhat_d5", new BigDecimal("73500"));

            when(marketAnalysisRepository.getStockFeatures("005930", date)).thenReturn(features);

            StockAnalysisResponse response = marketAnalysisService.getStockAnalysis("005930", null);

            assertThat(response.getHasAnalysis()).isTrue();
            assertThat(response.getStockName()).isEqualTo("삼성전자");
            assertThat(response.getExpectedReturn5d()).isEqualByComparingTo("5.00");
            assertThat(response.getHeadline()).contains("외국인·기관 동반 순매수");
            assertThat(response.getMetrics()).extracting(StockAnalysisResponse.Metric::getLabel)
                    .contains("외국인", "기관", "5일 예측", "뉴스 감성", "ROE");
        }
    }

    // ─── getStockDetailAnalysis ─────────────────────────────────────────────

    @Nested
    @DisplayName("getStockDetailAnalysis")
    class GetStockDetailAnalysisTest {

        @Test
        @DisplayName("분석 이력이 없으면 3개 섹션 모두 null")
        void neverAnalyzed_AllSectionsNull() {
            when(marketAnalysisRepository.getLatestStockScoreDate("999999")).thenReturn(null);

            StockDetailAnalysisResponse response = marketAnalysisService.getStockDetailAnalysis("999999", null);

            assertThat(response.getHasAnalysis()).isFalse();
            assertThat(response.getQuant()).isNull();
            assertThat(response.getSentiment()).isNull();
            assertThat(response.getTimeseries()).isNull();
        }

        @Test
        @DisplayName("시장 감성 조회가 실패해도 종목 상세는 정상 반환된다(시장 필드만 null)")
        void marketSentimentFails_DegradesGracefully() {
            when(marketAnalysisRepository.getLatestStockScoreDate("005930")).thenReturn(date);
            Map<String, Object> features = mapOf(
                    "stock_name", "삼성전자",
                    "foreign_net_buy", 1000L, "institutional_net_buy", 2000L,
                    "sentiment_score", new BigDecimal("0.3"), "news_count", 5,
                    "yhat_d1", new BigDecimal("100"), "yhat_upper_d1", new BigDecimal("105"),
                    "yhat_lower_d1", new BigDecimal("95"));

            when(marketAnalysisRepository.getStockDetailFeatures("005930", date)).thenReturn(features);
            when(marketAnalysisRepository.getMarketSentiment(date)).thenThrow(new RuntimeException("boom"));

            StockDetailAnalysisResponse response = marketAnalysisService.getStockDetailAnalysis("005930", null);

            assertThat(response.getHasAnalysis()).isTrue();
            assertThat(response.getSentiment().getStockSentimentScore()).isEqualByComparingTo("0.3");
            assertThat(response.getSentiment().getMarketSentimentScore()).isNull();
            assertThat(response.getSentiment().getMarketDistribution()).isNull();
        }

        @Test
        @DisplayName("yhat_dN 이 있는 날짜만 forecasts 에 포함된다")
        void onlyPresentDaysAreIncludedInForecasts() {
            when(marketAnalysisRepository.getLatestStockScoreDate("005930")).thenReturn(date);
            Map<String, Object> features = mapOf(
                    "stock_name", "삼성전자",
                    "yhat_d1", new BigDecimal("100"),
                    "yhat_upper_d1", new BigDecimal("110"), "yhat_lower_d1", new BigDecimal("90"),
                    "yhat_d3", new BigDecimal("105"),
                    "yhat_upper_d3", new BigDecimal("115"), "yhat_lower_d3", new BigDecimal("95"));

            when(marketAnalysisRepository.getStockDetailFeatures("005930", date)).thenReturn(features);
            when(marketAnalysisRepository.getMarketSentiment(date)).thenReturn(null);

            StockDetailAnalysisResponse response = marketAnalysisService.getStockDetailAnalysis("005930", null);

            assertThat(response.getTimeseries().getForecasts()).hasSize(2);
            assertThat(response.getTimeseries().getForecasts())
                    .extracting(StockDetailAnalysisResponse.Timeseries.Forecast::getDay)
                    .containsExactly("D+1", "D+3");
            // uncertaintyPct = (110-90)/100*100 = 20.0
            assertThat(response.getTimeseries().getForecasts().get(0).getUncertaintyPct())
                    .isEqualByComparingTo("20.0");
        }
    }
}
