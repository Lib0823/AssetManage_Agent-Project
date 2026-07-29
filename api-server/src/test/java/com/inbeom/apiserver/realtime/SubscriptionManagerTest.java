package com.inbeom.apiserver.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link SubscriptionManager} refcount/멀티플렉싱 단위 테스트 (오프라인 검증 가능 항목).
 *
 * <p>0→1 register, 1→0 unregister 전이에서만 상향 호출이 일어나는지,
 * 세션 종료 시 일괄 해제가 되는지, 구독 상한이 동작하는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionManager refcount 단위 테스트")
class SubscriptionManagerTest {

    @Mock
    private UpstreamSubscriber upstream;

    private SubscriptionManager manager;

    private final SubKey samsungTick =
            new SubKey(RealtimeTr.KR_TICK, "005930", "005930");

    @BeforeEach
    void setUp() {
        manager = new SubscriptionManager(upstream, new ObjectMapper(), 2);
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        lenient().when(s.getId()).thenReturn(id);
        lenient().when(s.isOpen()).thenReturn(true);
        return s;
    }

    @Test
    @DisplayName("0→1 첫 구독 시 한 번만 상향 register")
    void firstSubscriberRegistersUpstreamOnce() {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");

        manager.subscribe(s1, samsungTick);
        manager.subscribe(s2, samsungTick); // 같은 SubKey 두 번째 구독자

        verify(upstream, times(1)).register(samsungTick);
        verify(upstream, never()).unregister(any());
    }

    @Test
    @DisplayName("1→0 마지막 구독자 이탈 시에만 상향 unregister")
    void lastSubscriberUnregistersUpstreamOnce() {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");
        manager.subscribe(s1, samsungTick);
        manager.subscribe(s2, samsungTick);

        manager.unsubscribe(s1, samsungTick); // 아직 s2 남음
        verify(upstream, never()).unregister(samsungTick);

        manager.unsubscribe(s2, samsungTick); // 마지막
        verify(upstream, times(1)).unregister(samsungTick);
    }

    @Test
    @DisplayName("세션 종료 시 해당 세션의 모든 구독 일괄 해제")
    void removeSessionReleasesAllKeys() {
        WebSocketSession s1 = openSession("s1");
        SubKey appleTick = new SubKey(RealtimeTr.US_TICK, "AAPL", "RNASAAPL");
        manager.subscribe(s1, samsungTick);
        manager.subscribe(s1, appleTick);

        manager.removeSession(s1);

        verify(upstream, times(1)).unregister(samsungTick);
        verify(upstream, times(1)).unregister(appleTick);
        assertThat(manager.activeSubKeys()).isEmpty();
    }

    @Test
    @DisplayName("구독 상한 초과 시 신규 SubKey 거부")
    void subscriptionCapRejectsNewKeys() {
        WebSocketSession s1 = openSession("s1");
        boolean a = manager.subscribe(s1, new SubKey(RealtimeTr.KR_TICK, "A", "A"));
        boolean b = manager.subscribe(s1, new SubKey(RealtimeTr.KR_TICK, "B", "B"));
        boolean c = manager.subscribe(s1, new SubKey(RealtimeTr.KR_TICK, "C", "C")); // 상한(2) 초과

        assertThat(a).isTrue();
        assertThat(b).isTrue();
        assertThat(c).isFalse();
        assertThat(manager.activeSubKeys()).hasSize(2);
    }
}
