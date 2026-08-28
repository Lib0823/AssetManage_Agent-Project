package com.inbeom.apiserver.service;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 채권 KIS 응답 파싱 공용 헬퍼 ({@link BondQuoteService} · {@link BondTradingService} 공유).
 *
 * <p>KIS 채권 응답은 <b>모든 값이 문자열</b>로 온다({@code NUMERIC_COLUMNS=[]}). 숫자로 쓰려면
 * 반드시 파싱이 필요하고, 단가는 소수를 가지므로 파싱 결과는 예외 없이 {@link BigDecimal} 이다 —
 * {@code double} 을 거치면 그 시점에 정밀도가 사라져 되돌릴 수 없다.
 *
 * <p>값이 없을 때 <b>null 을 돌려주는 것</b>이 기본이다. 시세는 "0원"과 "값 없음"이 전혀 다른
 * 의미이고, 0 으로 뭉개면 화면이 없는 가격을 0 으로 표시한다. 합계처럼 0 이 맞는 자리는
 * 호출부가 명시적으로 기본값을 준다.
 */
@Slf4j
final class BondResponses {

    private BondResponses() {
    }

    static boolean isRtOk(Map<String, Object> body) {
        return body != null && "0".equals(String.valueOf(body.get("rt_cd")));
    }

    /** KIS 실패 응답의 사유 메시지 (없으면 null). */
    static String message(Map<String, Object> body) {
        return body == null ? null : asString(body.get("msg1"));
    }

    /**
     * {@code output} 을 단건 맵으로 읽는다.
     *
     * <p>KIS 예제는 output 을 항상 list 로 정규화해 두어 단건 API 가 객체로 오는지 배열로 오는지
     * 예제만으로는 단정할 수 없다(실계좌 미검증). 양쪽을 모두 견디게 해 둔다.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> firstMap(Object output) {
        if (output instanceof Map) {
            return (Map<String, Object>) output;
        }
        if (output instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
            return (Map<String, Object>) list.get(0);
        }
        return null;
    }

    /** {@code output} 을 다건 목록으로 읽는다. 단건 객체로 와도 1건짜리 목록으로 정규화한다. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asMapList(Object output) {
        if (output instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(row -> (Map<String, Object>) row)
                    .toList();
        }
        if (output instanceof Map) {
            return List.of((Map<String, Object>) output);
        }
        return List.of();
    }

    /** 빈 문자열·공백은 null 로 정규화. KIS 는 "값 없음"을 빈 문자열로 보낸다. */
    static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    static String string(Map<String, Object> item, String key) {
        return item == null ? null : asString(item.get(key));
    }

    /**
     * 후보 키를 순서대로 조회해 첫 비어있지 않은 값을 반환.
     * 응답 필드명이 실계좌 미검증인 TR(예: CTSC8013R 체결조회)에서 쓴다.
     */
    static String firstNonNull(Map<String, Object> item, String... keys) {
        if (item == null) {
            return null;
        }
        for (String key : keys) {
            String v = asString(item.get(key));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 값이 없거나 숫자가 아니면 null. 파싱 실패를 0 으로 위장하지 않는다. */
    static BigDecimal decimal(Map<String, Object> item, String key) {
        return toDecimal(string(item, key));
    }

    static BigDecimal toDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            log.warn("채권 응답의 숫자 파싱 실패: {}", raw);
            return null;
        }
    }

    /** 합계 누적처럼 "없으면 0"이 옳은 자리에서만 쓴다. */
    static BigDecimal decimalOrZero(Map<String, Object> item, String key) {
        BigDecimal value = decimal(item, key);
        return value == null ? BigDecimal.ZERO : value;
    }
}
