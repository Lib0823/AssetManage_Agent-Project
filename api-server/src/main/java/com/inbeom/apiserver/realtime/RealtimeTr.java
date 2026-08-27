package com.inbeom.apiserver.realtime;

/**
 * Phase 1 실시간 TR 정의 (호가 + 체결가).
 *
 * <p>출처: KIS Developers 실시간시세(WebSocket) 명세.
 * <ul>
 *   <li>국내 호가 10단계: H0STASP0</li>
 *   <li>국내 체결가:      H0STCNT0</li>
 *   <li>미국 호가(1단계): HDFSASP0</li>
 *   <li>미국 체결가:      HDFSCNT0</li>
 * </ul>
 *
 * <p>Phase 2(체결통보): 국내 체결통보 {@link #KR_FILL}(H0STCNI0, 사용자키 + AES 암호 프레임)을
 * 추가한다. 체결통보는 종목이 아닌 <b>계좌 단위</b>(tr_key=HTS ID)라 위
 * {@code resolve(market,type)} / {@code Kind} 머신과 무관하며, 전용 상수(trId)만 노출한다.
 *
 * <p>US 동등화: 해외(미국) 체결통보 {@link #US_FILL}(H0GSCNI0)을 추가한다. KR_FILL 과 동일하게
 * 계좌 단위(tr_key=HTS ID)이며, 같은 유저당 연결에서 KR_FILL 과 함께 구독된다(별도 연결 아님).
 */
public enum RealtimeTr {

    /** 국내 호가 10단계. */
    KR_ASKING("H0STASP0", Market.KR, Kind.ORDERBOOK),
    /** 국내 체결가. */
    KR_TICK("H0STCNT0", Market.KR, Kind.TICK),
    /** 미국 호가(1단계). */
    US_ASKING("HDFSASP0", Market.US, Kind.ORDERBOOK),
    /** 미국 체결가. */
    US_TICK("HDFSCNT0", Market.US, Kind.TICK),
    /** 국내 체결통보 (H0STCNI0). 계좌 단위 — resolve/Kind 와 무관. */
    KR_FILL("H0STCNI0", Market.KR, Kind.FILL),
    /** 미국 체결통보 (H0GSCNI0). 계좌 단위 — KR_FILL 과 같은 연결에서 함께 구독. */
    US_FILL("H0GSCNI0", Market.US, Kind.FILL);

    /** 구독/해제 프레임의 tr_type: 1 = 등록, 2 = 해제. */
    public static final String TR_TYPE_REGISTER = "1";
    public static final String TR_TYPE_UNREGISTER = "2";

    public enum Market { KR, US }

    public enum Kind { ORDERBOOK, TICK, FILL }

    private final String trId;
    private final Market market;
    private final Kind kind;

    RealtimeTr(String trId, Market market, Kind kind) {
        this.trId = trId;
        this.market = market;
        this.kind = kind;
    }

    public String trId() {
        return trId;
    }

    public Market market() {
        return market;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * 브라우저 요청(market + type)을 TR 로 해석한다.
     *
     * @param market "KR" | "US" (대소문자 무시)
     * @param type   "orderbook" | "tick" (대소문자 무시)
     * @return 매칭 TR, 알 수 없으면 null
     */
    public static RealtimeTr resolve(String market, String type) {
        if (market == null || type == null) {
            return null;
        }
        Market m;
        try {
            m = Market.valueOf(market.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        Kind k = switch (type.trim().toLowerCase()) {
            case "orderbook", "asking", "quote" -> Kind.ORDERBOOK;
            case "tick", "price", "ccnl" -> Kind.TICK;
            default -> null;
        };
        if (k == null) {
            return null;
        }
        for (RealtimeTr tr : values()) {
            if (tr.market == m && tr.kind == k) {
                return tr;
            }
        }
        return null;
    }

    /**
     * 상향 프레임의 tr_id 로부터 TR 을 역해석한다 (parser 의 fan-out 라우팅용).
     */
    public static RealtimeTr fromTrId(String trId) {
        if (trId == null) {
            return null;
        }
        for (RealtimeTr tr : values()) {
            if (tr.trId.equals(trId)) {
                return tr;
            }
        }
        return null;
    }

}
