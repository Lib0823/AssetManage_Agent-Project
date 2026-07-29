package com.inbeom.apiserver.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 서비스-투-서비스 인증 필터.
 *
 * <p>ai-agent → api-server 내부 호출(/internal/**)은 사람 JWT 가 아니라
 * 공유 시크릿 헤더({@code X-Internal-Api-Key})로 인증한다. 시크릿이
 * 설정되지 않았거나(blank) 일치하지 않으면 401 로 차단한다(fail-closed).
 *
 * <p>/internal 이외 경로는 통과시키며, JWT/일반 보안 흐름에 전혀 관여하지 않는다.
 */
@Slf4j
@Component
public class InternalAuthFilter extends OncePerRequestFilter {

    public static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String INTERNAL_PATH_PREFIX = "/internal";

    @Value("${internal.api-key:}")
    private String internalApiKey;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // context-path(/api)는 servletPath 에서 제외되므로 /internal 로 매칭된다.
        String path = request.getServletPath();
        if (path == null || !path.startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(INTERNAL_API_KEY_HEADER);
        if (!isAuthorized(provided)) {
            log.warn("Rejected internal request to {} (missing/invalid {})", path, INTERNAL_API_KEY_HEADER);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"Unauthorized internal request\",\"data\":null}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 설정된 시크릿이 비어있지 않고, 제공된 헤더와 상수시간 비교로 일치하는지 확인.
     */
    private boolean isAuthorized(String provided) {
        if (internalApiKey == null || internalApiKey.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                internalApiKey.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
