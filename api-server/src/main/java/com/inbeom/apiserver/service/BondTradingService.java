package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.KisBondClient;
import com.inbeom.apiserver.client.KisBondClient.BondCallContext;
import com.inbeom.apiserver.config.KisBondProperties;
import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.dto.bond.BondBalanceResponse;
import com.inbeom.apiserver.dto.bond.BondHoldingResponse;
import com.inbeom.apiserver.dto.bond.BondSellRequest;
import com.inbeom.apiserver.dto.bond.BondTradeHistoryResponse;
import com.inbeom.apiserver.dto.bond.BondTradeHistoryResponse.BondTradeHistoryItem;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.inbeom.apiserver.service.BondResponses.asMapList;
import static com.inbeom.apiserver.service.BondResponses.decimal;
import static com.inbeom.apiserver.service.BondResponses.firstMap;
import static com.inbeom.apiserver.service.BondResponses.firstNonNull;
import static com.inbeom.apiserver.service.BondResponses.isRtOk;
import static com.inbeom.apiserver.service.BondResponses.message;
import static com.inbeom.apiserver.service.BondResponses.string;
import static com.inbeom.apiserver.service.BondResponses.toDecimal;

/**
 * 장내채권 잔고 조회 · 매도 주문 · 거래내역 서비스.
 *
 * <p>잔고/매매는 <b>사용자별 DB 자격증명</b>({@link KisAuthService})을 쓴다 — 시세와 달리 계좌에
 * 종속된 정보이기 때문이다. 실전투자 전용이며 모의 TR·도메인 분기는 없다.
 *
 * <p><b>조회</b>(잔고/거래내역)는 KIS 실패 시 예외를 전파하지 않고 빈 결과 + {@code notice} 로
 * degrade 한다. <b>매도 주문</b>은 반대로 실패를 삼키지 않고 예외를 던진다
 * ({@link TradingService#executeSell} 와 같은 계약) — 주문 실패가 200 으로 내려가면 사용자는
 * 팔리지 않은 채권을 팔았다고 믿는다.
 *
 * <p><b>DB 에 아무것도 쓰지 않는다.</b> 거래내역은 KIS 에서 직접 조회하므로 신규 테이블이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BondTradingService {

    private final KisAuthService kisAuthService;
    private final KisBondClient kisBondClient;
    private final UserRepository userRepository;
    private final KisBondProperties bondProperties;

    /**
     * 잔고 연속조회 페이지 상한.
     *
     * <p>KIS 가 어떤 이유로든 {@code tr_cont=M} 을 계속 돌려주면 상한이 없는 루프는 영원히 돈다 —
     * 요청 스레드 하나가 묶이고 rate limit 토큰을 전부 태운다. 로트가 20페이지를 넘는 계좌는
     * 이 앱의 사용자 규모에서 현실적이지 않으므로 상한에서 멈추고 경고를 남긴다.
     */
    static final int MAX_BALANCE_PAGES = 20;

    private static final String CURRENCY_KRW = "KRW";
    private static final int HISTORY_LOOKBACK_DAYS = 90;
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final String NOTICE_BALANCE_FAILED = "보유 채권을 불러오지 못했습니다";
    private static final String NOTICE_BALANCE_PARTIAL =
            "보유 채권의 일부만 조회됐습니다 (연속조회 상한 도달)";
    private static final String NOTICE_HISTORY_FAILED = "채권 거래내역을 불러오지 못했습니다";
    private static final String NOTICE_ORDER_FAILED = "채권 매도 주문에 실패했습니다";

    // ── 잔고 ────────────────────────────────────────────────────────────

    /**
     * 보유 채권 잔고 조회 (CTSC8407R).
     *
     * <p>결과는 종목이 아니라 <b>매수 로트</b> 목록이다. 같은 채권을 다른 날 샀으면 행이 여럿이며,
     * 각 행의 {@code buyDate}/{@code buySeq} 가 매도에 필요하다.
     *
     * <p>KIS 호출 실패는 빈 목록 + notice 로 degrade 하지만, <b>KIS 계좌 미등록은 예외</b>다 —
     * 그것은 일시적 장애가 아니라 사용자가 조치해야 하는 상태이고, 빈 목록으로 감추면
     * "보유 채권이 없다"와 구분되지 않는다.
     *
     * @throws KisAccountNotFoundException KIS 계좌가 연동되지 않은 사용자
     */
    public BondBalanceResponse getBalance(Long userId) {
        Long kisAccountId = requireKisAccountId(loadUser(userId));

        try {
            KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);
            BondCallContext ctx = tradingContext(kisAccountId, credentials);

            List<BondHoldingResponse> holdings = new ArrayList<>();
            BigDecimal totalBuyAmount = BigDecimal.ZERO;
            String ctxFk = "";
            String ctxNk = "";
            boolean truncated = true;

            for (int page = 0; page < MAX_BALANCE_PAGES; page++) {
                @SuppressWarnings("rawtypes")
                ResponseEntity<Map> response =
                        kisBondClient.inquireBalance(ctx, credentials.accountNumber(),
                                credentials.accountProductCode(), ctxFk, ctxNk);

                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();
                if (!isRtOk(body)) {
                    log.warn("채권 잔고 rt_cd!=0: userId={}, msg={}", userId, message(body));
                    return emptyBalance(NOTICE_BALANCE_FAILED);
                }

                for (Map<String, Object> row : asMapList(body.get("output"))) {
                    BondHoldingResponse holding = mapHolding(row);
                    holdings.add(holding);
                    // 원화가 아닌 채권을 원화 합계에 더하면 총자산이 조용히 틀어진다.
                    // 통화코드가 아예 없으면(잔고 output 에는 보통 없다) 원화로 간주한다.
                    if (isKrw(holding.getCurrencyCode()) && holding.getBuyAmount() != null) {
                        totalBuyAmount = totalBuyAmount.add(holding.getBuyAmount());
                    }
                }

                if (!hasMorePages(response)) {
                    truncated = false;
                    break;
                }
                ctxFk = defaultString(string(body, "ctx_area_fk200"));
                ctxNk = defaultString(string(body, "ctx_area_nk200"));
            }

            if (truncated) {
                // 조용히 자르면 총자산이 과소 계산된 채로 정상처럼 보인다 — 반드시 알린다.
                log.warn("채권 잔고 연속조회가 상한({}페이지)에 도달: userId={}", MAX_BALANCE_PAGES, userId);
            }

            return BondBalanceResponse.builder()
                    .holdings(holdings)
                    .totalBuyAmount(totalBuyAmount)
                    .currency(CURRENCY_KRW)
                    .faceValueDivisor(bondProperties.getFaceValueDivisor())
                    .notice(truncated ? NOTICE_BALANCE_PARTIAL : null)
                    .build();
        } catch (Exception e) {
            log.warn("채권 잔고 조회 실패: userId={}, {}", userId, e.getMessage());
            return emptyBalance(NOTICE_BALANCE_FAILED);
        }
    }

    /**
     * 연속조회 여부. KIS 는 응답 헤더 {@code tr_cont} 로 알린다 — "F"/"M" 은 다음 페이지 있음,
     * "D"/"E" 는 마지막.
     *
     * <p><b>알려진 한계</b>: 후속 요청에 {@code tr_cont: N} 요청 헤더를 실어야 한다는 것이 KIS
     * 관례인데, {@code KisApiClient} 가 임의 헤더를 받지 않아 지금은 {@code CTX_AREA_*} 만으로
     * 이어 조회한다. 실계좌에서 2페이지 이상인 계좌로 확인이 필요하다(MUST-VERIFY).
     */
    private boolean hasMorePages(ResponseEntity<?> response) {
        String trCont = response.getHeaders().getFirst("tr_cont");
        return "M".equals(trCont) || "F".equals(trCont);
    }

    // ── 매도 ────────────────────────────────────────────────────────────

    /**
     * 장내채권 매도 주문 (TTTC0958U).
     *
     * <p>조회 메서드와 달리 실패를 삼키지 않는다 — 실제 자금이 움직이는 명령이다.
     *
     * <p>KIS 검증에 맡기지 않고 <b>보내기 전에 먼저 거부</b>하는 값이 셋 있다:
     * 수량, 로트 식별자({@code BUY_DT}/{@code BUY_SEQ}), 분리과세여부({@code SPRX_YN}).
     * 셋 다 빠뜨려도 KIS 가 200 을 줄 수 있고, 그러면 <b>의도와 다른 로트가 다른 과세 방식으로</b>
     * 팔린 뒤에야 드러난다.
     *
     * @throws BusinessException 요청값 오류 또는 rt_cd != 0
     */
    public Map<String, Object> sell(Long userId, BondSellRequest request) {
        validateSellRequest(request);

        Long kisAccountId = requireKisAccountId(loadUser(userId));
        KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);
        BondCallContext ctx = tradingContext(kisAccountId, credentials);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("CANO", credentials.accountNumber());
        requestBody.put("ACNT_PRDT_CD", credentials.accountProductCode());
        requestBody.put("PDNO", request.getBondCode());
        requestBody.put("ORD_QTY2", request.getQuantity().toPlainString());
        // toString() 은 0.0001 을 "1E-4" 로 낸다 — KIS 는 지수표기를 해석하지 못한다.
        requestBody.put("BOND_ORD_UNPR", request.getUnitPrice().toPlainString());
        requestBody.put("ORD_DVSN", ORD_DVSN);
        requestBody.put("SPRX_YN", Boolean.TRUE.equals(request.getSeparateTaxation()) ? "Y" : "N");
        requestBody.put("SLL_AGCO_OPPS_SLL_YN", "N");
        requestBody.put("SAMT_MKET_PTCI_YN", "N");
        requestBody.put("BOND_RTL_MKET_YN", BOND_RTL_MKET_YN_SELL);
        // 로트 식별자. 응답 필드는 buy_sqno 지만 요청 파라미터는 BUY_SEQ 다 — 이름이 다르다.
        requestBody.put("BUY_DT", request.getBuyDate());
        requestBody.put("BUY_SEQ", request.getBuySeq());
        requestBody.put("MGCO_APTM_ODNO", "");
        requestBody.put("ORD_SVR_DVSN_CD", "0");
        requestBody.put("CTAC_TLNO", "");

        // HTTP/네트워크 실패는 KisApiClient 가 KisApiException 으로 던진다 → 그대로 전파.
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> response = kisBondClient.sell(ctx, requestBody);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        verifyKisOrderSuccess(body, userId, request.getBondCode());

        Map<String, Object> output = firstMap(body.get("output"));
        // 주문계 응답만 UPPERCASE 다(조회계는 전부 lowercase) — 같은 규칙으로 읽으면 빈다.
        String orderNumber = output == null ? null : string(output, "ODNO");

        log.info("채권 매도 주문 완료: userId={}, bondCode={}, lot={}/{}, qty={}, orderNumber={}",
                userId, request.getBondCode(), request.getBuyDate(), request.getBuySeq(),
                request.getQuantity().toPlainString(), orderNumber);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderNumber", orderNumber);
        result.put("orderTime", output == null ? null : string(output, "ORD_TMD"));
        result.put("bondCode", request.getBondCode());
        result.put("bondName", request.getBondName());
        result.put("quantity", request.getQuantity());
        result.put("unitPrice", request.getUnitPrice());
        result.put("buyDate", request.getBuyDate());
        result.put("buySeq", request.getBuySeq());
        result.put("separateTaxation", request.getSeparateTaxation());
        result.put("orderType", "SELL");
        result.put("estimatedAmount", estimatedAmount(request));
        return result;
    }

    /**
     * 주문구분. 예제가 쓰는 값은 {@code "01"} 이나 <b>KIS 가 코드표를 공개하지 않았다.</b>
     * 실계좌 검증 대상 (MUST-VERIFY).
     */
    private static final String ORD_DVSN = "01";

    /**
     * 채권소매시장여부. <b>매수 예제는 "Y" 인데 매도 예제는 "N"</b> 이라 매도 쪽 값을 따른다.
     * 실계좌 검증 대상 (MUST-VERIFY).
     */
    private static final String BOND_RTL_MKET_YN_SELL = "N";

    private void validateSellRequest(BondSellRequest request) {
        if (request.getQuantity() == null || request.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.INVALID_TRADE_QUANTITY,
                    "주문 수량은 0보다 커야 합니다 (요청 수량: " + request.getQuantity() + ")");
        }
        if (request.getUnitPrice() == null || request.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.INVALID_TRADE_PRICE,
                    "주문 단가는 0보다 커야 합니다");
        }
        if (isBlank(request.getBuyDate())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                    "매수일자(BUY_DT)가 없어 매도할 로트를 특정할 수 없습니다. 보유 채권 목록에서 선택해 주세요.");
        }
        if (isBlank(request.getBuySeq())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                    "매수순번(BUY_SEQ)이 없어 매도할 로트를 특정할 수 없습니다. 보유 채권 목록에서 선택해 주세요.");
        }
        if (request.getSeparateTaxation() == null) {
            // 임의로 "N" 을 채우면 사용자가 의도하지 않은 과세 방식으로 매도된다.
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE,
                    "분리과세 여부를 확인할 수 없습니다. 보유 채권 정보에서 확인 후 다시 시도해 주세요.");
        }
    }

    /**
     * 주문 응답 검증. rt_cd != 0 또는 빈 응답이면 예외 — 국내주식과 같은 계약이다.
     * KIS 의 실패 사유({@code msg1})를 그대로 실어 사용자가 원인을 알 수 있게 한다.
     */
    private void verifyKisOrderSuccess(Map<String, Object> body, Long userId, String bondCode) {
        if (body == null) {
            log.warn("채권 매도 응답이 비어 있음: userId={}, bondCode={}", userId, bondCode);
            throw KisApiException.serverError(NOTICE_ORDER_FAILED + " (KIS 주문 응답이 비어 있습니다)");
        }
        Object rtCd = body.get("rt_cd");
        if (!"0".equals(String.valueOf(rtCd))) {
            String msg = String.valueOf(body.getOrDefault("msg1", "KIS 채권 주문이 거부되었습니다")).trim();
            log.warn("채권 매도 rt_cd!=0: userId={}, bondCode={}, msg={}", userId, bondCode, msg);
            throw KisApiException.serverError(NOTICE_ORDER_FAILED + " (" + msg + ", rt_cd=" + rtCd + ")");
        }
    }

    /**
     * 예상 체결금액 = 수량 × 단가 ÷ 환산계수.
     *
     * <p>환산계수는 설정값({@code kis.bond.face-value-divisor})이다 — 상수로 박지 않는다.
     * 수량 단위(액면금액/좌수)가 실계좌로만 확정 가능해, 값이 틀렸을 때 재배포 없이 고쳐야 한다.
     * <b>참고용 값</b>이며 실제 체결금액과 다를 수 있다.
     */
    private BigDecimal estimatedAmount(BondSellRequest request) {
        BigDecimal divisor = bondProperties.getFaceValueDivisor();
        if (divisor == null || divisor.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return request.getQuantity().multiply(request.getUnitPrice())
                .divide(divisor, 4, java.math.RoundingMode.HALF_UP);
    }

    // ── 거래내역 ────────────────────────────────────────────────────────

    /**
     * 장내채권 거래내역 조회 (CTSC8013R). 기간 미지정 시 최근 90일.
     * 조회 경로이므로 실패 시 빈 목록 + notice.
     */
    public BondTradeHistoryResponse getHistory(Long userId, String startDate, String endDate) {
        Long kisAccountId = requireKisAccountId(loadUser(userId));

        LocalDate today = LocalDate.now();
        String from = isBlank(startDate) ? today.minusDays(HISTORY_LOOKBACK_DAYS).format(YYYYMMDD) : startDate;
        String to = isBlank(endDate) ? today.format(YYYYMMDD) : endDate;

        try {
            KisCredentials credentials = kisAuthService.getKisCredentials(kisAccountId);
            BondCallContext ctx = tradingContext(kisAccountId, credentials);

            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = kisBondClient.inquireDailyCcld(ctx,
                    credentials.accountNumber(), credentials.accountProductCode(), from, to, "", "");

            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            if (!isRtOk(body)) {
                log.warn("채권 거래내역 rt_cd!=0: userId={}, msg={}", userId, message(body));
                return emptyHistory(NOTICE_HISTORY_FAILED);
            }

            Object output = body.get("output");
            if (!(output instanceof List)) {
                // 응답 키가 output/output1 중 어느 쪽인지 미검증이라 둘 다 시도한다.
                output = body.get("output1");
            }

            List<BondTradeHistoryItem> items = asMapList(output).stream()
                    .map(this::mapHistoryItem)
                    .toList();

            return BondTradeHistoryResponse.builder()
                    .list(items)
                    .currency(CURRENCY_KRW)
                    .notice(null)
                    .build();
        } catch (Exception e) {
            log.warn("채권 거래내역 조회 실패: userId={}, {}", userId, e.getMessage());
            return emptyHistory(NOTICE_HISTORY_FAILED);
        }
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    private User loadUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    /**
     * KIS 계좌 널 가드. {@code user.getKisAccount().getId()} 를 그대로 부르면 미연동 사용자에게
     * NPE(500) 가 나가 원인을 알 수 없다. ({@code OverseasTradingService} 와 같은 패턴)
     */
    private Long requireKisAccountId(User user) {
        if (user.getKisAccount() == null) {
            throw new KisAccountNotFoundException("KIS account not registered for userId: " + user.getId());
        }
        return user.getKisAccount().getId();
    }

    /**
     * 호출 컨텍스트 구성.
     *
     * <p>자격증명을 인자로 받는 이유: 호출부가 {@code CANO}/{@code ACNT_PRDT_CD} 때문에
     * 어차피 {@link KisCredentials} 를 들고 있는데, 여기서 다시 조회하면 요청 한 번에
     * DB 조회 + Jasypt 복호화가 두 번 일어난다.
     */
    private BondCallContext tradingContext(Long kisAccountId, KisCredentials credentials) {
        String token = kisAuthService.getKisAccessToken(kisAccountId);
        return new BondCallContext(credentials.baseUrl(), token,
                credentials.appKey(), credentials.appSecret());
    }

    private BondHoldingResponse mapHolding(Map<String, Object> row) {
        BigDecimal sprxQty = decimal(row, "sprx_qty");
        BigDecimal agrxQty = decimal(row, "agrx_qty");
        return BondHoldingResponse.builder()
                .bondCode(string(row, "pdno"))
                // 잔고 응답에는 종목명이 없다. 섞여 오는 경우에만 채워지고 보통 null 이다.
                .bondName(firstNonNull(row, "prdt_name", "ksd_bond_item_name"))
                .quantity(decimal(row, "cblc_qty"))
                .orderableQuantity(decimal(row, "ord_psbl_qty"))
                .buyUnitPrice(decimal(row, "buy_unpr"))
                .buyAmount(decimal(row, "buy_amt"))
                .buyYield(decimal(row, "buy_erng_rt"))
                .buyDate(string(row, "buy_dt"))
                // 응답은 buy_sqno, 매도 요청은 BUY_SEQ — 이름이 달라 그대로 매핑하면 빈 값이 나간다.
                .buySeq(string(row, "buy_sqno"))
                .sprxQty(sprxQty)
                .agrxQty(agrxQty)
                .maturityDate(string(row, "exdt"))
                .currencyCode(string(row, "iso_crcy_cd"))
                .separateTaxation(deriveSeparateTaxation(sprxQty, agrxQty))
                .build();
    }

    /**
     * 분리과세 여부 추정. 판별 불가면 null 을 돌려주고 <b>추측하지 않는다</b> —
     * 매도 화면이 사용자에게 확인받아야 하는 값이다.
     */
    private Boolean deriveSeparateTaxation(BigDecimal sprxQty, BigDecimal agrxQty) {
        boolean hasSprx = sprxQty != null && sprxQty.compareTo(BigDecimal.ZERO) > 0;
        boolean hasAgrx = agrxQty != null && agrxQty.compareTo(BigDecimal.ZERO) > 0;
        if (hasSprx && !hasAgrx) {
            return Boolean.TRUE;
        }
        if (hasAgrx && !hasSprx) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 응답 필드명이 실계좌 미검증이라 후보 키를 순회한다 (MUST-VERIFY). */
    private BondTradeHistoryItem mapHistoryItem(Map<String, Object> row) {
        return BondTradeHistoryItem.builder()
                .orderDate(firstNonNull(row, "ord_dt", "buy_dt", "trad_dt"))
                .orderTime(firstNonNull(row, "ord_tmd", "ord_tm"))
                .bondCode(firstNonNull(row, "pdno", "stnd_iscd"))
                .bondName(firstNonNull(row, "prdt_name", "ksd_bond_item_name", "bond_name"))
                .side(mapSide(firstNonNull(row, "sll_buy_dvsn_cd", "sll_buy_dvsn_cd_name")))
                .orderQty(toDecimal(firstNonNull(row, "ord_qty", "ord_qty2")))
                .executedQty(toDecimal(firstNonNull(row, "tot_ccld_qty", "ccld_qty")))
                .executedPrice(toDecimal(firstNonNull(row, "ccld_unpr", "avg_prvs", "bond_ord_unpr")))
                .executedAmount(toDecimal(firstNonNull(row, "tot_ccld_amt", "ccld_amt")))
                .orderNo(firstNonNull(row, "odno", "ord_no"))
                .status(firstNonNull(row, "prcs_stat_name", "ord_stat_name", "rvse_cncl_dvsn_name"))
                .build();
    }

    /** KIS 매도매수구분코드(01=매도, 02=매수) 또는 명칭을 BUY/SELL 로 정규화. 미상이면 원문. */
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

    /** 통화코드가 없으면(잔고 output 에는 보통 없다) 원화로 간주한다. */
    private boolean isKrw(String currencyCode) {
        return currencyCode == null || CURRENCY_KRW.equalsIgnoreCase(currencyCode);
    }

    private BondBalanceResponse emptyBalance(String notice) {
        return BondBalanceResponse.builder()
                .holdings(new ArrayList<>())
                .totalBuyAmount(BigDecimal.ZERO)
                .currency(CURRENCY_KRW)
                .faceValueDivisor(bondProperties.getFaceValueDivisor())
                .notice(notice)
                .build();
    }

    private BondTradeHistoryResponse emptyHistory(String notice) {
        return BondTradeHistoryResponse.builder()
                .list(new ArrayList<>())
                .currency(CURRENCY_KRW)
                .notice(notice)
                .build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
