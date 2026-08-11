package com.inbeom.apiserver.client;

import com.inbeom.apiserver.config.KisResilienceProperties.CachePolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * KIS 시세/재무 응답 캐시 — 신선도(TTL)와 <b>stale-if-error</b> 보관을 하나의 엔트리로 다룬다.
 *
 * <h2>엔트리가 하나인 이유</h2>
 * "신선 캐시"와 "장애용 마지막 성공값"을 별도 키로 두면 두 번 쓰고 두 번 읽어야 하고, 둘의 만료가
 * 어긋나면 신선 캐시는 없는데 폴백도 없는 구간이 생긴다. 그래서 값과 <b>저장 시각</b>을 한 엔트리에
 * 담고, Redis TTL 은 긴 쪽(stale 보관 기간)으로 잡는다. 신선도는 저장 시각으로 그때그때 계산한다.
 * 결과적으로 엔트리 하나가 "TTL 안이면 그냥 쓰고, 지났어도 KIS 가 죽으면 폴백"을 모두 만족한다.
 *
 * <p>캐시는 성공 응답만 담는다({@code rt_cd == 0} 판정은 {@link KisApiClient} 가 한다) — 오류
 * 응답을 캐시하면 30초 동안 같은 오류를 되돌려주고, stale 폴백도 오류로 오염된다.
 *
 * <p>Redis 장애 시에는 캐시가 없는 것처럼 동작한다(조회 miss / 저장 무시). {@link KisRateLimiter}
 * 의 fail-open 과 같은 이유로, 캐시 계층 장애가 원본 조회 자체를 막아서는 안 된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kis.resilience.cache.enabled", havingValue = "true", matchIfMissing = true)
public class KisResponseCache {

    private static final String KEY_PREFIX = "cache:kis:";
    private static final String FIELD_SAVED_AT = "savedAt";
    private static final String FIELD_BODY = "body";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public KisResponseCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 캐시 조회.
     *
     * @return 엔트리가 없거나 읽을 수 없으면 null
     */
    public <T> Entry<T> find(String cacheKey, Class<T> type, CachePolicy policy) {
        String raw;
        try {
            raw = redis.opsForValue().get(KEY_PREFIX + cacheKey);
        } catch (Exception e) {
            log.warn("KIS response cache unavailable (read): {}", e.getMessage());
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            Stored stored = objectMapper.readValue(raw, Stored.class);
            T body = objectMapper.readValue(stored.body(), type);
            boolean fresh = Duration.ofMillis(System.currentTimeMillis() - stored.savedAt())
                    .compareTo(policy.freshTtl()) < 0;
            return new Entry<>(body, fresh);
        } catch (Exception e) {
            // 포맷이 바뀐 낡은 엔트리 등 — 캐시 미스로 취급해 정상 경로로 되돌린다.
            log.warn("Discarding unreadable KIS cache entry key={}: {}", cacheKey, e.getMessage());
            return null;
        }
    }

    /** 성공 응답 저장. Redis TTL 은 stale 보관 기간이며, 신선도는 저장 시각으로 판정한다. */
    public void put(String cacheKey, Object body, CachePolicy policy) {
        try {
            String payload = objectMapper.writeValueAsString(
                    new Stored(System.currentTimeMillis(), objectMapper.writeValueAsString(body)));
            redis.opsForValue().set(KEY_PREFIX + cacheKey, payload, policy.staleGrace());
        } catch (Exception e) {
            log.warn("KIS response cache unavailable (write) key={}: {}", cacheKey, e.getMessage());
        }
    }

    /**
     * 캐시 키. 같은 종목이라도 데이터 종류가 다르면 달라야 하므로 TR_ID 와 요청 URL(종목코드가
     * 쿼리스트링에 들어 있다)을 함께 해싱한다. 도메인(모의/실전)도 응답이 다르므로 포함한다.
     */
    public String keyOf(String baseUrl, String endpointWithQuery, String trId) {
        return trId + ":" + sha256Short(baseUrl + "|" + endpointWithQuery);
    }

    private String sha256Short(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * @param body  캐시된 응답 본문
     * @param fresh 신선 TTL 안이면 true. false 면 stale 폴백으로만 쓸 수 있다.
     */
    public record Entry<T>(T body, boolean fresh) {}

    /** Redis 에 저장되는 형태. body 를 문자열로 이중 직렬화해 응답 타입에 관계없이 복원할 수 있게 한다. */
    private record Stored(long savedAt, String body) {}
}
