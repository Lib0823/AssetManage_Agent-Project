package com.inbeom.apiserver.client;

import com.inbeom.apiserver.config.KisResilienceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * KIS 앱키 단위 토큰 버킷 — Redis 기반.
 *
 * <h2>왜 앱키 단위인가</h2>
 * KIS 의 호출 한도는 <b>앱키(appkey)</b>에 걸린다. 이 서버는 성격이 다른 두 종류의 앱키를 쓴다:
 * <ul>
 *   <li><b>공유 시세 앱키</b>({@code kis.quote-app-key}) — 종목 상세/검색/관심종목 화면이
 *       모든 사용자를 대신해 쓴다. 사용자가 늘수록 가장 먼저 한도에 걸리는 지점이다.</li>
 *   <li><b>사용자별 매매 앱키</b>({@code user_kis_accounts}) — 사용자마다 다른 키다.</li>
 * </ul>
 * 이 둘은 실제로 서로 다른 앱키이므로 버킷도 독립이어야 한다. 한 사용자의 매매가 시세 조회를
 * 굶기거나, 시세 폭주가 매매 주문을 막아서는 안 된다.
 *
 * <p>그래서 버킷 키를 호출부에서 받지 않고 <b>appKey 에서 유도</b>한다. {@code callKisApi} 는
 * 이미 appKey 를 인자로 받으므로 15곳이 넘는 호출부를 건드리지 않고도 정확히 KIS 가 세는 단위와
 * 같은 경계가 생긴다. 사용자 id 로 키를 만들면 같은 사용자가 같은 앱키를 여러 경로로 쓰는지,
 * 공유 키가 어느 버킷에 속하는지를 호출부마다 다시 판단해야 한다.
 *
 * <p>appKey 는 자격증명이므로 키에 원문을 넣지 않는다 — SHA-256 앞 16자리만 쓴다.
 *
 * <h2>Redis 장애 시 fail-open</h2>
 * Redis 가 죽으면 <b>허용</b>한다. rate limit 은 KIS 를 보호하기 위한 장치이지 이 서비스의
 * 필수 경로가 아니다. fail-close 하면 Redis 장애가 곧 전체 매매·조회 중단이 되어, 막으려던
 * 문제보다 큰 사고가 된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kis.resilience.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class KisRateLimiter {

    /**
     * 토큰 버킷 소비. HGET/HSET 사이에 다른 인스턴스가 끼어들면 한도가 새므로 Lua 로 원자 실행한다.
     *
     * <p>현재 시각을 Redis 의 {@code TIME} 이 아니라 인자로 받는 이유: 스크립트를 결정적으로 유지해
     * 테스트에서 시간 경과(리필)를 실제 대기 없이도 검증할 수 있게 하기 위함이다. 대가로 인스턴스 간
     * 시계 오차만큼 리필이 어긋날 수 있으나, 초 단위 한도에서 NTP 동기화 수준의 오차는 무시할 만하다.
     */
    private static final RedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local refillPerSecond = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil or ts == nil then
                tokens = capacity
                ts = now
            end

            local elapsed = now - ts
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + (elapsed * refillPerSecond / 1000.0))

            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            -- 유휴 버킷을 영구 보관하지 않는다. 가득 차는 데 걸리는 시간 + 여유만큼만 남긴다
            -- (그 시점엔 어차피 capacity 로 복원되므로 삭제돼도 동작이 같다).
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refillPerSecond * 1000) + 10000)
            return allowed
            """, Long.class);

    private static final String KEY_PREFIX = "ratelimit:kis:appkey:";

    private final StringRedisTemplate redis;
    private final KisResilienceProperties.RateLimit config;

    public KisRateLimiter(StringRedisTemplate redis, KisResilienceProperties properties) {
        this.redis = redis;
        this.config = properties.getRateLimit();
    }

    /**
     * 이 앱키의 버킷에서 토큰 1개를 소비한다.
     *
     * @return 호출을 진행해도 되면 true, 한도 초과로 거부해야 하면 false
     */
    public boolean tryAcquire(String appKey) {
        return tryAcquire(KEY_PREFIX + bucketIdOf(appKey),
                config.getCapacity(), config.getRefillPerSecond());
    }

    /**
     * 임의 버킷에서 토큰 1개를 소비한다 — KIS 외 외부 API 가 같은 Lua 토큰 버킷을 재사용하기 위한 입구다.
     *
     * <p>업비트 연동이 이 오버로드를 쓴다. 업비트의 한도 구조가 KIS 와 다른 게 아니라
     * <b>정확히 같은 모양</b>이기 때문이다 — 공유 시세 버킷(업비트는 IP 단위, KIS 는 공유 앱키)과
     * 사용자별 매매 버킷(업비트는 Pocket, KIS 는 사용자 앱키)이 독립이어야 하고, Redis 장애 시
     * fail-open 해야 한다. 클래스를 하나 더 만들면 같은 Lua 스크립트와 같은 fail-open 판단이
     * 두 벌로 갈라져, 한쪽만 고쳐지는 사고가 생긴다.
     *
     * <p>버킷 키를 통째로 받는 이유: KIS 는 "앱키 하나 = 버킷 하나"지만 업비트 시세는
     * 그룹(ticker/candle/…)별로 따로 세므로 키 조립 규칙이 호출부마다 다르다.
     *
     * @param bucketKey       완성된 Redis 키. 자격증명 원문을 담지 말 것 — {@link #hashCredential} 참고
     * @param capacity        버킷 최대 용량(= 순간 버스트 허용량)
     * @param refillPerSecond 초당 충전 토큰 수(= 지속 호출률 상한)
     */
    public boolean tryAcquire(String bucketKey, int capacity, double refillPerSecond) {
        try {
            Long allowed = redis.execute(
                    TOKEN_BUCKET,
                    List.of(bucketKey),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond),
                    String.valueOf(System.currentTimeMillis()));
            if (allowed != null && allowed == 0L) {
                log.warn("Rate limit exceeded for bucket={} (capacity={}, refill={}/s)",
                        bucketKey, capacity, refillPerSecond);
                return false;
            }
            return true;
        } catch (Exception e) {
            // fail-open: Redis 장애가 외부 API 접근 자체를 끊어서는 안 된다.
            log.warn("Rate limiter unavailable, allowing call through: {}", e.getMessage());
            return true;
        }
    }

    /**
     * appKey → 버킷 식별자. 원문을 Redis 키에 노출하지 않기 위한 SHA-256 앞 16자리.
     * (충돌 저항이 목적이 아니라 식별이 목적이므로 16자리로 충분하다.)
     */
    private String bucketIdOf(String appKey) {
        return hashCredential(appKey);
    }

    /**
     * 자격증명 → Redis 키에 넣어도 되는 식별자. SHA-256 앞 16자리.
     *
     * <p>{@code public static} 인 이유는 업비트 주문 버킷도 access_key 를 같은 방식으로 가려야 하기
     * 때문이다. 해싱 규칙이 두 벌이 되면 같은 키가 서로 다른 버킷으로 갈라진다.
     */
    public static String hashCredential(String credential) {
        if (credential == null || credential.isBlank()) {
            return "unknown";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
