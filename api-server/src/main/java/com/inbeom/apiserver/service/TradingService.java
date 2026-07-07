package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.dto.kis.KisBalanceResponse;
import com.inbeom.apiserver.dto.kis.KisDailyCcldResponse;
import com.inbeom.apiserver.dto.trade.BalanceSummaryResponse;
import com.inbeom.apiserver.dto.trade.HoldingResponse;
import com.inbeom.apiserver.dto.trade.OrderableResponse;
import com.inbeom.apiserver.dto.trade.PendingOrderResponse;
import com.inbeom.apiserver.dto.trade.RecentTradeResponse;
import com.inbeom.apiserver.dto.trade.TradeHistoryResponse;
import com.inbeom.apiserver.exception.KisApiException;
import com.inbeom.apiserver.exception.UserNotFoundException;
import com.inbeom.apiserver.repository.TradeExecutionPlanRepository;
import com.inbeom.apiserver.repository.TradeHistoryRepository;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.service.KisAuthService.KisCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingService {

    private final KisAuthService kisAuthService;
    private final KisApiClient kisApiClient;
    private final UserRepository userRepository;
    private final TradeHistoryRepository tradeHistoryRepository;
    private final TradeExecutionPlanRepository tradeExecutionPlanRepository;

    /**
     * 홈 알림용 최근 거래내역 (DB trade_history 기반, 최신순 최대 8건).
     * KIS 라이브 호출이 아니므로 KIS 장애와 무관하게 빠르고 안정적이며, 실패해도 빈 목록을 반환한다.
     */
    public List<RecentTradeResponse> getRecentTrades(Long userId) {
        try {
            return tradeHistoryRepository.findByUserIdOrderByOrderedAtDesc(userId).stream()
                    .limit(8)
                    .map(t -> RecentTradeResponse.builder()
                            .id(t.getId())
                            .stockCode(t.getStockCode())
                            .stockName(t.getStockName())
                            .orderType(t.getOrderType())
                            .orderStatus(t.getOrderStatus())
                            .quantity(t.getQuantity())
                            .orderPrice(t.getOrderPrice())
                            .executedPrice(t.getExecutedPrice())
                            .orderedAt(t.getOrderedAt())
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load recent trades from DB for userId={}: {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Execute buy order via KIS API (VTTC0802U)
     * Note: Trade history is fetched from KIS API directly, not stored in DB
     */
    public Map<String, Object> executeBuy(Long userId, Long kisAccountId, String stockCode, String stockName,
                                           Integer quantity, BigDecimal orderPrice) {
        // 1. Get KIS credentials and token
        String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
        KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

        // 2. Build request body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("CANO", credentials.accountNumber());
        requestBody.put("ACNT_PRDT_CD", credentials.accountProductCode());
        requestBody.put("PDNO", stockCode);
        requestBody.put("ORD_DVSN", "01"); // 시장가
        requestBody.put("ORD_QTY", String.valueOf(quantity));
        requestBody.put("ORD_UNPR", "0"); // 시장가는 0

        // 3. Call KIS API
        ResponseEntity<Map> response = kisApiClient.post(
                credentials.baseUrl(),
                "/uapi/domestic-stock/v1/trading/order-cash",
                "VTTC0802U",
                kisToken,
                credentials.appKey(),
                credentials.appSecret(),
                requestBody,
                Map.class
        );

        verifyKisOrderSuccess(response.getBody());
        log.info("Buy order executed for userId={}, stockCode={}, quantity={}, orderNumber={}",
                userId, stockCode, quantity, extractOrderNumber(response.getBody()));

        return response.getBody();
    }

    /**
     * Execute sell order via KIS API (VTTC0801U)
     * Note: Trade history is fetched from KIS API directly, not stored in DB
     */
    public Map<String, Object> executeSell(Long userId, Long kisAccountId, String stockCode, String stockName,
                                            Integer quantity, BigDecimal orderPrice) {
        String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
        KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("CANO", credentials.accountNumber());
        requestBody.put("ACNT_PRDT_CD", credentials.accountProductCode());
        requestBody.put("PDNO", stockCode);
        requestBody.put("ORD_DVSN", "01");
        requestBody.put("ORD_QTY", String.valueOf(quantity));
        requestBody.put("ORD_UNPR", "0");

        ResponseEntity<Map> response = kisApiClient.post(
                credentials.baseUrl(),
                "/uapi/domestic-stock/v1/trading/order-cash",
                "VTTC0801U",
                kisToken,
                credentials.appKey(),
                credentials.appSecret(),
                requestBody,
                Map.class
        );

        verifyKisOrderSuccess(response.getBody());
        log.info("Sell order executed for userId={}, stockCode={}, quantity={}, orderNumber={}",
                userId, stockCode, quantity, extractOrderNumber(response.getBody()));

        return response.getBody();
    }

    /**
     * Get trade history from KIS API (VTTC0081R)
     * 최근 3개월 거래내역 조회
     */
    public List<TradeHistoryResponse> getTradeHistory(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 1. Get KIS account from user
        Long kisAccountId = user.getKisAccount().getId();

        // 2. Get KIS credentials and token
        String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
        KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

        // 3. Build query parameters for last 3 months
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusMonths(3);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("CANO", credentials.accountNumber());
        queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
        queryParams.put("INQR_STRT_DT", startDate.format(formatter));
        queryParams.put("INQR_END_DT", endDate.format(formatter));
        queryParams.put("SLL_BUY_DVSN_CD", "00");  // 00: 전체, 01: 매도, 02: 매수
        queryParams.put("INQR_DVSN", "00");  // 00: 역순
        queryParams.put("PDNO", "");  // 전체 종목
        queryParams.put("CCLD_DVSN", "00");  // 00: 전체
        queryParams.put("ORD_GNO_BRNO", "");
        queryParams.put("ODNO", "");
        queryParams.put("INQR_DVSN_3", "00");
        queryParams.put("INQR_DVSN_1", "");
        queryParams.put("CTX_AREA_FK100", "");
        queryParams.put("CTX_AREA_NK100", "");

        // 4. Call KIS API
        ResponseEntity<KisDailyCcldResponse> response = kisApiClient.get(
                credentials.baseUrl(),
                "/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
                "VTTC0081R",  // 주식일별주문체결조회 (모의투자)
                kisToken,
                credentials.appKey(),
                credentials.appSecret(),
                queryParams,
                KisDailyCcldResponse.class
        );

        // 5. Map KIS response to TradeHistoryResponse
        if (response.getBody() == null || response.getBody().getOutput1() == null) {
            log.warn("Empty trade history response from KIS API for userId={}", userId);
            return new ArrayList<>();
        }

        // 봇(AI) 자동매매 주문번호(ODNO) 집합 — 거래내역에 AI 매매 배지를 달기 위한 매칭.
        // DB 조회 실패는 비핵심이므로 빈 집합으로 degrade(거래내역 자체는 정상 반환).
        // 봇(AI) 자동매매 주문 키 집합 "yyyy-MM-dd|ODNO" — 거래내역 AI 배지 매칭.
        // ODNO는 당일 채번이라 (주문일자 + 주문번호)로 매칭해야 다른 날 동일 ODNO 오매칭을 방지한다.
        // DB 조회 실패는 비핵심이므로 빈 집합으로 degrade(거래내역 자체는 정상 반환).
        Set<String> aiKeys;
        try {
            aiKeys = tradeExecutionPlanRepository.findExecutedOrderKeys(userId);
        } catch (Exception e) {
            log.warn("Failed to load AI order keys for userId={}: {}", userId, e.getMessage());
            aiKeys = java.util.Collections.emptySet();
        }
        final Set<String> aiSet = aiKeys;

        return response.getBody().getOutput1().stream()
                .map(this::mapToTradeHistoryResponse)
                .map(t -> {
                    t.setAiTraded(aiSet.contains(t.getOrderDate() + "|" + t.getId()));
                    return t;
                })
                .collect(Collectors.toList());
    }

    /**
     * Get pending (unfilled) orders from KIS API (VTTC0081R).
     * PM 결정 1: 신규 KIS TR 도입 금지. getTradeHistory 와 동일한 inquire-daily-ccld 경로를 재사용하고,
     * 결과 중 미체결(취소 제외, 잔량 > 0 또는 orderStatus PENDING/PARTIAL)인 행만 반환한다.
     * 예외/빈결과/rt_cd != 0 시 빈 리스트로 graceful 처리한다.
     */
    public List<PendingOrderResponse> getPendingOrders(Long userId) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException(userId));

            // 1. Get KIS account from user
            Long kisAccountId = user.getKisAccount().getId();

            // 2. Get KIS credentials and token
            String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
            KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

            // 3. Build query parameters for last 3 months (getTradeHistory 와 동일)
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusMonths(3);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("CANO", credentials.accountNumber());
            queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
            queryParams.put("INQR_STRT_DT", startDate.format(formatter));
            queryParams.put("INQR_END_DT", endDate.format(formatter));
            queryParams.put("SLL_BUY_DVSN_CD", "00");  // 00: 전체, 01: 매도, 02: 매수
            queryParams.put("INQR_DVSN", "00");  // 00: 역순
            queryParams.put("PDNO", "");  // 전체 종목
            queryParams.put("CCLD_DVSN", "02");  // 00: 전체, 01: 체결, 02: 미체결
            queryParams.put("ORD_GNO_BRNO", "");
            queryParams.put("ODNO", "");
            queryParams.put("INQR_DVSN_3", "00");
            queryParams.put("INQR_DVSN_1", "");
            queryParams.put("CTX_AREA_FK100", "");
            queryParams.put("CTX_AREA_NK100", "");

            // 4. Call KIS API
            ResponseEntity<KisDailyCcldResponse> response = kisApiClient.get(
                    credentials.baseUrl(),
                    "/uapi/domestic-stock/v1/trading/inquire-daily-ccld",
                    "VTTC0081R",  // 주식일별주문체결조회 (모의투자)
                    kisToken,
                    credentials.appKey(),
                    credentials.appSecret(),
                    queryParams,
                    KisDailyCcldResponse.class
            );

            // 5. Validate response
            KisDailyCcldResponse body = response.getBody();
            if (body == null || body.getOutput1() == null) {
                log.warn("Empty pending orders response from KIS API for userId={}", userId);
                return new ArrayList<>();
            }
            if (body.getRtCd() != null && !"0".equals(body.getRtCd())) {
                log.warn("KIS pending orders rt_cd={} msg={} for userId={}", body.getRtCd(), body.getMsg1(), userId);
                return new ArrayList<>();
            }

            // 6. Filter unfilled rows (취소 제외, 잔량 > 0 또는 PENDING/PARTIAL) and map
            return body.getOutput1().stream()
                    .filter(this::isPending)
                    .map(this::mapToPendingOrderResponse)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load pending orders from KIS API for userId={}: {}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 미체결 판정: 취소된 주문은 제외하고, 잔여수량(rmn_qty) > 0 이거나
     * 주문상태가 PENDING/PARTIAL(총체결수량 < 주문수량)인 경우 미체결로 본다.
     */
    private boolean isPending(KisDailyCcldResponse.DailyCcldItem item) {
        if ("Y".equals(item.getCnclYn())) {
            return false;
        }
        int remain = calcRemainQuantity(item);
        if (remain > 0) {
            return true;
        }
        int totalQty = parseIntSafely(item.getTotCcldQty());
        int orderQty = parseIntSafely(item.getOrdQty());
        // PENDING(미체결) 또는 PARTIAL(일부체결) → 총체결수량 < 주문수량
        return orderQty > 0 && totalQty < orderQty;
    }

    /**
     * 잔여수량 계산: KIS rmn_qty 우선, 없거나 0이면 주문수량 - 총체결수량으로 보정.
     */
    private int calcRemainQuantity(KisDailyCcldResponse.DailyCcldItem item) {
        int rmnQty = parseIntSafely(item.getRmnQty());
        if (rmnQty > 0) {
            return rmnQty;
        }
        int orderQty = parseIntSafely(item.getOrdQty());
        int totalQty = parseIntSafely(item.getTotCcldQty());
        return Math.max(orderQty - totalQty, 0);
    }

    /**
     * Map KIS DailyCcldItem to PendingOrderResponse
     */
    private PendingOrderResponse mapToPendingOrderResponse(KisDailyCcldResponse.DailyCcldItem item) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HHmmss");
        DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        LocalDate orderDate = LocalDate.parse(item.getOrdDt(), dateFormatter);
        LocalDateTime orderedAt;
        if (item.getOrdTmd() != null && !item.getOrdTmd().isEmpty()) {
            String ordTmd = item.getOrdTmd().length() == 6 ? item.getOrdTmd() : String.format("%06d", Integer.parseInt(item.getOrdTmd()));
            orderedAt = LocalDateTime.of(orderDate, java.time.LocalTime.parse(ordTmd, timeFormatter));
        } else {
            orderedAt = orderDate.atStartOfDay();
        }

        // 01=매도(SELL), 02=매수(BUY)
        String orderType = "02".equals(item.getSllBuyDvsnCd()) ? "BUY" : "SELL";

        return PendingOrderResponse.builder()
                .orderNumber(item.getOdno())
                .stockCode(item.getPdno())
                .stockName(item.getPrdtName())
                .orderType(orderType)
                .orderQuantity(parseIntSafely(item.getOrdQty()))
                .remainQuantity(calcRemainQuantity(item))
                .orderPrice(parseBigDecimalSafely(item.getOrdUnpr()))
                .orderedAt(orderedAt.format(displayFormatter))
                .build();
    }

    /**
     * Map KIS DailyCcldItem to TradeHistoryResponse
     */
    private TradeHistoryResponse mapToTradeHistoryResponse(KisDailyCcldResponse.DailyCcldItem item) {
        // Parse date and time
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HHmmss");
        DateTimeFormatter dateDisplayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter timeDisplayFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        LocalDate orderDate = LocalDate.parse(item.getOrdDt(), dateFormatter);
        LocalDateTime orderedAt;

        if (item.getOrdTmd() != null && !item.getOrdTmd().isEmpty()) {
            String ordTmd = item.getOrdTmd().length() == 6 ? item.getOrdTmd() : String.format("%06d", Integer.parseInt(item.getOrdTmd()));
            orderedAt = LocalDateTime.of(
                    orderDate,
                    java.time.LocalTime.parse(ordTmd, timeFormatter)
            );
        } else {
            orderedAt = orderDate.atStartOfDay();
        }

        // Determine order type: 01=매도(SELL), 02=매수(BUY)
        String orderType = "02".equals(item.getSllBuyDvsnCd()) ? "BUY" : "SELL";

        // Determine order status based on execution
        String orderStatus;
        int totalQty = parseIntSafely(item.getTotCcldQty());
        int orderQty = parseIntSafely(item.getOrdQty());
        boolean isCancelled = "Y".equals(item.getCnclYn());

        if (isCancelled) {
            orderStatus = "CANCELLED";
        } else if (totalQty == 0) {
            orderStatus = "PENDING";
        } else if (totalQty < orderQty) {
            orderStatus = "PARTIAL";
        } else {
            orderStatus = "COMPLETED";
        }

        return TradeHistoryResponse.builder()
                .id(item.getOdno())
                .stockCode(item.getPdno())
                .stockName(item.getPrdtName())
                .orderType(orderType)
                .orderStatus(orderStatus)
                .quantity(orderQty)
                .orderPrice(parseBigDecimalSafely(item.getOrdUnpr()))
                .executedPrice(parseBigDecimalSafely(item.getAvgPrvs()))
                .executedQuantity(totalQty)
                .orderedAt(orderedAt)
                .orderDate(orderDate.format(dateDisplayFormatter))
                .orderTime(orderedAt.toLocalTime().format(timeDisplayFormatter))
                .build();
    }

    /**
     * 매수가능조회 (VTTC8908R, 모의). userId → KIS 계좌/토큰/자격증명 해석 후 inquire-psbl-order 호출.
     * KIS output 매핑: max_buy_qty→maxBuyQuantity, ord_psbl_cash→orderableCash.
     * 예외/rt_cd != 0 시 0 + notice 로 graceful degrade 한다 (절대 예외 전파하지 않음).
     *
     * @param price 주문단가(지정가). null/0 이면 "0" 전송.
     */
    public OrderableResponse getOrderable(Long userId, String stockCode, BigDecimal price) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new UserNotFoundException(userId));

            // 1. Get KIS account from user
            Long kisAccountId = user.getKisAccount().getId();

            // 2. Get KIS credentials and token
            String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
            KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

            // 3. Build query parameters
            String ordUnpr = (price == null || price.compareTo(BigDecimal.ZERO) == 0)
                    ? "0"
                    : price.toPlainString();

            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("CANO", credentials.accountNumber());
            queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
            queryParams.put("PDNO", stockCode);
            queryParams.put("ORD_UNPR", ordUnpr);
            queryParams.put("ORD_DVSN", "00");  // 00: 지정가
            queryParams.put("CMA_EVLU_AMT_ICLD_YN", "N");  // CMA평가금액포함여부
            queryParams.put("OVRS_ICLD_YN", "N");  // 해외포함여부

            // 4. Call KIS API
            ResponseEntity<Map> response = kisApiClient.get(
                    credentials.baseUrl(),
                    "/uapi/domestic-stock/v1/trading/inquire-psbl-order",
                    "VTTC8908R",  // 매수가능조회 (모의투자, convertTrId 가 VTTC↔TTTC 처리)
                    kisToken,
                    credentials.appKey(),
                    credentials.appSecret(),
                    queryParams,
                    Map.class
            );

            // 5. Validate response
            Map<String, Object> body = response.getBody();
            if (body == null || !"0".equals(String.valueOf(body.get("rt_cd")))) {
                log.warn("KIS inquire-psbl-order rt_cd={} msg={} for userId={}, stockCode={}",
                        body != null ? body.get("rt_cd") : "null",
                        body != null ? body.get("msg1") : "null body", userId, stockCode);
                return orderableFailure(stockCode);
            }

            Object output = body.get("output");
            if (!(output instanceof Map)) {
                log.warn("KIS inquire-psbl-order missing output for userId={}, stockCode={}", userId, stockCode);
                return orderableFailure(stockCode);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> outputMap = (Map<String, Object>) output;

            return OrderableResponse.builder()
                    .stockCode(stockCode)
                    .maxBuyQuantity(parseLongSafely(asString(outputMap.get("max_buy_qty"))))
                    .orderableCash(parseLongSafely(asString(outputMap.get("ord_psbl_cash"))))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load orderable info from KIS API for userId={}, stockCode={}: {}",
                    userId, stockCode, e.getMessage());
            return orderableFailure(stockCode);
        }
    }

    /**
     * 매수가능조회 실패 시 0 + 안내 메시지로 degrade.
     */
    private OrderableResponse orderableFailure(String stockCode) {
        return OrderableResponse.builder()
                .stockCode(stockCode)
                .maxBuyQuantity(0L)
                .orderableCash(0L)
                .notice("주문가능 정보를 불러오지 못했습니다")
                .build();
    }

    /**
     * KIS output 값(Object)을 trim 된 String 으로 변환. null/공백이면 null.
     */
    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Safely parse String to Long (콤마 제거, 소수점 절삭). 실패 시 0.
     */
    private long parseLongSafely(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            String cleaned = value.trim().replace(",", "");
            if (cleaned.contains(".")) {
                return (long) Double.parseDouble(cleaned);
            }
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse long: {}", value);
            return 0L;
        }
    }

    /**
     * Safely parse String to Integer
     */
    private int parseIntSafely(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse int: {}", value);
            return 0;
        }
    }

    /**
     * Safely parse String to BigDecimal
     */
    private BigDecimal parseBigDecimalSafely(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse BigDecimal: {}", value);
            return BigDecimal.ZERO;
        }
    }

    /**
     * KIS 주문 응답의 성공 여부(rt_cd)를 검증한다.
     *
     * <p>KIS 는 주문 거부 시에도 HTTP 200 + {@code rt_cd="1"} 과 사유({@code msg1},
     * 예: "모의투자 영업일이 아닙니다.")를 함께 준다. 이를 검사하지 않으면 실패 주문이
     * 성공(success=true)으로 잘못 보고되거나 후속 처리에서 generic 500 으로 사유가
     * 가려진다. rt_cd 가 "0"(정상)이 아니면 KIS 사유를 담아 예외를 던져 호출자
     * (ai-agent)까지 명확한 메시지가 전달되게 한다.
     */
    private void verifyKisOrderSuccess(Map<String, Object> kisResponse) {
        if (kisResponse == null) {
            throw KisApiException.serverError("KIS 주문 응답이 비어 있습니다.");
        }
        Object rtCd = kisResponse.get("rt_cd");
        if (!"0".equals(String.valueOf(rtCd))) {
            String msg = String.valueOf(kisResponse.getOrDefault("msg1", "KIS 주문이 거부되었습니다."));
            throw KisApiException.serverError("KIS order rejected: " + msg.trim() + " (rt_cd=" + rtCd + ")");
        }
    }

    private String extractOrderNumber(Map<String, Object> kisResponse) {
        if (kisResponse != null && kisResponse.containsKey("output")) {
            Map<String, Object> output = (Map<String, Object>) kisResponse.get("output");
            return (String) output.get("ODNO");
        }
        return null;
    }

    /**
     * Get holdings (보유 종목 조회) from KIS API (VTTC8434R)
     */
    public BalanceSummaryResponse getHoldings(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // 1. Get KIS account from user
        Long kisAccountId = user.getKisAccount().getId();

        // 2. Get KIS credentials and token
        String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
        KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

        // 3. Build query parameters
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("CANO", credentials.accountNumber());
        queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
        queryParams.put("AFHR_FLPR_YN", "N");  // 시간외단일가여부
        queryParams.put("OFL_YN", "");  // 오프라인여부
        queryParams.put("INQR_DVSN", "02");  // 조회구분: 01-대출일별, 02-종목별
        queryParams.put("UNPR_DVSN", "01");  // 단가구분: 01-기본값
        queryParams.put("FUND_STTL_ICLD_YN", "N");  // 펀드결제분포함여부
        queryParams.put("FNCG_AMT_AUTO_RDPT_YN", "N");  // 융자금액자동상환여부
        queryParams.put("PRCS_DVSN", "01");  // 처리구분: 00-전일, 01-당일
        queryParams.put("CTX_AREA_FK100", "");  // 연속조회검색조건100
        queryParams.put("CTX_AREA_NK100", "");  // 연속조회키100

        // 4. Call KIS API
        ResponseEntity<KisBalanceResponse> response = kisApiClient.get(
                credentials.baseUrl(),
                "/uapi/domestic-stock/v1/trading/inquire-balance",
                "VTTC8434R",  // 주식잔고조회 (모의투자)
                kisToken,
                credentials.appKey(),
                credentials.appSecret(),
                queryParams,
                KisBalanceResponse.class
        );

        // 5. Map KIS response to BalanceSummaryResponse
        if (response.getBody() == null) {
            log.warn("Empty balance response from KIS API for userId={}", userId);
            return BalanceSummaryResponse.builder()
                    .holdings(new ArrayList<>())
                    .totalEvaluationAmount(BigDecimal.ZERO)
                    .totalPurchaseAmount(BigDecimal.ZERO)
                    .totalProfitLoss(BigDecimal.ZERO)
                    .totalProfitLossRate(BigDecimal.ZERO)
                    .cashBalance(BigDecimal.ZERO)
                    .build();
        }

        KisBalanceResponse body = response.getBody();

        // Map output1 (holdings)
        List<HoldingResponse> holdings = new ArrayList<>();
        if (body.getOutput1() != null && !body.getOutput1().isEmpty()) {
            holdings = body.getOutput1().stream()
                    .map(this::mapToHoldingResponse)
                    .collect(Collectors.toList());
        }

        // Map output2 (summary)
        KisBalanceResponse.Output2 summary = body.getOutput2() != null && !body.getOutput2().isEmpty()
                ? body.getOutput2().get(0)
                : new KisBalanceResponse.Output2();

        // Calculate totals from holdings if Output2 fields are null
        BigDecimal totalEvaluationAmount = parseBigDecimalSafely(summary.getTotEvluAmt());
        BigDecimal totalPurchaseAmount = parseBigDecimalSafely(summary.getPchsAmtSmtl());
        BigDecimal totalProfitLoss = parseBigDecimalSafely(summary.getEvluPflsSmtl());
        BigDecimal totalProfitLossRate = parseBigDecimalSafely(summary.getEvluPflsRt());
        BigDecimal cashBalance = parseBigDecimalSafely(summary.getDncaTotAmt());

        // If Output2 fields are zero/null, calculate from holdings
        if ((totalPurchaseAmount == null || totalPurchaseAmount.compareTo(BigDecimal.ZERO) == 0) && !holdings.isEmpty()) {
            log.info("Output2 summary fields are null/zero, calculating from holdings");

            // Calculate sums from holdings
            totalPurchaseAmount = holdings.stream()
                    .map(HoldingResponse::getPurchaseAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            totalProfitLoss = holdings.stream()
                    .map(HoldingResponse::getProfitLoss)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            totalEvaluationAmount = holdings.stream()
                    .map(HoldingResponse::getEvaluationAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate profit/loss rate: (totalProfitLoss / totalPurchaseAmount) * 100
            if (totalPurchaseAmount.compareTo(BigDecimal.ZERO) > 0) {
                totalProfitLossRate = totalProfitLoss
                        .divide(totalPurchaseAmount, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            } else {
                totalProfitLossRate = BigDecimal.ZERO;
            }
        }

        log.info("Final Balance Summary - TotalEvaluation: {}, TotalPurchase: {}, TotalProfitLoss: {}, ProfitRate: {}%, Cash: {}",
                totalEvaluationAmount, totalPurchaseAmount, totalProfitLoss, totalProfitLossRate, cashBalance);

        return BalanceSummaryResponse.builder()
                .holdings(holdings)
                .totalEvaluationAmount(totalEvaluationAmount)
                .totalPurchaseAmount(totalPurchaseAmount)
                .totalProfitLoss(totalProfitLoss)
                .totalProfitLossRate(totalProfitLossRate)
                .cashBalance(cashBalance)
                .build();
    }

    /**
     * Map KIS Output1 to HoldingResponse
     */
    private HoldingResponse mapToHoldingResponse(KisBalanceResponse.Output1 item) {
        return HoldingResponse.builder()
                .stockCode(item.getPdno())
                .stockName(item.getPrdtName())
                .holdingQuantity(parseIntSafely(item.getHldgQty()))
                .availableQuantity(parseIntSafely(item.getOrdPsblQty()))
                .averagePrice(parseBigDecimalSafely(item.getPchsAvgPric()))
                .currentPrice(parseBigDecimalSafely(item.getPrpr()))
                .evaluationAmount(parseBigDecimalSafely(item.getEvluAmt()))
                .profitLoss(parseBigDecimalSafely(item.getEvluPflsAmt()))
                .profitLossRate(parseBigDecimalSafely(item.getEvluPflsRt()))
                .purchaseAmount(parseBigDecimalSafely(item.getPchsAmt()))
                .build();
    }
}
