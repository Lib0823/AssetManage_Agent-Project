package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.service.OverseasExchange;

/**
 * 미국 실시간 시세 구독용 {@code tr_key} 빌더 (단일 출처).
 *
 * <p><b>MUST-VERIFY</b> — 해외 실시간 tr_key 포맷.
 * KIS Developers 실시간 해외주식(HDFSASP0/HDFSCNT0) 명세상 tr_key 는
 * {@code <실시간구분><거래소코드(3)><심볼>} 형태로 추정한다(예: {@code DNASAAPL}).
 * <ul>
 *   <li>실시간구분 접두: 지연시세 {@code D} / 실시간 {@code R}. 모의/지연 환경은 {@code D},
 *       실전 실시간은 {@code R} 로 알려져 있으나 계정 권한별로 다르다 → MUST-VERIFY.</li>
 *   <li>거래소코드: NAS(NASDAQ) / NYS(NYSE) / AMS(AMEX) — {@link OverseasExchange#quoteCode()} 재사용.</li>
 *   <li>심볼: 티커 대문자 (예: AAPL).</li>
 * </ul>
 * 라이브 검증 방법: 미국 장 시간에 R/D 양쪽으로 구독 프레임을 보내 ACK(rt_cd=0)가 오는 접두를
 * 확정한다. {@link KisFrameParser} 진단 로거가 첫 ACK/데이터 프레임의 tr_key 를 출력하므로
 * 응답된 키와 본 빌더 출력이 일치하는지 대조한다. 접두 기본값은 아래 {@link #realtimePrefix} 한 곳.
 *
 * <p>국내(KR)는 종목코드를 그대로 tr_key 로 쓰므로 본 헬퍼를 거치지 않는다.
 */
public final class OverseasTrKey {

    private OverseasTrKey() {
    }

    /**
     * MUST-VERIFY — 실시간 구분 접두.
     * 운영(실전 실시간 권한)에서는 'R', 지연/모의 환경에서는 'D' 일 수 있다.
     * 보수적으로 실시간 'R' 을 기본값으로 두되, 한 곳에서만 정의해 라이브 확정 시 즉시 교체한다.
     */
    private static final char REALTIME_PREFIX = 'R';

    /** 지연시세 접두(대안). 라이브에서 'R' 이 거부되면 이 값으로 시도. */
    private static final char DELAYED_PREFIX = 'D';

    /**
     * 실시간(권장) tr_key 빌드. 예: NASDAQ AAPL → {@code RNASAAPL}.
     *
     * @param exchange 거래소 (NASD/NYSE/AMEX 또는 NAS/NYS/AMS, null 이면 NASD 폴백)
     * @param symbol   티커 (예 AAPL)
     */
    public static String build(OverseasExchange exchange, String symbol) {
        return build(REALTIME_PREFIX, exchange, symbol);
    }

    /**
     * 지연시세 tr_key 빌드 (라이브에서 실시간 접두가 거부될 때의 폴백).
     */
    public static String buildDelayed(OverseasExchange exchange, String symbol) {
        return build(DELAYED_PREFIX, exchange, symbol);
    }

    /**
     * 거래소 코드 문자열(NAS/NYS/AMS 또는 NASD/NYSE/AMEX)로 직접 빌드.
     */
    public static String build(String exchangeCode, String symbol) {
        return build(REALTIME_PREFIX, OverseasExchange.fromCode(exchangeCode), symbol);
    }

    private static String build(char prefix, OverseasExchange exchange, String symbol) {
        OverseasExchange ex = (exchange != null) ? exchange : OverseasExchange.NASD;
        String sym = (symbol == null) ? "" : symbol.trim().toUpperCase();
        // <접두><거래소코드(NAS/NYS/AMS)><심볼>  — MUST-VERIFY (위 클래스 주석 참조)
        return prefix + ex.quoteCode() + sym;
    }
}
