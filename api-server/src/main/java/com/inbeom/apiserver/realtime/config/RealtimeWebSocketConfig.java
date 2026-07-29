package com.inbeom.apiserver.realtime.config;

import com.inbeom.apiserver.realtime.JwtHandshakeInterceptor;
import com.inbeom.apiserver.realtime.RealtimeWebSocketHandler;
import com.inbeom.apiserver.realtime.SubscriptionManager;
import com.inbeom.apiserver.realtime.UserRealtimeConnectionRegistry;
import com.inbeom.apiserver.service.KisQuoteService;
import com.inbeom.apiserver.util.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * 실시간 브리지 WebSocket 등록 ({@code /ws/realtime}).
 *
 * <p>{@code @ConditionalOnProperty(kis.realtime.enabled=true)} 로, 비활성 시 엔드포인트 자체가
 * 등록되지 않는다(부팅 무영향, REST 경로 그대로). 핸드셰이크는 {@link JwtHandshakeInterceptor}
 * 가 {@code ?token=} JWT 로 인증한다.
 *
 * <p>{@code setAllowedOrigins} 는 {@code WebConfig} 의 CORS origin 목록을 미러한다
 * (dev LAN/localhost 포함). 핸드셰이크는 HTTP Origin 헤더 기반으로 검사된다.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kis.realtime.enabled", havingValue = "true")
public class RealtimeWebSocketConfig implements WebSocketConfigurer {

    private final SubscriptionManager subscriptionManager;
    private final KisQuoteService kisQuoteService;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;
    /** 체결통보 레지스트리(fills 비활성 시 빈 미생성 → ObjectProvider 로 optional 주입). */
    private final ObjectProvider<UserRealtimeConnectionRegistry> fillsRegistryProvider;

    /** WebConfig CORS allowedOriginPatterns 미러 (dev LAN 패턴 포함). */
    private static final String[] ALLOWED_ORIGIN_PATTERNS = {
            "http://localhost:5173",
            "http://localhost:5174",
            "http://localhost:3000",
            "http://192.168.*.*:5173",
            "http://10.*.*.*:5173",
            "http://172.16.*.*:5173"
    };

    @Bean
    public RealtimeWebSocketHandler realtimeWebSocketHandler() {
        return new RealtimeWebSocketHandler(
                subscriptionManager, kisQuoteService, objectMapper,
                fillsRegistryProvider.getIfAvailable());
    }

    @Bean
    public JwtHandshakeInterceptor jwtHandshakeInterceptor() {
        return new JwtHandshakeInterceptor(jwtTokenProvider);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeWebSocketHandler(), "/ws/realtime")
                .addInterceptors(jwtHandshakeInterceptor())
                .setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
    }
}
