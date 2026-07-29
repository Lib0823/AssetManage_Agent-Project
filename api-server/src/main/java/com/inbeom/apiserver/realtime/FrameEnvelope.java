package com.inbeom.apiserver.realtime;

/**
 * KIS 실시간 상향 <b>데이터</b> 프레임의 공통 봉투(envelope) 분리 헬퍼.
 *
 * <p>데이터 프레임 형식: {@code <flag>|<tr_id>|<count>|<data>}
 * (flag 0 = 평문, flag 1 = AES 암호 = 체결통보). data 내부는 {@code ^} 구분이라 앞쪽 3개의
 * {@code |} 만 분리하고 나머지는 통째로 data 로 둔다.
 *
 * <p>{@link KisFrameParser}(Phase 1, 평문 시세)와 동일한 분해 규칙을 쓰지만, 체결통보 경로
 * ({@link FillFrameParser})가 KisFrameParser 를 건드리지 않고 독립적으로 봉투를 열 수 있도록
 * 별도 정적 헬퍼로 둔다(중복 허용, Phase 1 보존). 본 헬퍼는 봉투만 분리하며, flag 1 의 data
 * (base64 ciphertext)는 호출부가 복호한다.
 */
public final class FrameEnvelope {

    /** flag 0 = 평문 프레임. */
    public static final String FLAG_PLAIN = "0";
    /** flag 1 = AES 암호 프레임 (체결통보). */
    public static final String FLAG_ENCRYPTED = "1";

    private FrameEnvelope() {
    }

    /**
     * 봉투 분리 결과.
     *
     * @param flag  암호화 플래그 ("0" 평문 / "1" 암호)
     * @param trId  상향 tr_id (예: H0STCNI0 / H0STCNI9)
     * @param count 레코드 수 (체결통보는 통상 1)
     * @param data  본문 (flag 1 이면 base64 ciphertext, flag 0 이면 ^-구분 평문)
     */
    public record Parsed(String flag, String trId, int count, String data) {
        public boolean isEncrypted() {
            return FLAG_ENCRYPTED.equals(flag);
        }
    }

    /**
     * {@code <flag>|<tr_id>|<count>|<data>} 프레임을 분리한다.
     * JSON(ACK/PINGPONG)이거나 {@code |} 가 3개 미만이면 null.
     */
    public static Parsed split(String raw) {
        if (raw == null || raw.isEmpty() || raw.charAt(0) == '{') {
            return null;
        }
        int p1 = raw.indexOf('|');
        int p2 = (p1 >= 0) ? raw.indexOf('|', p1 + 1) : -1;
        int p3 = (p2 >= 0) ? raw.indexOf('|', p2 + 1) : -1;
        if (p1 < 0 || p2 < 0 || p3 < 0) {
            return null;
        }
        String flag = raw.substring(0, p1);
        String trId = raw.substring(p1 + 1, p2);
        int count = parseIntSafe(raw.substring(p2 + 1, p3));
        String data = raw.substring(p3 + 1);
        return new Parsed(flag, trId, count, data);
    }

    private static int parseIntSafe(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 1;
        }
    }
}
