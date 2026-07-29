package com.inbeom.apiserver.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 실시간 비활성({@code kis.realtime.enabled != true}) 시의 {@link UpstreamSubscriber} 폴백.
 *
 * <p>이때 {@link KisRealtimeUpstreamClient} 빈은 생성되지 않으므로
 * {@link SubscriptionManager} 의 {@code @Lazy UpstreamSubscriber} 의존성이 풀리지 않아 부팅이 실패한다.
 * 이를 막기 위해 동일 인터페이스를 no-op 으로 제공한다 — 구독 refcount 와 브라우저 세션 송신은
 * 정상 동작하고, 상향 등록만 생략된다(graceful degrade). 핸드셰이크/REST 경로는 무영향.
 *
 * <p>{@code matchIfMissing = true} 로 프로퍼티 미지정(기본 비활성) 시에도 본 빈이 선택된다.
 * enabled=true 면 {@link KisRealtimeUpstreamClient} 가 동일 인터페이스를 제공하고 본 빈은 비활성.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "kis.realtime.enabled", havingValue = "false", matchIfMissing = true)
public class NoopUpstreamSubscriber implements UpstreamSubscriber {

    @Override
    public void register(SubKey key) {
        log.debug("[realtime] disabled (no-op register) {}", key);
    }

    @Override
    public void unregister(SubKey key) {
        log.debug("[realtime] disabled (no-op unregister) {}", key);
    }

    @Override
    public void registerAll(Collection<SubKey> keys) {
        // no-op
    }
}
