package com.inbeom.apiserver.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 업비트 응답(느슨한 {@code Map})에서 값을 꺼내는 헬퍼.
 *
 * <p>DTO 로 역직렬화하지 않는 이유는 응답 형태가 안정적이지 않기 때문이다 — 에러의
 * {@code error.name} 은 String 과 Integer 를 오가고, 점검 중에는 JSON 이 아닐 수도 있다.
 * {@code BondResponses} 가 KIS 응답에 대해 하는 역할과 같다.
 */
final class CoinResponses {

    /** 업비트 시각은 KST 기준이다. 오프셋이 없는 문자열을 만났을 때의 폴백 존. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private CoinResponses() {
    }

    /** 배열 응답({@code Map[]})을 {@code List<Map<String,Object>>} 로. null 이면 빈 목록. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> toMaps(Object[] body) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (body == null) {
            return result;
        }
        for (Object item : body) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    static String string(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 숫자 → {@link BigDecimal}.
     *
     * <p>{@code UpbitApiClient} 가 {@code USE_BIG_DECIMAL_FOR_FLOATS} 로 파싱하므로 보통 이미
     * {@code BigDecimal} 이다. 그렇지 않은 경로(테스트 stub 등)를 위해 문자열 경유 변환을 남겨 둔다 —
     * {@code new BigDecimal(double)} 은 {@code 0.1} 을 {@code 0.1000000000000000055...} 로 만든다.
     */
    static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long epochMillis(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 업비트 {@code created_at} → {@link OffsetDateTime}.
     *
     * <p>주문 응답의 {@code created_at} 은 {@code "2026-08-29T15:42:23+09:00"} 처럼 오프셋을 달고
     * 오지만, 실제 키로 확인하지 못한 항목이라 <b>오프셋이 없는 경우도 방어</b>한다 — 그 경우
     * 업비트 시각 기준인 KST 로 해석한다. 여기서 UTC 로 가정하면 9시간 어긋난 주문 시각이 저장된다.
     *
     * @return 파싱 실패 시 현재 시각(주문 기록 자체를 잃지 않기 위해)
     */
    static OffsetDateTime offsetDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return OffsetDateTime.now(KST);
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception ignored) {
            // 오프셋 없는 형태로 왔다면 KST 로 해석한다.
        }
        try {
            return ZonedDateTime.of(
                    java.time.LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    KST).toOffsetDateTime();
        } catch (Exception ignored) {
            return OffsetDateTime.now(KST);
        }
    }
}
