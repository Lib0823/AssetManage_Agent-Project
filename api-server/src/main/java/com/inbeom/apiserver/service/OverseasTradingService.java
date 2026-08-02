package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisApiClient;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.dto.overseas.OverseasBalanceResponse;
import com.inbeom.apiserver.dto.overseas.OverseasOrderRequest;
import com.inbeom.apiserver.dto.overseas.OverseasOrderableResponse;
import com.inbeom.apiserver.dto.overseas.OverseasPendingOrderResponse;
import com.inbeom.apiserver.dto.overseas.OverseasTradeHistoryResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.exception.KisAccountNotFoundException;
import com.inbeom.apiserver.exception.KisApiException;
import com.inbeom.apiserver.exception.UserNotFoundException;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.service.KisAuthService.KisCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 해외주식(미국) 잔고/매수/매도 서비스.
 *
 * <p>잔고/매매는 mock 도메인 + 사용자 DB 키(path A, {@link KisAuthService}) 흐름이다
 * ({@link TradingService} 패턴 미러). 해외 TR 은 모의 V변형 상수를 직접 전송하며
 * {@link KisApiClient#convertTrId}에 의존하지 않는다(VTTS/VTTT 는 변환 대상이 아님).
 *
 * <p><b>조회</b>(잔고/거래내역/미체결/매수가능)는 모의 미지원·rt_cd != 0·예외 시 예외를 전파하지 않고
 * 빈 결과 + {@code notice} 로 graceful degrade 한다(화면이 깨지지 않아야 하므로).
 *
 * <p><b>주문</b>(매수/매도)은 국내({@link TradingService#executeBuy})와 동일하게 실패를 삼키지 않고
 * {@link com.inbeom.apiserver.exception.BusinessException} 을 던진다 → {@code GlobalExceptionHandler} 가
 * {@code ApiResponse.success=false} + ErrorCode 별 HTTP 상태로 변환한다. 주문은 부작용이 있는 명령이므로
 * 실패가 200 으로 내려가면 안 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverseasTradingService {

    private final KisAuthService kisAuthService;
    private final KisApiClient kisApiClient;
    private final UserRepository userRepository;

    // 해외 TR (모의 V변형 — convertTrId 미적용 대상이므로 상수로 직접 확정)
    // ─── MUST-VERIFY (라이브 모의계좌+장중에서만 확정 가능) ───
    // 모의 V변형 TR명: kis_os.py 기준 실전 TTTS****R → 모의 VTTS****R 동일 suffix.
    // 모의 inquire-nccs/inquire-ccnl 지원 여부 및 응답 필드명은 실응답 확인 필요.
    private static final String TR_BALANCE = "VTTS3012R";   // 해외주식 잔고 (모의)
    private static final String TR_BUY = "VTTT1002U";       // 미국 매수 주문 (모의)
    private static final String TR_SELL = "VTTT1006U";      // 미국 매도 주문 (모의)
    private static final String TR_HISTORY = "VTTS3035R";   // 해외 체결내역(거래내역) (모의) — MUST-VERIFY
    private static final String TR_PENDING = "VTTS3018R";   // 해외 미체결 (모의) — MUST-VERIFY
    private static final String TR_ORDERABLE = "VTTS3007R"; // 해외 매수가능 (모의) — MUST-VERIFY

    /** 계정 도메인이 실전(openapi)인지 판정. (모의 openapivts 는 포함 안 됨) */
    private boolean isReal(KisCredentials credentials) {
        return credentials.baseUrl() != null
                && credentials.baseUrl().contains("openapi.koreainvestment.com");
    }

    /**
     * 해외 TR 모드 변환: 실전이면 접두 V→T (VTTS→TTTS, VTTT→TTTT), 모의면 그대로.
     * convertTrId 는 국내(VTTC/TTTC)만 처리하므로 해외는 여기서 확정한다.
     */
    private String overseasTr(String mockTr, KisCredentials credentials) {
        return isReal(credentials) ? "T" + mockTr.substring(1) : mockTr;
    }

    private static final String BALANCE_ENDPOINT = "/uapi/overseas-stock/v1/trading/inquire-balance";
    private static final String ORDER_ENDPOINT = "/uapi/overseas-stock/v1/trading/order";
    private static final String HISTORY_ENDPOINT = "/uapi/overseas-stock/v1/trading/inquire-ccnl";
    private static final String PENDING_ENDPOINT = "/uapi/overseas-stock/v1/trading/inquire-nccs";
    private static final String ORDERABLE_ENDPOINT = "/uapi/overseas-stock/v1/trading/inquire-psamount";

    // 모의 inquire-ccnl/inquire-nccs 는 전체("00")만 지원(모의 제약). 거래내역 기본 조회기간 90일.
    private static final String DVSN_ALL = "00";
    private static final int HISTORY_LOOKBACK_DAYS = 90;
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String NOTICE_BALANCE_FAILED = "해외 잔고를 불러오지 못했습니다";
    private static final String NOTICE_ORDER_FAILED = "해외 주문에 실패했습니다 (모의투자 해외매매 미지원일 수 있습니다)";
    private static final String NOTICE_HISTORY_FAILED = "해외 거래내역을 불러오지 못했습니다 (모의투자 미지원일 수 있습니다)";
    private static final String NOTICE_PENDING_FAILED = "해외 미체결 내역을 불러오지 못했습니다 (모의투자 미지원일 수 있습니다)";
    private static final String NOTICE_ORDERABLE_FAILED = "해외 매수가능 금액을 불러오지 못했습니다 (모의투자 미지원일 수 있습니다)";

    /**
     * 해외주식 잔고 조회 (VTTS3012R, 모의). 미국 3거래소(NASD/NYSE/AMEX)를 순회하며 보유종목을 합산한다.
     * 미지원/실패/예외 시 빈 목록 + notice 로 degrade(예외 전파 금지).
     */
    @SuppressWarnings("unchecked")
    public OverseasBalanceResponse getBalance(Long userId) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getKisAccount() == null) {
                log.warn("Overseas balance: no KIS account for userId={}", userId);
                return emptyBalance(NOTICE_BALANCE_FAILED);
            }

            Long kisAccountId = user.getKisAccount().getId();
            String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
            KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

            List<OverseasBalanceResponse.OverseasHolding> holdings = new ArrayList<>();
            BigDecimal totalEval = BigDecimal.ZERO;
            BigDecimal totalPurchase = BigDecimal.ZERO;
            BigDecimal totalProfitLoss = BigDecimal.ZERO;
            boolean anyOk = false;

            for (OverseasExchange ex : OverseasExchange.values()) {
                Map<String, String> queryParams = new LinkedHashMap<>();
                queryParams.put("CANO", credentials.accountNumber());
                queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
                queryParams.put("OVRS_EXCG_CD", ex.balanceCode());
                queryParams.put("TR_CRCY_CD", ex.currency());
                queryParams.put("CTX_AREA_FK200", "");
                queryParams.put("CTX_AREA_NK200", "");

                Map<String, Object> body;
                try {
                    ResponseEntity<Map> response = kisApiClient.get(
                            credentials.baseUrl(),
                            BALANCE_ENDPOINT,
                            overseasTr(TR_BALANCE, credentials),
                            kisToken,
                            credentials.appKey(),
                            credentials.appSecret(),
                            queryParams,
                            Map.class
                    );
                    body = response.getBody();
                } catch (Exception e) {
                    log.warn("Overseas balance call failed for userId={} excd={}: {}",
                            userId, ex.balanceCode(), e.getMessage());
                    continue;
                }

                if (!isRtOk(body)) {
                    log.warn("Overseas balance rt_cd!=0 for userId={} excd={}: {}",
                            userId, ex.balanceCode(), body != null ? body.get("msg1") : "null body");
                    continue;
                }
                anyOk = true;

                Object output1 = body.get("output1");
                if (output1 instanceof List) {
                    for (Object row : (List<Object>) output1) {
                        if (!(row instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> item = (Map<String, Object>) row;
                        OverseasBalanceResponse.OverseasHolding holding = mapHolding(item);
                        // 보유수량 0 인 종목은 제외(잔여 노출 방지)
                        if (holding.getQuantity() != null && holding.getQuantity() > 0) {
                            holdings.add(holding);
                            totalPurchase = totalPurchase.add(
                                    parseBigDecimalSafely(asString(item.get("frcr_pchs_amt1"))));
                            totalEval = totalEval.add(
                                    parseBigDecimalSafely(asString(item.get("ovrs_stck_evlu_amt"))));
                            totalProfitLoss = totalProfitLoss.add(
                                    holding.getEvalProfitLoss() != null
                                            ? holding.getEvalProfitLoss() : BigDecimal.ZERO);
                        }
                    }
                }

                // output2(요약)에 통화별 평가/손익이 있으면 우선 누적 시도 (단일 거래소 응답 기준)
                Object output2 = body.get("output2");
                Map<String, Object> summary = firstMap(output2);
                if (summary != null) {
                    BigDecimal sumEval = parseBigDecimalSafely(asString(summary.get("tot_evlu_pfls_amt")));
                    // output2 의 합계 필드는 응답에 따라 비어있을 수 있어 보유종목 합을 신뢰값으로 둔다.
                    log.debug("Overseas balance summary excd={} tot_evlu_pfls_amt={}", ex.balanceCode(), sumEval);
                }
            }

            if (!anyOk) {
                return emptyBalance(NOTICE_BALANCE_FAILED);
            }

            return OverseasBalanceResponse.builder()
                    .holdings(holdings)
                    .totalEval(totalEval)
                    .totalPurchase(totalPurchase)
                    .totalProfitLoss(totalProfitLoss)
                    .currency("USD")
                    .notice(null)
                    .build();
        } catch (Exception e) {
            log.warn("Overseas balance failed for userId={}: {}", userId, e.getMessage());
            return emptyBalance(NOTICE_BALANCE_FAILED);
        }
    }

    /**
     * 해외주식 체결내역(거래내역) 조회 (VTTS3035R, 모의, inquire-ccnl). 사용자 mock 키.
     * 조회기간은 최근 90일(ORD_STRT_DT~ORD_END_DT), 매도매수/체결구분은 모의 제약상 전체("00").
     * 미지원/실패/예외 시 빈 목록 + notice 로 degrade(예외 전파 금지).
     */
    @SuppressWarnings("unchecked")
    public OverseasTradeHistoryResponse getHistory(Long userId, String exchange) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getKisAccount() == null) {
                log.warn("Overseas history: no KIS account for userId={}", userId);
                return emptyHistory(NOTICE_HISTORY_FAILED);
            }

            OverseasExchange ex = OverseasExchange.fromCode(exchange);
            Long kisAccountId = user.getKisAccount().getId();
            String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
            KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

            LocalDate today = LocalDate.now();
            Map<String, String> queryParams = new LinkedHashMap<>();
            queryParams.put("CANO", credentials.accountNumber());
            queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
            queryParams.put("PDNO", "");
            queryParams.put("ORD_STRT_DT", today.minusDays(HISTORY_LOOKBACK_DAYS).format(YYYYMMDD));
            queryParams.put("ORD_END_DT", today.format(YYYYMMDD));
            queryParams.put("SLL_BUY_DVSN", DVSN_ALL);    // 모의: 전체만
            queryParams.put("CCLD_NCCS_DVSN", DVSN_ALL);  // 모의: 전체만
            queryParams.put("OVRS_EXCG_CD", ex.balanceCode());
            queryParams.put("SORT_SQN", "");
            queryParams.put("ORD_DT", "");
            queryParams.put("ORD_GNO_BRNO", "");
            queryParams.put("ODNO", "");
            queryParams.put("CTX_AREA_FK200", "");
            queryParams.put("CTX_AREA_NK200", "");

            Map<String, Object> body;
            try {
                ResponseEntity<Map> response = kisApiClient.get(
                        credentials.baseUrl(),
                        HISTORY_ENDPOINT,
                        overseasTr(TR_HISTORY, credentials),
                        kisToken,
                        credentials.appKey(),
                        credentials.appSecret(),
                        queryParams,
                        Map.class
                );
                body = response.getBody();
            } catch (Exception e) {
                log.warn("Overseas history call failed for userId={} excd={}: {}",
                        userId, ex.balanceCode(), e.getMessage());
                return emptyHistory(NOTICE_HISTORY_FAILED);
            }

            if (!isRtOk(body)) {
                log.warn("Overseas history rt_cd!=0 for userId={} excd={}: {}",
                        userId, ex.balanceCode(), body != null ? body.get("msg1") : "null body");
                return emptyHistory(NOTICE_HISTORY_FAILED);
            }

            List<OverseasTradeHistoryResponse.OverseasTradeHistoryItem> items = new ArrayList<>();
            Object output = body.get("output");
            // output 키는 응답에 따라 output1/output 둘 다 가능 → 둘 다 시도(MUST-VERIFY).
            if (!(output instanceof List)) {
                output = body.get("output1");
            }
            if (output instanceof List) {
                for (Object row : (List<Object>) output) {
                    if (!(row instanceof Map)) {
                        continue;
                    }
                    items.add(mapHistoryItem((Map<String, Object>) row));
                }
            }

            return OverseasTradeHistoryResponse.builder()
                    .list(items)
                    .currency("USD")
                    .notice(null)
                    .build();
        } catch (Exception e) {
            log.warn("Overseas history failed for userId={}: {}", userId, e.getMessage());
            return emptyHistory(NOTICE_HISTORY_FAILED);
        }
    }

    /**
     * 해외주식 미체결 조회 (VTTS3018R, 모의, inquire-nccs). 사용자 mock 키.
     * 미지원/실패/예외 시 빈 목록 + notice 로 degrade(예외 전파 금지).
     */
    @SuppressWarnings("unchecked")
    public OverseasPendingOrderResponse getPendingOrders(Long userId, String exchange) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getKisAccount() == null) {
                log.warn("Overseas pending-orders: no KIS account for userId={}", userId);
                return emptyPending(NOTICE_PENDING_FAILED);
            }

            OverseasExchange ex = OverseasExchange.fromCode(exchange);
            Long kisAccountId = user.getKisAccount().getId();
            String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
            KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

            Map<String, String> queryParams = new LinkedHashMap<>();
            queryParams.put("CANO", credentials.accountNumber());
            queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
            queryParams.put("OVRS_EXCG_CD", ex.balanceCode());
            queryParams.put("SORT_SQN", "");
            queryParams.put("CTX_AREA_FK200", "");
            queryParams.put("CTX_AREA_NK200", "");

            Map<String, Object> body;
            try {
                ResponseEntity<Map> response = kisApiClient.get(
                        credentials.baseUrl(),
                        PENDING_ENDPOINT,
                        overseasTr(TR_PENDING, credentials),
                        kisToken,
                        credentials.appKey(),
                        credentials.appSecret(),
                        queryParams,
                        Map.class
                );
                body = response.getBody();
            } catch (Exception e) {
                log.warn("Overseas pending-orders call failed for userId={} excd={}: {}",
                        userId, ex.balanceCode(), e.getMessage());
                return emptyPending(NOTICE_PENDING_FAILED);
            }

            if (!isRtOk(body)) {
                log.warn("Overseas pending-orders rt_cd!=0 for userId={} excd={}: {}",
                        userId, ex.balanceCode(), body != null ? body.get("msg1") : "null body");
                return emptyPending(NOTICE_PENDING_FAILED);
            }

            List<OverseasPendingOrderResponse.OverseasPendingOrderItem> items = new ArrayList<>();
            Object output = body.get("output");
            if (!(output instanceof List)) {
                output = body.get("output1");
            }
            if (output instanceof List) {
                for (Object row : (List<Object>) output) {
                    if (!(row instanceof Map)) {
                        continue;
                    }
                    items.add(mapPendingItem((Map<String, Object>) row));
                }
            }

            return OverseasPendingOrderResponse.builder()
                    .list(items)
                    .currency("USD")
                    .notice(null)
                    .build();
        } catch (Exception e) {
            log.warn("Overseas pending-orders failed for userId={}: {}", userId, e.getMessage());
            return emptyPending(NOTICE_PENDING_FAILED);
        }
    }

    /**
     * 해외주식 매수가능 조회 (VTTS3007R, 모의, inquire-psamount). 사용자 mock 키.
     * price 는 OVRS_ORD_UNPR(지정단가) 로 전달한다. 미지원/실패/예외 시 0 + notice 로 degrade.
     */
    @SuppressWarnings("unchecked")
    public OverseasOrderableResponse getOrderable(Long userId, String symbol, String exchange, BigDecimal price) {
        OverseasExchange ex = OverseasExchange.fromCode(exchange);
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getKisAccount() == null) {
                log.warn("Overseas orderable: no KIS account for userId={}", userId);
                return emptyOrderable(symbol, ex, NOTICE_ORDERABLE_FAILED);
            }

            Long kisAccountId = user.getKisAccount().getId();
            String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
            KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

            String unitPrice = (price != null && price.compareTo(BigDecimal.ZERO) > 0)
                    ? price.toPlainString() : "0";

            Map<String, String> queryParams = new LinkedHashMap<>();
            queryParams.put("CANO", credentials.accountNumber());
            queryParams.put("ACNT_PRDT_CD", credentials.accountProductCode());
            queryParams.put("OVRS_EXCG_CD", ex.balanceCode());
            queryParams.put("OVRS_ORD_UNPR", unitPrice);
            queryParams.put("ITEM_CD", symbol);

            Map<String, Object> body;
            try {
                ResponseEntity<Map> response = kisApiClient.get(
                        credentials.baseUrl(),
                        ORDERABLE_ENDPOINT,
                        overseasTr(TR_ORDERABLE, credentials),
                        kisToken,
                        credentials.appKey(),
                        credentials.appSecret(),
                        queryParams,
                        Map.class
                );
                body = response.getBody();
            } catch (Exception e) {
                log.warn("Overseas orderable call failed for userId={} symbol={}: {}",
                        userId, symbol, e.getMessage());
                return emptyOrderable(symbol, ex, NOTICE_ORDERABLE_FAILED);
            }

            if (!isRtOk(body)) {
                log.warn("Overseas orderable rt_cd!=0 for userId={} symbol={}: {}",
                        userId, symbol, body != null ? body.get("msg1") : "null body");
                return emptyOrderable(symbol, ex, NOTICE_ORDERABLE_FAILED);
            }

            Map<String, Object> out = firstMap(body.get("output"));
            if (out == null) {
                log.warn("Overseas orderable missing output for userId={} symbol={}", userId, symbol);
                return emptyOrderable(symbol, ex, NOTICE_ORDERABLE_FAILED);
            }

            // 필드명 MUST-VERIFY: 최대주문가능수량/주문가능외화금액. 응답 변형 대비 후보 키 순회.
            int maxQty = parseIntSafely(firstNonNull(out,
                    "max_ord_psbl_qty", "ovrs_max_ord_psbl_qty", "ord_psbl_qty"));
            BigDecimal cash = parseBigDecimalSafely(firstNonNull(out,
                    "ord_psbl_frcr_amt", "frcr_ord_psbl_amt1", "ovrs_ord_psbl_amt"));

            return OverseasOrderableResponse.builder()
                    .symbol(symbol)
                    .exchange(ex.balanceCode())
                    .maxBuyQty(maxQty)
                    .orderableCash(cash)
                    .currency("USD")
                    .notice(null)
                    .build();
        } catch (Exception e) {
            log.warn("Overseas orderable failed for userId={} symbol={}: {}", userId, symbol, e.getMessage());
            return emptyOrderable(symbol, ex, NOTICE_ORDERABLE_FAILED);
        }
    }

    /**
     * 해외주식 매수 (VTTT1002U, 모의). 지정가(ORD_DVSN="00")만.
     * 실패 시 {@link com.inbeom.apiserver.exception.BusinessException} 전파(국내와 동일).
     */
    public Map<String, Object> buy(Long userId, OverseasOrderRequest request) {
        return order(userId, request, true);
    }

    /**
     * 해외주식 매도 (VTTT1006U, 모의). 지정가(ORD_DVSN="00")만.
     * 실패 시 {@link com.inbeom.apiserver.exception.BusinessException} 전파(국내와 동일).
     */
    public Map<String, Object> sell(Long userId, OverseasOrderRequest request) {
        return order(userId, request, false);
    }

    /**
     * 해외 주문 실행. 조회 메서드와 달리 실패를 삼키지 않는다 —
     * 국내 {@link TradingService#executeBuy}/{@link TradingService#executeSell} 와 같은 계약이다.
     *
     * @throws com.inbeom.apiserver.exception.BusinessException 수량 오류(INVALID_TRADE_QUANTITY),
     *         단가 누락(INVALID_TRADE_PRICE),
     *         사용자/KIS 계정 미등록(USER_NOT_FOUND / KIS_ACCOUNT_NOT_FOUND),
     *         KIS 호출 실패 또는 rt_cd != 0 (KIS_API_* )
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> order(Long userId, OverseasOrderRequest request, boolean isBuy) {
        String side = isBuy ? "buy" : "sell";

        Integer quantity = request.getQuantity();
        if (quantity == null || quantity < 1) {
            throw new BusinessException(ErrorCode.INVALID_TRADE_QUANTITY,
                    "주문 수량은 1주 이상이어야 합니다 (요청 수량: " + quantity + ")");
        }

        BigDecimal price = request.getPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            // 모의 해외 주문은 지정가 전용 → 단가 필수
            throw new BusinessException(ErrorCode.INVALID_TRADE_PRICE,
                    "해외 주문은 지정가만 지원합니다 (단가 필수)");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (user.getKisAccount() == null) {
            throw new KisAccountNotFoundException("KIS account not registered for userId: " + userId);
        }

        Long kisAccountId = user.getKisAccount().getId();
        String kisToken = kisAuthService.getKisAccessToken(kisAccountId);
        KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);

        OverseasExchange ex = OverseasExchange.fromCode(request.getExchange());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("CANO", credentials.accountNumber());
        requestBody.put("ACNT_PRDT_CD", credentials.accountProductCode());
        requestBody.put("OVRS_EXCG_CD", ex.balanceCode());
        requestBody.put("PDNO", request.getSymbol());
        requestBody.put("ORD_QTY", String.valueOf(request.getQuantity()));
        requestBody.put("OVRS_ORD_UNPR", price.toPlainString());
        requestBody.put("CTAC_TLNO", "");
        requestBody.put("MGCO_APTM_ODNO", "");
        requestBody.put("SLL_TYPE", isBuy ? "" : "00");
        requestBody.put("ORD_SVR_DVSN_CD", "0");
        requestBody.put("ORD_DVSN", "00");  // 지정가 전용

        String trId = overseasTr(isBuy ? TR_BUY : TR_SELL, credentials);

        // HTTP/네트워크 실패는 KisApiClient 가 KisApiException 으로 던진다 → 그대로 전파(국내와 동일)
        ResponseEntity<Map> response = kisApiClient.post(
                credentials.baseUrl(),
                ORDER_ENDPOINT,
                trId,
                kisToken,
                credentials.appKey(),
                credentials.appSecret(),
                requestBody,
                Map.class
        );
        Map<String, Object> body = response.getBody();

        verifyOverseasOrderSuccess(body, side, userId, request.getSymbol());

        String orderNumber = null;
        Object output = body.get("output");
        if (output instanceof Map) {
            orderNumber = asString(((Map<String, Object>) output).get("ODNO"));
        }

        log.info("Overseas {} order executed for userId={}, symbol={}, qty={}, orderNumber={}",
                side, userId, request.getSymbol(), request.getQuantity(), orderNumber);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("orderNumber", orderNumber);
        result.put("symbol", request.getSymbol());
        result.put("exchange", ex.balanceCode());
        result.put("quantity", request.getQuantity());
        result.put("price", price);
        result.put("orderType", isBuy ? "BUY" : "SELL");
        return result;
    }

    /**
     * 해외 주문 응답 검증. 국내 {@code TradingService#verifyKisOrderSuccess} 와 동일한 계약으로
     * rt_cd != 0 또는 빈 응답이면 예외를 던진다(모의 해외매매 미지원도 이 경로로 4002 로 내려간다).
     */
    private void verifyOverseasOrderSuccess(Map<String, Object> body, String side, Long userId, String symbol) {
        if (body == null) {
            log.warn("Overseas {} order empty response for userId={} symbol={}", side, userId, symbol);
            throw KisApiException.serverError(NOTICE_ORDER_FAILED + " (KIS 주문 응답이 비어 있습니다)");
        }
        Object rtCd = body.get("rt_cd");
        if (!"0".equals(String.valueOf(rtCd))) {
            String msg = String.valueOf(body.getOrDefault("msg1", "KIS 해외 주문이 거부되었습니다")).trim();
            log.warn("Overseas {} order rt_cd!=0 for userId={} symbol={}: {}", side, userId, symbol, msg);
            throw KisApiException.serverError(NOTICE_ORDER_FAILED + " (" + msg + ", rt_cd=" + rtCd + ")");
        }
    }

    private OverseasBalanceResponse emptyBalance(String notice) {
        return OverseasBalanceResponse.builder()
                .holdings(new ArrayList<>())
                .totalEval(BigDecimal.ZERO)
                .totalPurchase(BigDecimal.ZERO)
                .totalProfitLoss(BigDecimal.ZERO)
                .currency("USD")
                .notice(notice)
                .build();
    }

    private OverseasTradeHistoryResponse emptyHistory(String notice) {
        return OverseasTradeHistoryResponse.builder()
                .list(new ArrayList<>())
                .currency("USD")
                .notice(notice)
                .build();
    }

    private OverseasPendingOrderResponse emptyPending(String notice) {
        return OverseasPendingOrderResponse.builder()
                .list(new ArrayList<>())
                .currency("USD")
                .notice(notice)
                .build();
    }

    private OverseasOrderableResponse emptyOrderable(String symbol, OverseasExchange ex, String notice) {
        return OverseasOrderableResponse.builder()
                .symbol(symbol)
                .exchange(ex.balanceCode())
                .maxBuyQty(0)
                .orderableCash(BigDecimal.ZERO)
                .currency("USD")
                .notice(notice)
                .build();
    }

    // 체결내역 필드명 MUST-VERIFY (모의 inquire-ccnl 실응답 기준 확정 필요).
    private OverseasTradeHistoryResponse.OverseasTradeHistoryItem mapHistoryItem(Map<String, Object> item) {
        String orderDate = firstNonNull(item, "ord_dt", "dmst_ord_dt");
        String orderTime = firstNonNull(item, "ord_tmd", "ord_tm");
        return OverseasTradeHistoryResponse.OverseasTradeHistoryItem.builder()
                .symbol(firstNonNull(item, "pdno", "ovrs_pdno"))
                .name(firstNonNull(item, "prdt_name", "ovrs_item_name"))
                .side(mapSide(firstNonNull(item, "sll_buy_dvsn_cd", "sll_buy_dvsn_cd_name")))
                .qty(parseIntSafely(firstNonNull(item, "ft_ccld_qty", "ccld_qty")))
                .price(parseBigDecimalSafely(firstNonNull(item, "ft_ccld_unpr3", "ccld_unpr", "ft_ccld_unpr")))
                .executedAt(joinDateTime(orderDate, orderTime))
                .orderNo(firstNonNull(item, "odno"))
                .status(firstNonNull(item, "prcs_stat_name", "rvse_cncl_dvsn_name", "sll_buy_dvsn_cd_name"))
                .build();
    }

    // 미체결 필드명 MUST-VERIFY (모의 inquire-nccs 실응답 기준 확정 필요).
    private OverseasPendingOrderResponse.OverseasPendingOrderItem mapPendingItem(Map<String, Object> item) {
        String orderDate = firstNonNull(item, "ord_dt", "dmst_ord_dt");
        String orderTime = firstNonNull(item, "ord_tmd", "ord_tm");
        return OverseasPendingOrderResponse.OverseasPendingOrderItem.builder()
                .orderNo(firstNonNull(item, "odno"))
                .symbol(firstNonNull(item, "pdno", "ovrs_pdno"))
                .name(firstNonNull(item, "prdt_name", "ovrs_item_name"))
                .side(mapSide(firstNonNull(item, "sll_buy_dvsn_cd", "sll_buy_dvsn_cd_name")))
                .orderQty(parseIntSafely(firstNonNull(item, "ft_ord_qty", "ord_qty")))
                .remainQty(parseIntSafely(firstNonNull(item, "nccs_qty", "ord_psbl_qty")))
                .orderPrice(parseBigDecimalSafely(firstNonNull(item, "ft_ord_unpr3", "ord_unpr", "ft_ord_unpr")))
                .orderedAt(joinDateTime(orderDate, orderTime))
                .build();
    }

    /**
     * KIS 매도매수구분코드(01=매도, 02=매수) 또는 명칭을 BUY/SELL 로 정규화. 미상이면 원문 반환.
     */
    private String mapSide(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if ("02".equals(s) || s.contains("매수") || s.equalsIgnoreCase("BUY")) {
            return "BUY";
        }
        if ("01".equals(s) || s.contains("매도") || s.equalsIgnoreCase("SELL")) {
            return "SELL";
        }
        return s;
    }

    /**
     * yyyyMMdd + HHmmss 를 yyyyMMddHHmmss 로 결합. 둘 중 하나라도 비면 가진 값만 반환.
     */
    private String joinDateTime(String date, String time) {
        if (date == null) {
            return time;
        }
        if (time == null) {
            return date;
        }
        return date + time;
    }

    /**
     * 후보 키들을 순서대로 조회해 첫 비어있지 않은 String 값을 반환(응답 필드명 변형 대비, MUST-VERIFY).
     */
    private String firstNonNull(Map<String, Object> item, String... keys) {
        for (String key : keys) {
            String v = asString(item.get(key));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private OverseasBalanceResponse.OverseasHolding mapHolding(Map<String, Object> item) {
        return OverseasBalanceResponse.OverseasHolding.builder()
                .symbol(asString(item.get("ovrs_pdno")))
                .name(asString(item.get("ovrs_item_name")))
                .quantity(parseIntSafely(asString(item.get("ovrs_cblc_qty"))))
                .orderableQty(parseIntSafely(asString(item.get("ord_psbl_qty"))))
                .avgPrice(parseBigDecimalSafely(asString(item.get("pchs_avg_pric"))))
                .currentPrice(parseBigDecimalSafely(asString(item.get("now_pric2"))))
                .evalProfitLoss(parseBigDecimalSafely(asString(item.get("frcr_evlu_pfls_amt"))))
                .profitLossRate(parseBigDecimalSafely(asString(item.get("evlu_pfls_rt"))))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMap(Object output) {
        if (output instanceof Map) {
            return (Map<String, Object>) output;
        }
        if (output instanceof List) {
            List<Object> list = (List<Object>) output;
            if (!list.isEmpty() && list.get(0) instanceof Map) {
                return (Map<String, Object>) list.get(0);
            }
        }
        return null;
    }

    private boolean isRtOk(Map<String, Object> body) {
        return body != null && "0".equals(String.valueOf(body.get("rt_cd")));
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private int parseIntSafely(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            String cleaned = value.trim().replace(",", "");
            if (cleaned.contains(".")) {
                return (int) Double.parseDouble(cleaned);
            }
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse int: {}", value);
            return 0;
        }
    }

    private BigDecimal parseBigDecimalSafely(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            log.warn("Failed to parse BigDecimal: {}", value);
            return BigDecimal.ZERO;
        }
    }
}
