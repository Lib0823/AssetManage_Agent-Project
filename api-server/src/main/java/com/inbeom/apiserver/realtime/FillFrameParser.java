package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.dto.realtime.FillMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * KIS 체결통보 복호 평문 → {@link FillMessage} 파서.
 *
 * <ul>
 *   <li>국내: H0STCNI0(실전) / H0STCNI9(모의) → {@link KrFill} 블록, market="KR".</li>
 *   <li>미국: H0GSCNI0(실전) / H0GSCNI9(모의) → {@link UsFill} 블록, market="US".</li>
 * </ul>
 *
 * <p>{@link KisFillFrameDecryptor} 가 복호한 평문은 {@code ^} 로 구분된 단일 레코드다(체결통보는
 * 통상 count=1). 본 파서는 그 positional field 를 {@link FillMessage} 로 변환한다. 어느 블록을
 * 쓸지는 호출부({@link UserFillsUpstreamConnection})가 데이터 프레임의 tr_id 로 판별한 {@link RealtimeTr}
 * 로 결정한다(KR_FILL → KR 블록, US_FILL → US 블록).
 *
 * <p><b>MUST-VERIFY — 필드 인덱스 / 코드값</b>. 아래 {@link KrFill}/{@link UsFill} 인덱스와 코드
 * 해석은 KIS Developers 실시간 체결통보 명세의 응답 필드 "순서"에 기반한 추정이며, 실제 체결
 * 프레임으로 확정해야 한다(주문 체결 시에만 프레임 발생 → 장중 실/모의 계좌 필요).
 * {@code kis.realtime.fills.frame-debug=true} 일 때 각 시장(KR/US)의 첫 레코드 index→value 매핑을
 * 1회 로깅하는 {@link #logDiagnostic} 진단 로거로 명세와 대조해 아래 상수를 확정한다.
 * 모든 인덱스/코드 상수는 이 클래스 한 곳에만 둔다.
 */
@Slf4j
@Component
public class FillFrameParser {

    // ───────────────────────────────────────────────────────────────────────
    // MUST-VERIFY 인덱스 (출처: KIS 국내주식 실시간 체결통보 H0STCNI0 응답 필드 순서)
    //
    // 명세 앞부분(알려진 순서):
    //  [0] CUST_ID            고객 ID
    //  [1] ACNT_NO           계좌번호
    //  [2] ODER_NO           주문번호
    //  [3] OODER_NO          원주문번호
    //  [4] SELN_BYOV_CLS     매도매수구분 (01=매도, 02=매수)   ← side 결정 (MUST-VERIFY)
    //  [5] RCTF_CLS          정정구분
    //  [6] ODER_KIND         주문종류
    //  [7] ODER_COND         주문조건
    //  [8] STCK_SHRN_ISCD    유가증권단축종목코드               ← symbol (MUST-VERIFY)
    //  [9] CNTG_QTY          체결수량                          ← qty (MUST-VERIFY)
    //  [10] CNTG_UNPR        체결단가                          ← price (MUST-VERIFY)
    //  [11] STCK_CNTG_HOUR   주식체결시간(HHMMSS)              ← filledAt (MUST-VERIFY)
    //  [12] CNTG_YN          체결여부 (1=주문/정정/취소접수, 2=체결) ← isFill (MUST-VERIFY)
    //  [13] ACPT_YN          접수여부
    //  ...
    // 위 순서는 추정 — frame-debug 로 첫 실제 프레임을 캡처해 확정한다.
    // ───────────────────────────────────────────────────────────────────────
    private static final class KrFill {
        static final int ORDER_NO = 2;          // ODER_NO
        static final int SELN_BYOV_CLS = 4;     // 매도매수구분 (MUST-VERIFY)
        static final int SYMBOL = 8;            // STCK_SHRN_ISCD (MUST-VERIFY)
        static final int CNTG_QTY = 9;          // 체결수량 (MUST-VERIFY)
        static final int CNTG_UNPR = 10;        // 체결단가 (MUST-VERIFY)
        static final int CNTG_HOUR = 11;        // 체결시간 HHMMSS (MUST-VERIFY)
        static final int CNTG_YN = 12;          // 체결여부 1/2 (MUST-VERIFY)
    }

    /** MUST-VERIFY: CNTG_YN 값. "2" = 실체결, "1" = 주문/정정/취소 접수통보. */
    private static final String CNTG_YN_FILLED = "2";

    /** MUST-VERIFY: SELN_BYOV_CLS 값. "01" = 매도, "02" = 매수. (자리수 0 패딩 가능 → 우측 비교) */
    private static final String SELN_CLS_SELL = "01";
    private static final String SELN_CLS_BUY = "02";

    // ───────────────────────────────────────────────────────────────────────
    // MUST-VERIFY 인덱스 (출처: KIS 해외주식 실시간 체결통보 H0GSCNI0 응답 필드 순서)
    //
    // 해외 체결통보(H0GSCNI0)는 국내(H0STCNI0)와 동일한 계좌-단위 체결통보지만 필드 구성이 다르다.
    // 명세 앞부분(알려진 순서, 추정):
    //  [0] CUST_ID            고객 ID
    //  [1] ACNT_NO            계좌번호
    //  [2] ODER_NO            주문번호                          ← orderNo (MUST-VERIFY)
    //  [3] OODER_NO           원주문번호
    //  [4] SELN_BYOV_CLS      매도매수구분 (01=매도, 02=매수)   ← side (MUST-VERIFY)
    //  [5] RCTF_CLS           정정구분
    //  [6] ODER_KIND          주문종류
    //  [7] (예약/구분 필드)
    //  [8] STCK_SHRN_ISCD     해외 종목코드(예: AAPL)            ← symbol (MUST-VERIFY)
    //  [9] CNTG_QTY           체결수량                          ← qty (MUST-VERIFY)
    //  [10] CNTG_UNPR         체결단가(USD, 소수점)              ← price (MUST-VERIFY)
    //  [11] STCK_CNTG_HOUR    체결시간(HHMMSS, 현지/한국시각)    ← filledAt (MUST-VERIFY)
    //  [12] CNTG_YN           체결여부 (1=접수, 2=체결)          ← isFill (MUST-VERIFY)
    //  ...
    // 해외는 종목코드가 영문 티커이고 단가가 소수점(USD)이라는 점이 KR 과 다르다. 위 순서/코드는
    // 추정 — frame-debug 로 US 첫 실제 프레임을 캡처해 확정한다(KrFill 과 인덱스가 다를 수 있음).
    // ───────────────────────────────────────────────────────────────────────
    private static final class UsFill {
        static final int ORDER_NO = 2;          // ODER_NO (MUST-VERIFY)
        static final int SELN_BYOV_CLS = 4;     // 매도매수구분 (MUST-VERIFY)
        static final int SYMBOL = 8;            // 해외 종목코드 ticker (MUST-VERIFY)
        static final int CNTG_QTY = 9;          // 체결수량 (MUST-VERIFY)
        static final int CNTG_UNPR = 10;        // 체결단가 USD (MUST-VERIFY)
        static final int CNTG_HOUR = 11;        // 체결시간 HHMMSS (MUST-VERIFY)
        static final int CNTG_YN = 12;          // 체결여부 1/2 (MUST-VERIFY)
    }

    private final boolean frameDebug;
    private volatile boolean diagnosedKr = false;
    private volatile boolean diagnosedUs = false;

    public FillFrameParser(
            @Value("${kis.realtime.fills.frame-debug:false}") boolean frameDebug) {
        this.frameDebug = frameDebug;
    }

    /**
     * 복호된 체결통보 평문 1레코드를 {@link FillMessage} 로 변환한다 (국내 기본).
     *
     * <p>tr 정보가 없는 호출(레거시)은 국내(KR_FILL)로 간주한다. 시장 분기가 필요한 경우
     * {@link #parse(String, RealtimeTr)} 를 사용한다.
     *
     * @param decrypted {@code ^}-구분 평문 (KisFillFrameDecryptor 복호 결과)
     * @return FillMessage, 파싱 불가/필드 부족 시 null
     */
    public FillMessage parse(String decrypted) {
        return parse(decrypted, RealtimeTr.KR_FILL);
    }

    /**
     * 복호된 체결통보 평문 1레코드를 시장별 필드 블록으로 {@link FillMessage} 로 변환한다.
     *
     * @param decrypted {@code ^}-구분 평문 (KisFillFrameDecryptor 복호 결과)
     * @param tr        프레임 tr_id 로 판별된 체결통보 TR (KR_FILL / US_FILL). null → KR 폴백.
     * @return FillMessage, 파싱 불가/필드 부족 시 null
     */
    public FillMessage parse(String decrypted, RealtimeTr tr) {
        if (decrypted == null || decrypted.isBlank()) {
            return null;
        }
        String[] f = decrypted.split("\\^", -1);
        boolean us = (tr != null && tr.market() == RealtimeTr.Market.US);
        logDiagnostic(f, us);
        return us ? parseUsFill(f) : parseKrFill(f);
    }

    private FillMessage parseKrFill(String[] f) {
        String symbol = at(f, KrFill.SYMBOL);
        if (symbol == null || symbol.isBlank()) {
            log.debug("[fills] KR fill frame missing symbol; fields={}", f.length);
            return null;
        }
        boolean isFill = CNTG_YN_FILLED.equals(at(f, KrFill.CNTG_YN));
        return FillMessage.builder()
                .market("KR")
                .symbol(symbol.trim())
                .side(resolveSide(at(f, KrFill.SELN_BYOV_CLS)))
                .orderNo(trimOrNull(at(f, KrFill.ORDER_NO)))
                .qty(lng(f, KrFill.CNTG_QTY))
                .price(bd(f, KrFill.CNTG_UNPR))
                .filledAt(trimOrNull(at(f, KrFill.CNTG_HOUR)))
                .isFill(isFill)
                .ts(System.currentTimeMillis())
                .build();
    }

    private FillMessage parseUsFill(String[] f) {
        String symbol = at(f, UsFill.SYMBOL);
        if (symbol == null || symbol.isBlank()) {
            log.debug("[fills] US fill frame missing symbol; fields={}", f.length);
            return null;
        }
        boolean isFill = CNTG_YN_FILLED.equals(at(f, UsFill.CNTG_YN));
        return FillMessage.builder()
                .market("US")
                .symbol(symbol.trim())
                .side(resolveSide(at(f, UsFill.SELN_BYOV_CLS)))
                .orderNo(trimOrNull(at(f, UsFill.ORDER_NO)))
                .qty(lng(f, UsFill.CNTG_QTY))
                .price(bd(f, UsFill.CNTG_UNPR))
                .filledAt(trimOrNull(at(f, UsFill.CNTG_HOUR)))
                .isFill(isFill)
                .ts(System.currentTimeMillis())
                .build();
    }

    /**
     * SELN_BYOV_CLS → side. "02"=매수(buy), "01"=매도(sell). (MUST-VERIFY: 코드 의미)
     * 알 수 없으면 null(직렬화 제외).
     */
    private String resolveSide(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        if (SELN_CLS_BUY.equals(c)) {
            return "buy";
        }
        if (SELN_CLS_SELL.equals(c)) {
            return "sell";
        }
        return null;
    }

    /**
     * MUST-VERIFY 진단 로거. {@code kis.realtime.fills.frame-debug=true} 일 때 각 시장(KR/US)의
     * 첫 복호 레코드 index→value 매핑을 1회씩 출력해 위 인덱스/코드 상수를 명세·라이브와 대조하게
     * 한다. (값 자체에 키는 없으나 계좌/주문 정보 포함 → 운영에선 false 권장.)
     *
     * @param us US_FILL 프레임이면 true (KR/US 각각 1회 진단)
     */
    private void logDiagnostic(String[] f, boolean us) {
        if (!frameDebug) {
            return;
        }
        if (us) {
            if (diagnosedUs) {
                return;
            }
            diagnosedUs = true;
        } else {
            if (diagnosedKr) {
                return;
            }
            diagnosedKr = true;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[fills][frame-debug] FIRST DECRYPTED ")
                .append(us ? "US" : "KR")
                .append(" FILL FRAME fields=")
                .append(f.length).append('\n');
        for (int i = 0; i < f.length; i++) {
            sb.append("  [").append(i).append("]=").append(f[i]).append('\n');
        }
        log.info(sb.toString());
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private String at(String[] f, int idx) {
        if (idx < 0 || idx >= f.length) {
            return null;
        }
        String v = f[idx];
        return (v == null) ? null : v.trim();
    }

    private String trimOrNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
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
}
