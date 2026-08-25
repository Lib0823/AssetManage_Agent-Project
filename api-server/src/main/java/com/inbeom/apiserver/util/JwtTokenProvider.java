package com.inbeom.apiserver.util;

import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 토큰 용도 구분 클레임. access/refresh 가 같은 키로 서명되므로 이 클레임이 없으면
     * 리프레시 토큰으로도 보호 리소스에 접근할 수 있다(로그아웃이 세션을 끊지 못한다).
     */
    private static final String TOKEN_TYPE_CLAIM = "type";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final SecretKey secretKey;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET 환경변수가 설정되지 않았습니다. 32바이트(256bit) 이상의 무작위 값을 주입하세요.");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    /**
     * Generate Access Token with userId and kisAccountId
     */
    public String generateAccessToken(String username, Long userId, Long kisAccountId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpiration);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("kisAccountId", kisAccountId);
        claims.put(TOKEN_TYPE_CLAIM, TOKEN_TYPE_ACCESS);

        return Jwts.builder()
                .subject(username)
                .claims(claims)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Generate Refresh Token
     */
    public String generateRefreshToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpiration);

        return Jwts.builder()
                .subject(username)
                .claim(TOKEN_TYPE_CLAIM, TOKEN_TYPE_REFRESH)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Get username from token
     */
    public String getUsernameFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.getSubject();
    }

    /**
     * {@code Authorization} 헤더에서 Bearer 토큰만 떼어낸다.
     *
     * <p>컨트롤러가 {@code authHeader.substring(7)} 을 직접 호출하면 헤더가 "Bearer " 로 시작하지
     * 않을 때 {@link StringIndexOutOfBoundsException} 이 나 401 대신 500 으로 응답한다.
     * 형식 위반은 인증 실패이므로 {@link ErrorCode#INVALID_TOKEN}(2002, 401)로 통일한다.
     */
    public String resolveBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN,
                    "Authorization 헤더는 'Bearer {token}' 형식이어야 합니다");
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN,
                    "Authorization 헤더에 토큰이 없습니다");
        }
        return token;
    }

    /**
     * Get userId from token
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.get("userId", Long.class);
    }

    /**
     * Get kisAccountId from token
     */
    public Long getKisAccountIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claims.get("kisAccountId", Long.class);
    }

    /**
     * Get all claims from token
     */
    public Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Validate JWT token
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            log.error("Invalid JWT signature: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            log.error("Expired JWT token: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.error("Unsupported JWT token: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("JWT claims string is empty: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 액세스 토큰 전용 검증. 서명·만료에 더해 {@code type=access} 인지 확인한다.
     */
    public boolean validateAccessToken(String token) {
        return validateTokenOfType(token, TOKEN_TYPE_ACCESS);
    }

    /**
     * 리프레시 토큰 전용 검증. 서명·만료에 더해 {@code type=refresh} 인지 확인한다.
     */
    public boolean validateRefreshToken(String token) {
        return validateTokenOfType(token, TOKEN_TYPE_REFRESH);
    }

    private boolean validateTokenOfType(String token, String expectedType) {
        if (!validateToken(token)) {
            return false;
        }
        String actualType = getAllClaimsFromToken(token).get(TOKEN_TYPE_CLAIM, String.class);
        if (!expectedType.equals(actualType)) {
            log.warn("Rejected JWT: expected type '{}' but token carries '{}'", expectedType, actualType);
            return false;
        }
        return true;
    }

    public long getAccessTokenExpiration() {
        return accessTokenExpiration;
    }

    public long getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }
}
