package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.dto.realtime.QuoteMessage;
import com.inbeom.apiserver.dto.realtime.TickMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * KIS 실시간 상향 데이터 프레임 파서.
 *
 * <p>데이터 프레임 형식: {@code <0|1>|<tr_id>|<count>|<f0^f1^f2...>}
 * (0 = 평문 = Phase 1 호가/체결가, 1 = 암호 = 체결통보 Phase 2 → 본 파서는 평문만 처리).
 * 필드는 {@code ^} 로 구분되며, count 가 2 이상이면 같은 레코드 길이만큼 반복된다.
 *
 * <p><b>MUST-VERIFY — 필드 인덱스</b>. 아래 각 TR 의 positional index 는 KIS Developers
 * 실시간시세 명세의 응답 필드 "순서"에 기반한 추정이며 라이브 프레임으로 확정해야 한다.
 * 이를 위해 {@code kis.realtime.frame-debug=true} 일 때 TR 별 첫 레코드의 index→value 매핑을
 * 한 번만 로그로 출력하는 {@link #logDiagnostic} 진단 로거를 둔다. 명세 필드명과 라이브 값을
 * 대조해 아래 상수를 확정한다. 모든 인덱스 상수는 이 클래스 한 곳에만 둔다.
 */
@Slf4j
@Component
public class KisFrameParser {

    /** PINGPONG keepalive 프레임의 tr_id (그대로 echo). */
    public static final String TR_PINGPONG = "PINGPONG";

    private final boolean frameDebug;

    // TR 별 진단 로그 1회 출력 플래그 (동시성 무해 — 중복 1~2회 출력은 허용)
    private final java.util.Set<String> diagnosedTrIds =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public KisFrameParser(@Value("${kis.realtime.frame-debug:false}") boolean frameDebug) {
        this.frameDebug = frameDebug;
    }

    // ───────────────────────────────────────────────────────────────────────
    // MUST-VERIFY 인덱스 상수 (출처: KIS Developers 실시간시세 명세 응답 필드 순서)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * 국내 체결가 H0STCNT0 필드 순서 (MUST-VERIFY).
     * 명세 순서(앞부분): MKSC_SHRN_ISCD(0)=유가증권단축종목코드, STCK_CNTG_HOUR(1)=체결시간,
     * STCK_PRPR(2)=현재가, PRDY_VRSS_SIGN(3)=전일대비부호, PRDY_VRSS(4)=전일대비,
     * PRDY_CTRT(5)=전일대비율, ..., CNTG_VOL(?)=체결거래량, ACML_VOL(?)=누적거래량.
     * 체결량/누적거래량 인덱스는 가변 위치라 라이브로 확정 필요 → 진단 로거로 대조.
     */
    private static final class KrTick {
        static final int SYMBOL = 0;        // MKSC_SHRN_ISCD
        static final int CURRENT_PRICE = 2; // STCK_PRPR
        static final int SIGN = 3;          // PRDY_VRSS_SIGN (1/2 상승, 4/5 하락, 3 보합)
        static final int CHANGE = 4;        // PRDY_VRSS (부호 없음 → SIGN 으로 부호 결정)
        static final int CHANGE_RATE = 5;   // PRDY_CTRT
        static final int VOLUME = 12;       // CNTG_VOL  (MUST-VERIFY: 라이브 확정)
        static final int ACC_VOLUME = 13;   // ACML_VOL  (MUST-VERIFY: 라이브 확정)
    }

    /**
     * 국내 호가 10단계 H0STASP0 필드 순서 (MUST-VERIFY).
     * 명세 순서(앞부분): MKSC_SHRN_ISCD(0), BSOP_HOUR(1), HOUR_CLS_CODE(2),
     * 그 다음 ASKP1..ASKP10(3..12), BIDP1..BIDP10(13..22),
     * ASKP_RSQN1..10(23..32), BIDP_RSQN1..10(33..42) 로 추정.
     * 호가/잔량 블록 시작 오프셋은 라이브로 확정 필요 → 진단 로거로 대조.
     */
    private static final class KrAsking {
        static final int SYMBOL = 0;          // MKSC_SHRN_ISCD
        static final int ASKP_START = 3;      // ASKP1 시작 (MUST-VERIFY)
        static final int BIDP_START = 13;     // BIDP1 시작 (MUST-VERIFY)
        static final int ASKP_RSQN_START = 23; // ASKP_RSQN1 시작 (MUST-VERIFY)
        static final int BIDP_RSQN_START = 33; // BIDP_RSQN1 시작 (MUST-VERIFY)
        static final int LEVELS = 10;
    }

    /**
     * 미국 체결가 HDFSCNT0 필드 순서 (MUST-VERIFY).
     * 명세 순서(앞부분, 추정): RSYM(0)=실시간종목코드, SYMB(1)=종목코드, ZDIV(2)=소수점자리수,
     * TYMD/XYMD(3)=일자, XHMS(4)=시간, KYMD(5)/KHMS(6), OPEN(7), HIGH(8), LOW(9),
     * LAST(11)=현재가, SIGN(12)=대비부호, DIFF(13)=대비, RATE(14)=등락율,
     * ... EVOL(체결량)/TVOL(누적거래량) 는 가변 위치 → 라이브 확정.
     */
    private static final class UsTick {
        static final int SYMBOL = 1;        // SYMB (없으면 RSYM[0] 뒤 4자리 폴백)
        static final int RSYM = 0;          // RSYM (예: DNASAAPL)
        static final int LAST = 11;         // LAST 현재가 (MUST-VERIFY)
        static final int SIGN = 12;         // 대비부호 (MUST-VERIFY)
        static final int DIFF = 13;         // 전일대비 (MUST-VERIFY)
        static final int RATE = 14;         // 등락율 (MUST-VERIFY)
        static final int EVOL = 19;         // 체결량 (MUST-VERIFY)
        static final int TVOL = 20;         // 누적거래량 (MUST-VERIFY)
    }

    /**
     * 미국 호가 1단계 HDFSASP0 필드 순서 (MUST-VERIFY).
     * 명세 순서(앞부분, 추정): RSYM(0), SYMB(1), ZDIV(2), XYMD(3), XHMS(4),
     * BVOL(?), AVOL(?), ... BID1(?), ASK1(?), BID_RSQN1(?), ASK_RSQN1(?).
     * 1호가만 제공 → 단일 레벨. 호가/잔량 인덱스는 라이브로 확정.
     */
    private static final class UsAsking {
        static final int SYMBOL = 1;        // SYMB
        static final int RSYM = 0;
        static final int BID1 = 11;         // 매수1호가 (MUST-VERIFY)
        static final int ASK1 = 12;         // 매도1호가 (MUST-VERIFY)
        static final int BID_RSQN1 = 13;    // 매수1잔량 (MUST-VERIFY)
        static final int ASK_RSQN1 = 14;    // 매도1잔량 (MUST-VERIFY)
    }

    /**
     * 파싱 결과. 호가/체결 둘 중 하나만 채워지며, 둘 다 null 이면 무시(미지원/암호 프레임).
     *
     * @param trId   상향 tr_id
     * @param symbol 종목 식별자 (브라우저 메시지 symbol 과 fan-out 라우팅 키)
     * @param quote  호가 메시지 (체결 프레임이면 null)
     * @param tick   체결 메시지 (호가 프레임이면 null)
     */
    public record ParseResult(String trId, String symbol, QuoteMessage quote, TickMessage tick) {
    }

    /**
     * 평문 데이터 프레임을 파싱한다. PINGPONG/ACK/암호 프레임 등 비데이터는 null 반환.
     *
     * @param raw 상향에서 받은 텍스트 프레임 (이미 PINGPONG/JSON-ACK 여부는 호출부가 판별)
     */
    public ParseResult parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) == '{') {
            // JSON(ACK/PINGPONG)은 데이터 프레임이 아님 → 호출부에서 별도 처리
            return null;
        }
        // 헤더 4토큰: encryptFlag | tr_id | count | body
        // body 내부는 ^ 구분이므로 앞쪽 3개만 | 로 분리한다.
        int p1 = raw.indexOf('|');
        int p2 = (p1 >= 0) ? raw.indexOf('|', p1 + 1) : -1;
        int p3 = (p2 >= 0) ? raw.indexOf('|', p2 + 1) : -1;
        if (p1 < 0 || p2 < 0 || p3 < 0) {
            log.debug("[realtime] non-data frame ignored: {}", abbreviate(raw));
            return null;
        }
        String encryptFlag = raw.substring(0, p1);
        String trId = raw.substring(p1 + 1, p2);
        int count = parseIntSafe(raw.substring(p2 + 1, p3), 1);
        String body = raw.substring(p3 + 1);

        // Phase 1 은 평문(flag 0)만 처리. 암호(flag 1)=체결통보 Phase 2.
        if (!"0".equals(encryptFlag)) {
            log.debug("[realtime] encrypted frame (flag={}) tr_id={} ignored (Phase2)", encryptFlag, trId);
            return null;
        }

        RealtimeTr tr = RealtimeTr.fromTrId(trId);
        if (tr == null) {
            log.debug("[realtime] unknown tr_id={} ignored", trId);
            return null;
        }

        String[] f = body.split("\\^", -1);
        logDiagnostic(trId, f);

        // count>1 이면 다중 레코드지만 Phase 1 은 최신(첫) 레코드만 fan-out 한다.
        return switch (tr) {
            case KR_TICK -> new ParseResult(trId, fieldSymbol(f, KrTick.SYMBOL), null, parseKrTick(f));
            case KR_ASKING -> new ParseResult(trId, fieldSymbol(f, KrAsking.SYMBOL), parseKrAsking(f), null);
            case US_TICK -> {
                String sym = usSymbol(f, UsTick.SYMBOL, UsTick.RSYM);
                yield new ParseResult(trId, sym, null, parseUsTick(f, sym));
            }
            case US_ASKING -> {
                String sym = usSymbol(f, UsAsking.SYMBOL, UsAsking.RSYM);
                yield new ParseResult(trId, sym, parseUsAsking(f, sym), null);
            }
            // 체결통보(KR_FILL/US_FILL)는 flag 1 암호 프레임 → 이미 위 encryptFlag 가드에서 걸러진다.
            // Phase 2 FillFrameParser 가 독립 처리하므로 본 평문 파서는 무시(null). switch 완전성용.
            case KR_FILL, US_FILL -> null;
        };
    }

    // ── 국내 ──────────────────────────────────────────────────────────────

    private TickMessage parseKrTick(String[] f) {
        BigDecimal price = bd(f, KrTick.CURRENT_PRICE);
        BigDecimal change = signedChange(at(f, KrTick.SIGN), bd(f, KrTick.CHANGE));
        return TickMessage.builder()
                .market("KR")
                .symbol(fieldSymbol(f, KrTick.SYMBOL))
                .currentPrice(price)
                .changeAmount(change)
                .changeRate(bd(f, KrTick.CHANGE_RATE))
                .volume(lng(f, KrTick.VOLUME))
                .accVolume(lng(f, KrTick.ACC_VOLUME))
                .ts(System.currentTimeMillis())
                .build();
    }

    private QuoteMessage parseKrAsking(String[] f) {
        List<QuoteMessage.Level> asks = new ArrayList<>(KrAsking.LEVELS);
        List<QuoteMessage.Level> bids = new ArrayList<>(KrAsking.LEVELS);
        for (int i = 0; i < KrAsking.LEVELS; i++) {
            Long ap = lng(f, KrAsking.ASKP_START + i);
            Long aq = lng(f, KrAsking.ASKP_RSQN_START + i);
            Long bp = lng(f, KrAsking.BIDP_START + i);
            Long bq = lng(f, KrAsking.BIDP_RSQN_START + i);
            if (ap != null && ap > 0) {
                asks.add(QuoteMessage.Level.builder().price(ap).quantity(aq).build());
            }
            if (bp != null && bp > 0) {
                bids.add(QuoteMessage.Level.builder().price(bp).quantity(bq).build());
            }
        }
        return QuoteMessage.builder()
                .market("KR")
                .symbol(fieldSymbol(f, KrAsking.SYMBOL))
                .asks(asks)
                .bids(bids)
                .ts(System.currentTimeMillis())
                .build();
    }

    // ── 미국 ──────────────────────────────────────────────────────────────

    private TickMessage parseUsTick(String[] f, String symbol) {
        BigDecimal change = signedChange(at(f, UsTick.SIGN), bd(f, UsTick.DIFF));
        return TickMessage.builder()
                .market("US")
                .symbol(symbol)
                .currentPrice(bd(f, UsTick.LAST))
                .changeAmount(change)
                .changeRate(bd(f, UsTick.RATE))
                .volume(lng(f, UsTick.EVOL))
                .accVolume(lng(f, UsTick.TVOL))
                .ts(System.currentTimeMillis())
                .build();
    }

    private QuoteMessage parseUsAsking(String[] f, String symbol) {
        List<QuoteMessage.Level> asks = new ArrayList<>(1);
        List<QuoteMessage.Level> bids = new ArrayList<>(1);
        Long ask = lng(f, UsAsking.ASK1);
        Long askQ = lng(f, UsAsking.ASK_RSQN1);
        Long bid = lng(f, UsAsking.BID1);
        Long bidQ = lng(f, UsAsking.BID_RSQN1);
        if (ask != null && ask > 0) {
            asks.add(QuoteMessage.Level.builder().price(ask).quantity(askQ).build());
        }
        if (bid != null && bid > 0) {
            bids.add(QuoteMessage.Level.builder().price(bid).quantity(bidQ).build());
        }
        return QuoteMessage.builder()
                .market("US")
                .symbol(symbol)
                .asks(asks)
                .bids(bids)
                .ts(System.currentTimeMillis())
                .build();
    }

    // ── 공통 헬퍼 ───────────────────────────────────────────────────────────

    /**
     * MUST-VERIFY 진단 로거. {@code kis.realtime.frame-debug=true} 일 때 각 TR 의 첫 레코드의
     * index→value 매핑을 1회 출력해 위 인덱스 상수를 라이브 명세와 대조하도록 한다.
     */
    private void logDiagnostic(String trId, String[] f) {
        if (!frameDebug || !diagnosedTrIds.add(trId)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[realtime][frame-debug] FIRST FRAME tr_id=").append(trId)
                .append(" fields=").append(f.length).append('\n');
        for (int i = 0; i < f.length; i++) {
            sb.append("  [").append(i).append("]=").append(f[i]).append('\n');
        }
        log.info(sb.toString());
    }

    /**
     * 전일대비 부호 적용. KIS PRDY_VRSS_SIGN: 1/2 = 상승(+), 4/5 = 하락(-), 3 = 보합.
     * (MUST-VERIFY: 부호 코드 의미는 명세 기준; 미국 SIGN 도 동일 코드계로 추정.)
     */
    private BigDecimal signedChange(String sign, BigDecimal magnitude) {
        if (magnitude == null) {
            return null;
        }
        BigDecimal abs = magnitude.abs();
        if (sign != null && (sign.equals("4") || sign.equals("5"))) {
            return abs.negate();
        }
        return abs;
    }

    private String fieldSymbol(String[] f, int idx) {
        return at(f, idx);
    }

    /**
     * 미국 종목 식별자: SYMB 필드 우선, 없으면 RSYM(예 DNASAAPL)에서 거래소접두(4자) 제거 폴백.
     */
    private String usSymbol(String[] f, int symbIdx, int rsymIdx) {
        String symb = at(f, symbIdx);
        if (symb != null && !symb.isBlank()) {
            return symb.trim().toUpperCase();
        }
        String rsym = at(f, rsymIdx);
        if (rsym != null && rsym.length() > 4) {
            // <접두1><거래소3> 4자 제거 (DNAS / RNAS 등)
            return rsym.substring(4).trim().toUpperCase();
        }
        return rsym;
    }

    private String at(String[] f, int idx) {
        if (idx < 0 || idx >= f.length) {
            return null;
        }
        String v = f[idx];
        return (v == null) ? null : v.trim();
    }

    private BigDecimal bd(String[] f, int idx) {
        String v = at(f, idx);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long lng(String[] f, int idx) {
        BigDecimal v = bd(f, idx);
        return (v == null) ? null : v.longValue();
    }

    private int parseIntSafe(String v, int dflt) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private String abbreviate(String s) {
        return (s.length() > 80) ? s.substring(0, 80) + "…" : s;
    }
}
