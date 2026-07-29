package com.inbeom.apiserver.realtime;

import com.inbeom.apiserver.util.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

/**
 * {@code /ws/realtime} 핸드셰이크 JWT 인증 인터셉터.
 *
 * <p>브라우저 네이티브 WebSocket 은 커스텀 헤더를 못 보내므로 {@code ?token=<JWT>} 쿼리로 받는다.
 * {@link JwtTokenProvider#validateToken} 검증 실패/누락 시 false 를 반환해 핸드셰이크를 거부한다(403).
 * 검증 성공 시 username/userId 를 세션 attributes 에 담아 핸들러가 식별할 수 있게 한다.
 *
 * <p>토큰이 URL 에 노출되는 점은 MVP 범위에서 허용한다(서버 로그는 query 미기록 권장).
 * Phase 2 에서 one-time ticket 으로 대체한다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_USER_ID = "userId";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {

        String token = extractToken(request);
        if (token == null || token.isBlank() || !jwtTokenProvider.validateToken(token)) {
            log.warn("[realtime] handshake rejected: invalid or missing token");
            return false;
        }
        try {
            Claims claims = jwtTokenProvider.getAllClaimsFromToken(token);
            attributes.put(ATTR_USERNAME, claims.getSubject());
            Object userId = claims.get(JwtHandshakeInterceptor.ATTR_USER_ID);
            if (userId != null) {
                attributes.put(ATTR_USER_ID, userId);
            }
            log.debug("[realtime] handshake accepted for user={}", claims.getSubject());
            return true;
        } catch (Exception e) {
            log.warn("[realtime] handshake rejected: token claim parse failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // no-op
    }

    private String extractToken(ServerHttpRequest request) {
        List<String> tokens = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .get("token");
        if (tokens != null && !tokens.isEmpty()) {
            return tokens.get(0);
        }
        return null;
    }
}
