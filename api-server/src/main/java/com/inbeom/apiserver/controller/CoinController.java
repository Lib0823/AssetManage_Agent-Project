package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.dto.coin.CoinAccountResponse;
import com.inbeom.apiserver.dto.coin.CoinCandleListResponse;
import com.inbeom.apiserver.dto.coin.CoinMarketListResponse;
import com.inbeom.apiserver.dto.coin.CoinOrderRequest;
import com.inbeom.apiserver.dto.coin.CoinOrderResponse;
import com.inbeom.apiserver.dto.coin.CoinOrderbookResponse;
import com.inbeom.apiserver.dto.coin.CoinTickerResponse;
import com.inbeom.apiserver.dto.coin.CoinTradeHistoryResponse;
import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.service.CoinQuoteService;
import com.inbeom.apiserver.service.CoinTradingService;
import com.inbeom.apiserver.util.JwtTokenProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 업비트 코인 시세/자산/주문 REST API.
 *
 * <p><b>원화(KRW) 마켓 전용</b>이다. BTC·USDT 마켓은 이 기능의 범위 밖이라 경로 패턴 자체가
 * {@code KRW-} 로 시작하는 코드만 받는다.
 *
 * <p>시세 4종은 공개(permitAll)이고 자산·주문·이력은 JWT 인증이 필요하다. 공개 경로는
 * {@code SecurityConfig} 에서 <b>하나씩 명시</b>된다 — {@code /coins/**} 를 통째로 열면 자산과
 * 주문이 같이 열린다.
 *
 * <p><b>조회 경로</b>는 graceful degrade 하므로 업비트 장애 시에도 200 + {@code notice} 로 응답한다.
 * <b>주문 경로</b>는 반대로 실패 시 예외가 전파되어 {@code success=false} + 4xx/5xx 로 내려간다 —
 * 주문 실패가 200 이면 사용자는 사지 않은 코인을 샀다고 믿는다.
 */
@Slf4j
@RestController
@RequestMapping("/coins")
@RequiredArgsConstructor
public class CoinController {

    /**
     * 원화 마켓 코드 패턴. 고정 경로({@code markets}, {@code accounts} 등)와 겹치지 않도록
     * {@code KRW-} 접두를 강제한다 — 제약이 없으면 {@code /coins/{market}/...} 가 다른 경로를
     * 삼킬 수 있다.
     */
    public static final String MARKET_PATTERN = "KRW-[A-Z0-9]{1,20}";
    private static final String MARKET = "/{market:" + MARKET_PATTERN + "}";

    /** 캔들 기본 개수. 업비트 상한은 200. */
    private static final int DEFAULT_CANDLE_COUNT = 100;

    private final CoinQuoteService coinQuoteService;
    private final CoinTradingService coinTradingService;
    private final JwtTokenProvider jwtTokenProvider;

    // ------------------------------------------------------------------
    // 공개 시세
    // ------------------------------------------------------------------

    /**
     * GET /api/coins/markets
     * 원화마켓 전체 목록 (유의/주의 플래그 포함). 288개 안팎이며 자주 바뀌지 않으므로
     * 프론트는 이 목록을 받아 <b>클라이언트에서 검색 필터링</b>하면 된다.
     */
    @GetMapping("/markets")
    public ResponseEntity<ApiResponse<CoinMarketListResponse>> getMarkets() {
        CoinMarketListResponse markets = coinQuoteService.getKrwMarkets();
        return ResponseEntity.ok(ApiResponse.success("Coin markets retrieved", markets));
    }

    /**
     * GET /api/coins/tickers?markets=KRW-BTC,KRW-ETH
     * 현재가 <b>배치</b> 조회.
     *
     * <p>단건 엔드포인트를 두지 않은 것은 의도적이다. 보유 종목마다 호출하면 업비트의 시세 한도
     * (IP당 10 req/s)를 즉시 소진해 <b>다른 모든 사용자의 시세까지 막는다.</b> 자산 화면은
     * 반드시 이 경로로 1회만 호출해야 한다.
     */
    @GetMapping("/tickers")
    public ResponseEntity<ApiResponse<List<CoinTickerResponse>>> getTickers(
            @RequestParam("markets") String markets
    ) {
        List<String> requested = Arrays.stream(markets.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        List<CoinTickerResponse> tickers = coinQuoteService.getTickers(requested);
        return ResponseEntity.ok(ApiResponse.success("Coin tickers retrieved", tickers));
    }

    /**
     * GET /api/coins/{market}/orderbook
     * 15단계 호가. 매도/매수가 {@code units} 한 배열에 쌍으로 들어 있다(주식 호가와 구조가 다르다).
     */
    @GetMapping(MARKET + "/orderbook")
    public ResponseEntity<ApiResponse<CoinOrderbookResponse>> getOrderbook(
            @PathVariable("market") String market
    ) {
        CoinOrderbookResponse orderbook = coinQuoteService.getOrderbook(market);
        return ResponseEntity.ok(ApiResponse.success("Coin orderbook retrieved", orderbook));
    }

    /**
     * GET /api/coins/{market}/candles?unit=days&count=100
     *
     * <p>{@code unit} 은 {@code days}/{@code weeks}/{@code months} 또는 분봉 숫자
     * ({@code 1,3,5,10,15,30,60,240})만 허용한다 — 임의 문자열을 업비트 경로에 그대로 넣지 않기
     * 위한 화이트리스트이며, 벗어나면 400이다. {@code count} 는 1~200으로 잘린다.
     */
    @GetMapping(MARKET + "/candles")
    public ResponseEntity<ApiResponse<CoinCandleListResponse>> getCandles(
            @PathVariable("market") String market,
            @RequestParam(value = "unit", defaultValue = "days") String unit,
            @RequestParam(value = "count", defaultValue = "" + DEFAULT_CANDLE_COUNT) int count
    ) {
        CoinCandleListResponse candles = coinQuoteService.getCandles(market, unit, count);
        return ResponseEntity.ok(ApiResponse.success("Coin candles retrieved", candles));
    }

    // ------------------------------------------------------------------
    // 인증 필요
    // ------------------------------------------------------------------

    /**
     * GET /api/coins/accounts
     * 업비트 보유 자산 (JWT). 원화 잔고 행도 포함된다({@code market} 이 null 인 행).
     *
     * <p>평가금액은 여기 없다 — 업비트가 수량만 주므로 <b>수량 × 현재가</b>를 프론트가 계산하며,
     * 그 현재가는 {@code /coins/tickers} 배치 조회 1회로 받아야 한다.
     */
    @GetMapping("/accounts")
    public ResponseEntity<ApiResponse<List<CoinAccountResponse>>> getAccounts(
            @RequestHeader("Authorization") String authHeader
    ) {
        Long userId = resolveUserId(authHeader);
        List<CoinAccountResponse> accounts = coinTradingService.getAccounts(userId);
        return ResponseEntity.ok(ApiResponse.success("Coin accounts retrieved", accounts));
    }

    /**
     * POST /api/coins/buy
     * 매수 주문 (JWT).
     *
     * <p><b>시장가 매수는 수량이 아니라 {@code price} 에 주문 총액을 담는다</b>(업비트 규칙).
     * 지정가 매수는 {@code quantity} + {@code price}(단가)다.
     */
    @PostMapping("/buy")
    public ResponseEntity<ApiResponse<CoinOrderResponse>> buy(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CoinOrderRequest request
    ) {
        Long userId = resolveUserId(authHeader);
        CoinOrderResponse result = coinTradingService.placeOrder(
                userId, CoinTradingService.buySide(), request);
        return ResponseEntity.ok(ApiResponse.success("Coin buy order submitted", result));
    }

    /**
     * POST /api/coins/sell
     * 매도 주문 (JWT).
     *
     * <p><b>시장가 매도는 {@code quantity} 만 담는다</b>(단가를 함께 보내면 업비트가 거부한다).
     */
    @PostMapping("/sell")
    public ResponseEntity<ApiResponse<CoinOrderResponse>> sell(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CoinOrderRequest request
    ) {
        Long userId = resolveUserId(authHeader);
        CoinOrderResponse result = coinTradingService.placeOrder(
                userId, CoinTradingService.sellSide(), request);
        return ResponseEntity.ok(ApiResponse.success("Coin sell order submitted", result));
    }

    /**
     * GET /api/coins/history
     * 코인 주문 이력 (JWT). DB {@code coin_trade_history} 에서 읽는다.
     *
     * <p>{@code submittedState} 는 <b>접수 상태</b>이지 체결 상태가 아니며 갱신되지 않는다.
     * 화면도 "접수 상태"로 표기해야 한다.
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<CoinTradeHistoryResponse>>> getHistory(
            @RequestHeader("Authorization") String authHeader
    ) {
        Long userId = resolveUserId(authHeader);
        List<CoinTradeHistoryResponse> history = coinTradingService.getHistory(userId);
        return ResponseEntity.ok(ApiResponse.success("Coin trade history retrieved", history));
    }

    private Long resolveUserId(String authHeader) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        return jwtTokenProvider.getUserIdFromToken(token);
    }
}
