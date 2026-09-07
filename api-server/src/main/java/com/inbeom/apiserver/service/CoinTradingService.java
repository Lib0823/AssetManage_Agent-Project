package com.inbeom.apiserver.service;

import com.inbeom.apiserver.client.UpbitApiClient;
import com.inbeom.apiserver.domain.CoinTradeHistory;
import com.inbeom.apiserver.dto.coin.CoinAccountResponse;
import com.inbeom.apiserver.dto.coin.CoinOrderRequest;
import com.inbeom.apiserver.dto.coin.CoinOrderResponse;
import com.inbeom.apiserver.dto.coin.CoinTradeHistoryResponse;
import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import com.inbeom.apiserver.repository.CoinTradeHistoryRepository;
import com.inbeom.apiserver.service.UpbitAuthService.UpbitCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.inbeom.apiserver.service.CoinResponses.decimal;
import static com.inbeom.apiserver.service.CoinResponses.offsetDateTime;
import static com.inbeom.apiserver.service.CoinResponses.string;
import static com.inbeom.apiserver.service.CoinResponses.toMaps;

/**
 * 코인 자산조회 · 주문.
 *
 * <p><b>이 클래스의 핵심은 주문 타입 매핑이다.</b> 업비트는 세 가지 파라미터 조합을 쓰는데
 * 시장가에서 매수/매도가 <b>비대칭</b>이라 실수하기 쉽다:
 *
 * <table border="1">
 *   <caption>업비트 주문 파라미터</caption>
 *   <tr><th>의도</th><th>side</th><th>ord_type</th><th>volume</th><th>price</th></tr>
 *   <tr><td>지정가 매수/매도</td><td>bid/ask</td><td>{@code limit}</td><td>수량</td><td>단가</td></tr>
 *   <tr><td><b>시장가 매수</b></td><td>bid</td><td>{@code price}</td><td><b>미전송</b></td><td><b>총액</b></td></tr>
 *   <tr><td><b>시장가 매도</b></td><td>ask</td><td>{@code market}</td><td>수량</td><td><b>미전송</b></td></tr>
 * </table>
 *
 * <p>{@code ord_type=price} 는 <b>매수 전용</b>, {@code market} 은 <b>매도 전용</b>이다. 반대 조합은
 * 여기서 만들어질 수 없게 막는다 — 잘못 나가면 의도와 다른 실제 주문이 체결된다.
 *
 * <p><b>수량·가격은 반드시 {@link BigDecimal#toPlainString()} 으로 직렬화한다.</b>
 * {@code toString()} 은 작은 값에서 지수 표기({@code 1.23E-6})를 내고, 업비트는 그것을
 * 수량으로 받아들이지 않는다.
 *
 * <p>조회 경로와 달리 <b>주문 경로는 예외를 전파</b>한다. 주문 실패가 200 으로 내려가면 사용자는
 * 사지 않은 코인을 샀다고 믿는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoinTradingService {

    private static final String SIDE_BID = "bid";
    private static final String SIDE_ASK = "ask";

    private static final String ORD_TYPE_LIMIT = "limit";
    /** 시장가 <b>매수</b> 전용. price 에 총액을 싣는다. */
    private static final String ORD_TYPE_PRICE = "price";
    /** 시장가 <b>매도</b> 전용. volume 에 수량을 싣는다. */
    private static final String ORD_TYPE_MARKET = "market";

    private final UpbitApiClient upbitApiClient;
    private final UpbitAuthService upbitAuthService;
    private final CoinQuoteService coinQuoteService;
    private final CoinTradeHistoryRepository coinTradeHistoryRepository;

    /**
     * 보유 자산 조회. 원화(KRW) 잔고 행도 함께 온다.
     *
     * <p>파라미터가 없는 요청이므로 JWT 에 {@code query_hash} 가 들어가지 않는다
     * ({@code UpbitApiClient.buildJwt} 가 빈 맵을 그렇게 처리한다).
     */
    public List<CoinAccountResponse> getAccounts(Long userId) {
        UpbitCredentials credentials = upbitAuthService.getCredentials(userId);

        List<Map<String, Object>> raw = toMaps(upbitApiClient.getAuthenticated(
                "/v1/accounts", Map.of(),
                credentials.accessKey(), credentials.secretKey(), Map[].class).getBody());

        List<CoinAccountResponse> accounts = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            String currency = string(row, "currency");
            accounts.add(CoinAccountResponse.builder()
                    .currency(currency)
                    // 원화 자체는 마켓이 아니다. KRW 행에 "KRW-KRW" 를 만들면 티커 배치 조회가 404 난다.
                    .market(currency == null || "KRW".equals(currency) ? null : "KRW-" + currency)
                    .balance(decimal(row, "balance"))
                    .locked(decimal(row, "locked"))
                    .avgBuyPrice(decimal(row, "avg_buy_price"))
                    .unitCurrency(string(row, "unit_currency"))
                    .build());
        }
        return accounts;
    }

    /**
     * 매수/매도 주문.
     *
     * @param side {@code bid}(매수) 또는 {@code ask}(매도) — 컨트롤러의 엔드포인트가 정한다
     */
    @Transactional
    public CoinOrderResponse placeOrder(Long userId, String side, CoinOrderRequest request) {
        String market = coinQuoteService.requireKrwMarket(request.getMarket());
        String identifier = resolveIdentifier(request.getIdempotencyKey());

        // 멱등: 같은 사용자가 같은 키로 다시 보내면 업비트를 다시 부르지 않는다.
        // (타임아웃 뒤 재시도가 중복 주문이 되는 것을 막는 것이 identifier 컬럼의 존재 이유다.)
        //
        // userId 로 좁히는 것이 필수다 — 키는 클라이언트가 값을 정하므로, 전역 조회하면 남이 쓴 키를
        // 흉내내어 타인의 주문 내역을 받아볼 수 있고 자기 주문은 조용히 실행되지 않는다.
        var existing = coinTradeHistoryRepository.findByUserIdAndIdentifier(userId, identifier);
        if (existing.isPresent()) {
            log.info("Duplicate coin order suppressed by idempotency key: userId={} identifier={}",
                    userId, identifier);
            return toOrderResponse(existing.get(), true);
        }

        Map<String, String> params = buildOrderParams(market, side, request);

        UpbitCredentials credentials = upbitAuthService.getCredentials(userId);
        params.put("identifier", identifier);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = upbitApiClient.postAuthenticated(
                "/v1/orders", params,
                credentials.accessKey(), credentials.secretKey(), Map.class).getBody();

        if (body == null || string(body, "uuid") == null) {
            throw new BusinessException(ErrorCode.UPBIT_API_ERROR,
                    "업비트 주문 응답에 주문 번호가 없습니다. 업비트에서 주문 내역을 직접 확인해 주세요.");
        }

        CoinTradeHistory saved = coinTradeHistoryRepository.save(CoinTradeHistory.builder()
                .userId(userId)
                .market(market)
                // 한글명은 업비트 마켓 목록에만 있고 CoinQuoteService 에는 캐시가 없다. 여기서 채우려면
                // 주문 경로에 시세 호출이 하나 늘고, 그 호출이 실패하면 실주문까지 위태로워진다.
                // 표시용 필드 하나 때문에 치를 값이 아니라서 비워 둔다 —
                // 프론트가 market 코드(KRW-BTC → BTC)로 폴백한다(TransactionsView).
                .coinName(null)
                .orderSide(string(body, "side"))
                .ordType(string(body, "ord_type"))
                // state 는 접수 직후 값(대개 wait)이며 체결 여부가 아니다.
                .submittedState(string(body, "state"))
                .volume(decimal(body, "volume"))
                .price(decimal(body, "price"))
                .executedVolume(decimal(body, "executed_volume"))
                .paidFee(decimal(body, "paid_fee"))
                .orderUuid(string(body, "uuid"))
                .identifier(identifier)
                .orderedAt(offsetDateTime(string(body, "created_at")))
                .build());

        log.info("Coin order submitted: userId={} market={} side={} ordType={} uuid={}",
                userId, market, saved.getOrderSide(), saved.getOrdType(), saved.getOrderUuid());

        return CoinOrderResponse.builder()
                .orderUuid(saved.getOrderUuid())
                .market(market)
                .side(saved.getOrderSide())
                .ordType(saved.getOrdType())
                .submittedState(saved.getSubmittedState())
                .volume(saved.getVolume())
                .price(saved.getPrice())
                .executedVolume(saved.getExecutedVolume())
                .remainingVolume(decimal(body, "remaining_volume"))
                .paidFee(saved.getPaidFee())
                .identifier(identifier)
                .orderedAt(saved.getOrderedAt())
                .duplicate(false)
                .build();
    }

    @Transactional(readOnly = true)
    public List<CoinTradeHistoryResponse> getHistory(Long userId) {
        return coinTradeHistoryRepository.findByUserIdOrderByOrderedAtDesc(userId).stream()
                .map(h -> CoinTradeHistoryResponse.builder()
                        .id(h.getId())
                        .market(h.getMarket())
                        .coinName(h.getCoinName())
                        .orderSide(h.getOrderSide())
                        .ordType(h.getOrdType())
                        .submittedState(h.getSubmittedState())
                        .volume(h.getVolume())
                        .price(h.getPrice())
                        .executedVolume(h.getExecutedVolume())
                        .paidFee(h.getPaidFee())
                        .orderUuid(h.getOrderUuid())
                        .identifier(h.getIdentifier())
                        .orderedAt(h.getOrderedAt())
                        .build())
                .toList();
    }

    // ------------------------------------------------------------------
    // 주문 파라미터 매핑 — 이 메서드가 이 기능에서 가장 위험한 곳이다
    // ------------------------------------------------------------------

    /**
     * 사용자 의도 → 업비트 주문 파라미터.
     *
     * <p>{@link LinkedHashMap} 인 것은 우연이 아니다. 이 맵은 JSON 바디가 되는 동시에
     * <b>JWT {@code query_hash} 의 해싱 대상 쿼리스트링</b>이 된다. 업비트는 정렬하지 않은
     * 삽입 순서 그대로를 해싱하므로, 순서가 흔들리는 맵을 쓰면 서명이 어긋나 401 이 난다.
     */
    private Map<String, String> buildOrderParams(String market, String side, CoinOrderRequest request) {
        boolean isBuy = SIDE_BID.equals(side);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("market", market);
        params.put("side", side);

        if (request.getOrderType() == CoinOrderRequest.OrderType.LIMIT) {
            BigDecimal quantity = requirePositive(request.getQuantity(), "지정가 주문에는 수량이 필요합니다");
            BigDecimal price = requirePositive(request.getPrice(), "지정가 주문에는 단가가 필요합니다");
            params.put("ord_type", ORD_TYPE_LIMIT);
            params.put("volume", quantity.toPlainString());
            params.put("price", price.toPlainString());
            return params;
        }

        if (isBuy) {
            // 시장가 매수: 수량이 아니라 총액을 지정한다. volume 을 함께 보내면 업비트가 거부한다.
            BigDecimal total = requirePositive(request.getPrice(),
                    "시장가 매수에는 주문 총액(price)이 필요합니다. 수량이 아니라 금액을 지정합니다.");
            params.put("ord_type", ORD_TYPE_PRICE);
            params.put("price", total.toPlainString());
            return params;
        }

        // 시장가 매도: 수량만 지정한다. price 를 함께 보내면 업비트가 거부한다.
        BigDecimal quantity = requirePositive(request.getQuantity(),
                "시장가 매도에는 주문 수량(quantity)이 필요합니다");
        params.put("ord_type", ORD_TYPE_MARKET);
        params.put("volume", quantity.toPlainString());
        return params;
    }

    private BigDecimal requirePositive(BigDecimal value, String message) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.INVALID_COIN_ORDER, message);
        }
        return value;
    }

    /** 프론트가 멱등키를 주지 않으면 서버가 만든다(같은 요청의 재시도만 막지 못할 뿐, 형식은 동일). */
    private String resolveIdentifier(String requested) {
        if (requested != null && !requested.isBlank()) {
            String trimmed = requested.trim();
            if (trimmed.length() > 64) {
                throw new BusinessException(ErrorCode.INVALID_COIN_ORDER, "멱등키는 64자를 넘을 수 없습니다");
            }
            return trimmed;
        }
        return UUID.randomUUID().toString();
    }

    private CoinOrderResponse toOrderResponse(CoinTradeHistory history, boolean duplicate) {
        return CoinOrderResponse.builder()
                .orderUuid(history.getOrderUuid())
                .market(history.getMarket())
                .side(history.getOrderSide())
                .ordType(history.getOrdType())
                .submittedState(history.getSubmittedState())
                .volume(history.getVolume())
                .price(history.getPrice())
                .executedVolume(history.getExecutedVolume())
                .paidFee(history.getPaidFee())
                .identifier(history.getIdentifier())
                .orderedAt(history.getOrderedAt())
                .duplicate(duplicate)
                .build();
    }

    /**
     * 매수/매도 side 상수.
     *
     * <p>요청 DTO 가 아니라 <b>엔드포인트</b>({@code /coins/buy} vs {@code /coins/sell})가 방향을
     * 정한다. 둘 다 두면 {@code POST /coins/buy} 에 {@code side=SELL} 을 실을 수 있게 되어,
     * 어느 쪽을 믿을지 결정해야 하는 문제가 생긴다 — 그 결정이 틀리면 실제 자금이 반대로 움직인다.
     */
    public static String buySide() {
        return SIDE_BID;
    }

    public static String sellSide() {
        return SIDE_ASK;
    }
}
