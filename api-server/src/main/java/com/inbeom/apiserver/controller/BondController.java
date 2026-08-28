package com.inbeom.apiserver.controller;

import com.inbeom.apiserver.client.KisBondClient;
import com.inbeom.apiserver.dto.bond.BondBalanceResponse;
import com.inbeom.apiserver.dto.bond.BondInfoResponse;
import com.inbeom.apiserver.dto.bond.BondIssueInfoResponse;
import com.inbeom.apiserver.dto.bond.BondOrderbookResponse;
import com.inbeom.apiserver.dto.bond.BondPriceResponse;
import com.inbeom.apiserver.dto.bond.BondSellRequest;
import com.inbeom.apiserver.dto.bond.BondTradeHistoryResponse;
import com.inbeom.apiserver.dto.common.ApiResponse;
import com.inbeom.apiserver.service.BondQuoteService;
import com.inbeom.apiserver.service.BondTradingService;
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

import java.util.Map;

/**
 * 장내채권 시세/잔고/매도 REST API.
 *
 * <p><b>범위는 "보유 조회 + 매도"다.</b> 매수·검색은 의도적으로 없다 — KIS 채권 API 18개 중
 * 종목명·키워드로 채권을 찾는 API가 하나도 없어(전부 12자리 표준코드를 입력으로 요구) 검색 화면을
 * 만들 수 없고, 검색이 없으면 매수 진입 경로도 없기 때문이다. 유일한 진입점은 자산 화면의
 * 보유 채권 카드다.
 *
 * <p>시세({@code /bonds/{code}} 계열)는 공개(permitAll)이며 앱 단위 quote 자격증명으로 조회한다.
 * 잔고/매도/거래내역은 JWT 인증 후 사용자별 KIS 키로 처리한다.
 *
 * <p><b>조회 경로</b>는 graceful degrade 하므로 미연동/실패 시에도 200 + {@code data.notice} 로
 * 응답한다(화면이 통째로 사라지지 않아야 한다). <b>매도 경로</b>는 반대로 실패 시 예외가 전파되어
 * {@code ApiResponse.success=false} + 4xx/5xx 로 내려간다 — 주문 실패가 200 으로 내려가면
 * 사용자는 팔리지 않은 채권을 팔았다고 믿는다.
 *
 * <p><b>경로 매핑 주의</b>: {@code /bonds/balance}(고정)와 {@code /bonds/{bondCode}}(경로변수)가
 * 같은 깊이다. {@code balance}·{@code history} 가 종목코드로 잡히지 않도록 경로변수에
 * 12자리 영숫자 제약({@link KisBondClient#BOND_CODE_PATTERN})을 건다.
 */
@Slf4j
@RestController
@RequestMapping("/bonds")
@RequiredArgsConstructor
public class BondController {

    /** 12자리 영숫자 — 채권 표준종목코드. {@code balance}/{@code history} 와 겹치지 않는다. */
    private static final String CODE = "/{bondCode:" + KisBondClient.BOND_CODE_PATTERN + "}";

    private final BondQuoteService bondQuoteService;
    private final BondTradingService bondTradingService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * GET /api/bonds/balance
     * 보유 채권 잔고 (JWT). 결과는 종목이 아니라 <b>매수 로트</b> 목록이며,
     * 각 항목의 {@code buyDate}/{@code buySeq} 가 매도에 필요하다.
     * KIS 조회 실패 시 빈 목록 + notice, KIS 계좌 미연동 시 4xx.
     */
    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<BondBalanceResponse>> getBalance(
            @RequestHeader("Authorization") String authHeader
    ) {
        Long userId = resolveUserId(authHeader);
        BondBalanceResponse balance = bondTradingService.getBalance(userId);
        return ResponseEntity.ok(ApiResponse.success("Bond balance retrieved", balance));
    }

    /**
     * GET /api/bonds/history?startDate=20260101&endDate=20260131
     * 채권 거래내역 (JWT). 기간 미지정 시 최근 90일. KIS 에서 직접 조회하므로 DB 기록이 없다.
     */
    @GetMapping("/history")
    public ResponseEntity<ApiResponse<BondTradeHistoryResponse>> getHistory(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate
    ) {
        Long userId = resolveUserId(authHeader);
        BondTradeHistoryResponse history = bondTradingService.getHistory(userId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success("Bond trade history retrieved", history));
    }

    /**
     * POST /api/bonds/sell
     * 채권 매도 주문 (JWT).
     *
     * <p>요청의 {@code buyDate}/{@code buySeq} 는 <b>잔고 조회 응답을 그대로 실어 보내는 값</b>이다
     * (사용자 입력이 아니다). 없으면 어느 매수분을 파는지 특정할 수 없어 거부된다.
     * 실패 시 예외 전파 → {@code success=false} + ErrorCode 별 HTTP 상태.
     */
    @PostMapping("/sell")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sell(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody BondSellRequest request
    ) {
        Long userId = resolveUserId(authHeader);
        Map<String, Object> result = bondTradingService.sell(userId, request);
        return ResponseEntity.ok(ApiResponse.success("Bond sell order executed successfully", result));
    }

    /**
     * GET /api/bonds/{bondCode}
     * 채권 기본조회 (공개). 종목명·통화·표면이율 등. 미연동/실패 시 값 null + notice.
     */
    @GetMapping(CODE)
    public ResponseEntity<ApiResponse<BondInfoResponse>> getBondInfo(
            @PathVariable("bondCode") String bondCode
    ) {
        BondInfoResponse info = bondQuoteService.getBondInfo(bondCode);
        return ResponseEntity.ok(ApiResponse.success("Bond info retrieved", info));
    }

    /**
     * GET /api/bonds/{bondCode}/issue-info
     * 채권 발행정보 (공개). 만기일·표면금리·<b>평가사별 신용등급 4개</b>·호가단위.
     */
    @GetMapping(CODE + "/issue-info")
    public ResponseEntity<ApiResponse<BondIssueInfoResponse>> getIssueInfo(
            @PathVariable("bondCode") String bondCode
    ) {
        BondIssueInfoResponse issueInfo = bondQuoteService.getIssueInfo(bondCode);
        return ResponseEntity.ok(ApiResponse.success("Bond issue info retrieved", issueInfo));
    }

    /**
     * GET /api/bonds/{bondCode}/price
     * 채권 현재가 (공개). 단가는 소수를 가지므로 화면에서 정수로 반올림하면 안 된다.
     */
    @GetMapping(CODE + "/price")
    public ResponseEntity<ApiResponse<BondPriceResponse>> getPrice(
            @PathVariable("bondCode") String bondCode
    ) {
        BondPriceResponse price = bondQuoteService.getPrice(bondCode);
        return ResponseEntity.ok(ApiResponse.success("Bond price retrieved", price));
    }

    /**
     * GET /api/bonds/{bondCode}/orderbook
     * 채권 5단 호가 (공개). 장내채권은 유동성이 낮아 <b>호가가 비는 것이 정상</b>이며
     * 오류가 아니다 — 빈 목록이 내려온다.
     */
    @GetMapping(CODE + "/orderbook")
    public ResponseEntity<ApiResponse<BondOrderbookResponse>> getOrderbook(
            @PathVariable("bondCode") String bondCode
    ) {
        BondOrderbookResponse orderbook = bondQuoteService.getOrderbook(bondCode);
        return ResponseEntity.ok(ApiResponse.success("Bond orderbook retrieved", orderbook));
    }

    private Long resolveUserId(String authHeader) {
        String token = jwtTokenProvider.resolveBearerToken(authHeader);
        return jwtTokenProvider.getUserIdFromToken(token);
    }
}
