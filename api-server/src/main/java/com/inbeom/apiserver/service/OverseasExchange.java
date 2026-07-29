package com.inbeom.apiserver.service;

/**
 * 미국 해외거래소 코드/통화 단일 매핑 소스.
 *
 * <p>한 거래소가 용도별로 코드가 다르다:
 * <ul>
 *   <li>{@code balanceCode} — 잔고(VTTS3012R) / 매매(VTTT1002U·VTTT1006U) OVRS_EXCG_CD (NASD/NYSE/AMEX)</li>
 *   <li>{@code quoteCode}   — 시세(HHDFS76200200/HHDFS00000300) EXCD (NAS/NYS/AMS)</li>
 * </ul>
 * MVP 범위는 미국(NASDAQ/NYSE/AMEX)만이며 통화는 모두 USD 다.
 *
 * <p>알 수 없거나 비어있는 코드는 NASD 로 폴백한다(미국 전체 기본).
 */
public enum OverseasExchange {

    NASD("NASD", "NAS", "USD"),
    NYSE("NYSE", "NYS", "USD"),
    AMEX("AMEX", "AMS", "USD");

    private final String balanceCode;
    private final String quoteCode;
    private final String currency;

    OverseasExchange(String balanceCode, String quoteCode, String currency) {
        this.balanceCode = balanceCode;
        this.quoteCode = quoteCode;
        this.currency = currency;
    }

    /**
     * 잔고/매매용 OVRS_EXCG_CD (NASD/NYSE/AMEX).
     */
    public String balanceCode() {
        return balanceCode;
    }

    /**
     * 시세용 EXCD (NAS/NYS/AMS).
     */
    public String quoteCode() {
        return quoteCode;
    }

    /**
     * 거래통화코드 (USD).
     */
    public String currency() {
        return currency;
    }

    /**
     * 입력 코드(잔고코드 NASD/NYSE/AMEX 또는 시세코드 NAS/NYS/AMS, 대소문자 무시)를 매핑한다.
     * 알 수 없거나 비어있으면 NASD 로 폴백한다.
     */
    public static OverseasExchange fromCode(String code) {
        if (code == null || code.isBlank()) {
            return NASD;
        }
        String c = code.trim().toUpperCase();
        for (OverseasExchange ex : values()) {
            if (ex.balanceCode.equals(c) || ex.quoteCode.equals(c)) {
                return ex;
            }
        }
        return NASD;
    }
}
