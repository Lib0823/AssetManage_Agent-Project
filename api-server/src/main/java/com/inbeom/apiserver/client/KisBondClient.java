package com.inbeom.apiserver.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 장내채권(domestic-bond) KIS 호출의 TR_ID·엔드포인트·필수 파라미터를 한곳에 모은 얇은 래퍼.
 *
 * <p><b>자체 HTTP 로직을 두지 않는다.</b> 실제 호출은 전부 {@link KisApiClient} 에 위임하므로
 * rate limit·응답 캐시·타임아웃·예외 정규화가 주식/해외와 똑같이 적용된다. 이 클래스가 담당하는
 * 것은 "채권 API 의 계약"뿐이다 — TR_ID, 경로, 그리고 채권에만 있는 필수 파라미터
 * ({@code PRDT_TYPE_CD}, {@code FID_COND_MRKT_DIV_CODE}) 주입.
 *
 * <p>계약 근거: {@code _workspace/bond_api_contract.md}
 * (KIS 공식 예제 저장소 {@code examples_llm/domestic_bond/} 기준. 실계좌 응답 검증은 미수행).
 *
 * <p><b>실전투자 전용.</b> 모의 TR·도메인 분기는 두지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisBondClient {

    // ── 시세계 (공개, 앱 단위 quote 자격증명) ─────────────────────────────
    /** 장내채권 기본조회. */
    public static final String TR_SEARCH_BOND_INFO = "CTPF1114R";
    /** 장내채권 발행정보. */
    public static final String TR_ISSUE_INFO = "CTPF1101R";
    /** 장내채권 현재가. */
    public static final String TR_INQUIRE_PRICE = "FHKBJ773400C0";
    /** 장내채권 호가(5단). */
    public static final String TR_INQUIRE_ASKING_PRICE = "FHKBJ773401C0";

    // ── 매매계 (인증, 사용자별 DB 자격증명) ───────────────────────────────
    /** 장내채권 잔고조회. */
    public static final String TR_INQUIRE_BALANCE = "CTSC8407R";
    /** 장내채권 매도주문. */
    public static final String TR_SELL = "TTTC0958U";
    /** 장내채권 일별 주문체결 조회. */
    public static final String TR_INQUIRE_DAILY_CCLD = "CTSC8013R";

    private static final String BOND_PREFIX = "/uapi/domestic-bond/v1";

    public static final String PATH_SEARCH_BOND_INFO = BOND_PREFIX + "/quotations/search-bond-info";
    public static final String PATH_ISSUE_INFO = BOND_PREFIX + "/quotations/issue-info";
    public static final String PATH_INQUIRE_PRICE = BOND_PREFIX + "/quotations/inquire-price";
    public static final String PATH_INQUIRE_ASKING_PRICE = BOND_PREFIX + "/quotations/inquire-asking-price";
    public static final String PATH_INQUIRE_BALANCE = BOND_PREFIX + "/trading/inquire-balance";
    public static final String PATH_SELL = BOND_PREFIX + "/trading/sell";
    public static final String PATH_INQUIRE_DAILY_CCLD = BOND_PREFIX + "/trading/inquire-daily-ccld";

    /**
     * 채권 표준종목코드(PDNO)는 <b>12자리 영숫자 혼합</b>이다 — {@code KR2033022D33}, {@code KR6449111CB8}.
     * 주식(6자리 숫자) 패턴을 재사용하면 정상 채권이 전부 거부된다.
     */
    public static final String BOND_CODE_PATTERN = "[A-Za-z0-9]{12}";

    /**
     * 상품유형코드. 기본조회/발행정보의 필수 파라미터로, KIS 예제는 {@code "302"} 만 사용한다.
     *
     * <p><b>채권 종류(국채/회사채/전환사채 등)별로 다를 수 있으며 실계좌 검증이 필요하다.</b>
     * 다른 코드가 필요한 채권이 있으면 이 값 하나 때문에 조회가 실패하므로, 실계좌에서
     * 국채 이외 종목을 한 번 조회해 확인할 것. (사전 검토 {@code preflight_bond.md} R-7)
     */
    public static final String PRDT_TYPE_CD = "302";

    /** 채권 시세/호가의 시장분류코드. 채권은 "B". */
    public static final String MARKET_DIV_CODE = "B";

    /** 잔고 조회조건: "00"=전체, "01"=상품번호단위. */
    public static final String INQR_CNDT_ALL = "00";

    private final KisApiClient kisApiClient;

    /**
     * 채권 호출에 필요한 자격증명 묶음. 시세계는 앱 단위(quote) 키, 매매계는 사용자별 DB 키가
     * 들어오며 이 클래스는 둘을 구분하지 않는다.
     */
    public record BondCallContext(String baseUrl, String token, String appKey, String appSecret) {}

    /** 장내채권 기본조회 (CTPF1114R). */
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> searchBondInfo(BondCallContext ctx, String bondCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("PDNO", bondCode);
        params.put("PRDT_TYPE_CD", PRDT_TYPE_CD);
        return get(ctx, PATH_SEARCH_BOND_INFO, TR_SEARCH_BOND_INFO, params);
    }

    /** 장내채권 발행정보 (CTPF1101R). */
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> issueInfo(BondCallContext ctx, String bondCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("PDNO", bondCode);
        params.put("PRDT_TYPE_CD", PRDT_TYPE_CD);
        return get(ctx, PATH_ISSUE_INFO, TR_ISSUE_INFO, params);
    }

    /** 장내채권 현재가 (FHKBJ773400C0). */
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> inquirePrice(BondCallContext ctx, String bondCode) {
        return get(ctx, PATH_INQUIRE_PRICE, TR_INQUIRE_PRICE, marketDivParams(bondCode));
    }

    /** 장내채권 호가 (FHKBJ773401C0). */
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> inquireAskingPrice(BondCallContext ctx, String bondCode) {
        return get(ctx, PATH_INQUIRE_ASKING_PRICE, TR_INQUIRE_ASKING_PRICE, marketDivParams(bondCode));
    }

    /**
     * 장내채권 잔고조회 (CTSC8407R).
     *
     * <p>{@code PDNO}/{@code BUY_DT} 는 "전체 조회" 시 공백을 넘긴다. {@code Map.of()} 는 null 을
     * 받지 못하고 {@link KisApiClient#get} 은 쿼리스트링을 인코딩 없이 이어붙이므로 <b>빈 문자열</b>을 쓴다.
     *
     * @param ctxAreaFk 연속조회검색조건200 (첫 페이지는 빈 문자열)
     * @param ctxAreaNk 연속조회키200 (첫 페이지는 빈 문자열)
     */
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> inquireBalance(BondCallContext ctx, String cano, String acntPrdtCd,
                                              String ctxAreaFk, String ctxAreaNk) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CANO", cano);
        params.put("ACNT_PRDT_CD", acntPrdtCd);
        params.put("INQR_CNDT", INQR_CNDT_ALL);
        params.put("PDNO", "");
        params.put("BUY_DT", "");
        params.put("CTX_AREA_FK200", ctxAreaFk == null ? "" : ctxAreaFk);
        params.put("CTX_AREA_NK200", ctxAreaNk == null ? "" : ctxAreaNk);
        return get(ctx, PATH_INQUIRE_BALANCE, TR_INQUIRE_BALANCE, params);
    }

    /**
     * 장내채권 일별 주문체결 조회 (CTSC8013R).
     *
     * <p><b>MUST-VERIFY</b>: 이 TR 의 요청 파라미터·응답 필드명은 실측 계약 문서
     * ({@code bond_api_contract.md})가 다루지 않은 범위라 국내주식 체결조회 관례를 따랐다.
     * 실계좌 응답으로 확인이 필요하며, 응답 파싱은 후보 키 순회로 방어한다.
     */
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> inquireDailyCcld(BondCallContext ctx, String cano, String acntPrdtCd,
                                                String startDate, String endDate,
                                                String ctxAreaFk, String ctxAreaNk) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("CANO", cano);
        params.put("ACNT_PRDT_CD", acntPrdtCd);
        params.put("INQR_STRT_DT", startDate);
        params.put("INQR_END_DT", endDate);
        params.put("SLL_BUY_DVSN_CD", "00");   // 전체
        params.put("SORT_SQN_DVSN", "01");
        params.put("PDNO", "");
        params.put("CTX_AREA_FK200", ctxAreaFk == null ? "" : ctxAreaFk);
        params.put("CTX_AREA_NK200", ctxAreaNk == null ? "" : ctxAreaNk);
        return get(ctx, PATH_INQUIRE_DAILY_CCLD, TR_INQUIRE_DAILY_CCLD, params);
    }

    /**
     * 장내채권 매도주문 (TTTC0958U).
     *
     * <p>요청 본문 구성은 {@code BondTradingService} 가 담당한다 — 로트 식별자(BUY_DT/BUY_SEQ)와
     * 분리과세여부(SPRX_YN)는 사용자 보유 상태에서 유도되는 값이라 클라이언트 계층이 결정할 수 없다.
     */
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map> sell(BondCallContext ctx, Map<String, Object> requestBody) {
        return kisApiClient.post(ctx.baseUrl(), PATH_SELL, TR_SELL,
                ctx.token(), ctx.appKey(), ctx.appSecret(), requestBody, Map.class);
    }

    private Map<String, String> marketDivParams(String bondCode) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("FID_COND_MRKT_DIV_CODE", MARKET_DIV_CODE);
        params.put("FID_INPUT_ISCD", bondCode);
        return params;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> get(BondCallContext ctx, String path, String trId, Map<String, String> params) {
        return kisApiClient.get(ctx.baseUrl(), path, trId,
                ctx.token(), ctx.appKey(), ctx.appSecret(), params, Map.class);
    }
}
