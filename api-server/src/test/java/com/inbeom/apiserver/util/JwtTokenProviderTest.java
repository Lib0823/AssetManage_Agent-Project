package com.inbeom.apiserver.util;

import com.inbeom.apiserver.exception.BusinessException;
import com.inbeom.apiserver.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtTokenProvider 는 실제 JWT 를 만들고 파싱하는 순수 로직이므로 mock 없이 진짜 토큰으로 검증한다.
 */
@DisplayName("JwtTokenProvider 단위 테스트")
class JwtTokenProviderTest {

    private static final String SECRET =
            "test-only-jwt-secret-key-not-used-in-any-real-environment-0123456789";
    private static final long ACCESS_EXPIRATION = 3_600_000L;
    private static final long REFRESH_EXPIRATION = 86_400_000L;

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);
    }

    @Nested
    @DisplayName("생성자 테스트")
    class ConstructorTest {

        @Test
        @DisplayName("생성자 - secret 이 null 이면 IllegalStateException")
        void constructor_Fail_NullSecret() {
            // When & Then
            assertThatThrownBy(() -> new JwtTokenProvider(null, ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        }

        @Test
        @DisplayName("생성자 - secret 이 공백이면 IllegalStateException")
        void constructor_Fail_BlankSecret() {
            // When & Then
            assertThatThrownBy(() -> new JwtTokenProvider("   ", ACCESS_EXPIRATION, REFRESH_EXPIRATION))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        }

        @Test
        @DisplayName("생성자 - 만료 시간 설정값이 getter 로 그대로 노출된다")
        void constructor_ExposesExpirations() {
            // Then
            assertThat(jwtTokenProvider.getAccessTokenExpiration()).isEqualTo(ACCESS_EXPIRATION);
            assertThat(jwtTokenProvider.getRefreshTokenExpiration()).isEqualTo(REFRESH_EXPIRATION);
        }
    }

    @Nested
    @DisplayName("Access Token 발급/파싱 테스트")
    class AccessTokenTest {

        @Test
        @DisplayName("generateAccessToken - username/userId/kisAccountId 클레임이 모두 담긴다")
        void generateAccessToken_ContainsAllClaims() {
            // Given
            String username = "testuser";
            Long userId = 1L;
            Long kisAccountId = 10L;

            // When
            String token = jwtTokenProvider.generateAccessToken(username, userId, kisAccountId);

            // Then
            assertThat(token).isNotBlank();
            assertThat(token.split("\\.")).hasSize(3);
            assertThat(jwtTokenProvider.getUsernameFromToken(token)).isEqualTo(username);
            assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(userId);
            assertThat(jwtTokenProvider.getKisAccountIdFromToken(token)).isEqualTo(kisAccountId);
        }

        @Test
        @DisplayName("generateAccessToken - kisAccountId 가 null 이어도 발급되고 null 로 읽힌다")
        void generateAccessToken_NullKisAccountId() {
            // Given & When
            String token = jwtTokenProvider.generateAccessToken("testuser", 1L, null);

            // Then
            assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(1L);
            assertThat(jwtTokenProvider.getKisAccountIdFromToken(token)).isNull();
        }

        @Test
        @DisplayName("generateAccessToken - 만료 시각이 accessTokenExpiration 만큼 뒤로 설정된다")
        void generateAccessToken_SetsExpiration() {
            // Given
            long before = System.currentTimeMillis();

            // When
            String token = jwtTokenProvider.generateAccessToken("testuser", 1L, 10L);

            // Then
            Claims claims = jwtTokenProvider.getAllClaimsFromToken(token);
            long expiration = claims.getExpiration().getTime();
            assertThat(expiration).isBetween(before + ACCESS_EXPIRATION - 5_000, before + ACCESS_EXPIRATION + 5_000);
        }

        @Test
        @DisplayName("getAllClaimsFromToken - subject/issuedAt/expiration 을 모두 반환한다")
        void getAllClaimsFromToken_Success() {
            // Given
            String token = jwtTokenProvider.generateAccessToken("testuser", 7L, 70L);

            // When
            Claims claims = jwtTokenProvider.getAllClaimsFromToken(token);

            // Then
            assertThat(claims.getSubject()).isEqualTo("testuser");
            assertThat(claims.getIssuedAt()).isNotNull();
            assertThat(claims.getExpiration()).isNotNull();
            assertThat(claims.get("userId", Long.class)).isEqualTo(7L);
            assertThat(claims.get("kisAccountId", Long.class)).isEqualTo(70L);
        }
    }

    @Nested
    @DisplayName("Refresh Token 발급/파싱 테스트")
    class RefreshTokenTest {

        @Test
        @DisplayName("generateRefreshToken - subject 만 담기고 userId/kisAccountId 클레임은 없다")
        void generateRefreshToken_OnlySubject() {
            // Given & When
            String token = jwtTokenProvider.generateRefreshToken("testuser");

            // Then
            assertThat(jwtTokenProvider.getUsernameFromToken(token)).isEqualTo("testuser");
            assertThat(jwtTokenProvider.getUserIdFromToken(token)).isNull();
            assertThat(jwtTokenProvider.getKisAccountIdFromToken(token)).isNull();
            assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("generateRefreshToken - 만료 시각이 refreshTokenExpiration 만큼 뒤로 설정된다")
        void generateRefreshToken_SetsExpiration() {
            // Given
            long before = System.currentTimeMillis();

            // When
            String token = jwtTokenProvider.generateRefreshToken("testuser");

            // Then
            long expiration = jwtTokenProvider.getAllClaimsFromToken(token).getExpiration().getTime();
            assertThat(expiration).isBetween(before + REFRESH_EXPIRATION - 5_000, before + REFRESH_EXPIRATION + 5_000);
        }
    }

    @Nested
    @DisplayName("토큰 타입 구분 테스트")
    class TokenTypeTest {

        @Test
        @DisplayName("발급된 토큰에 type 클레임이 access/refresh 로 담긴다")
        void tokensCarryTypeClaim() {
            String accessToken = jwtTokenProvider.generateAccessToken("testuser", 1L, 10L);
            String refreshToken = jwtTokenProvider.generateRefreshToken("testuser");

            assertThat(jwtTokenProvider.getAllClaimsFromToken(accessToken).get("type", String.class))
                    .isEqualTo(JwtTokenProvider.TOKEN_TYPE_ACCESS);
            assertThat(jwtTokenProvider.getAllClaimsFromToken(refreshToken).get("type", String.class))
                    .isEqualTo(JwtTokenProvider.TOKEN_TYPE_REFRESH);
        }

        @Test
        @DisplayName("validateAccessToken - 리프레시 토큰은 거부한다 (로그아웃 우회 차단)")
        void validateAccessToken_RejectsRefreshToken() {
            String refreshToken = jwtTokenProvider.generateRefreshToken("testuser");

            assertThat(jwtTokenProvider.validateAccessToken(refreshToken)).isFalse();
            assertThat(jwtTokenProvider.validateAccessToken(
                    jwtTokenProvider.generateAccessToken("testuser", 1L, 10L))).isTrue();
        }

        @Test
        @DisplayName("validateRefreshToken - 액세스 토큰은 거부한다")
        void validateRefreshToken_RejectsAccessToken() {
            String accessToken = jwtTokenProvider.generateAccessToken("testuser", 1L, 10L);

            assertThat(jwtTokenProvider.validateRefreshToken(accessToken)).isFalse();
            assertThat(jwtTokenProvider.validateRefreshToken(
                    jwtTokenProvider.generateRefreshToken("testuser"))).isTrue();
        }

        @Test
        @DisplayName("type 클레임이 없는 토큰은 양쪽 모두 거부한다")
        void tokenWithoutTypeClaim_RejectedByBoth() {
            String legacyToken = Jwts.builder()
                    .subject("testuser")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                    .compact();

            assertThat(jwtTokenProvider.validateToken(legacyToken)).isTrue();
            assertThat(jwtTokenProvider.validateAccessToken(legacyToken)).isFalse();
            assertThat(jwtTokenProvider.validateRefreshToken(legacyToken)).isFalse();
        }
    }

    @Nested
    @DisplayName("토큰 검증 테스트")
    class ValidateTokenTest {

        @Test
        @DisplayName("validateToken - 정상 토큰은 true")
        void validateToken_Success() {
            // Given
            String token = jwtTokenProvider.generateAccessToken("testuser", 1L, 10L);

            // When & Then
            assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("validateToken - 만료된 토큰은 false")
        void validateToken_Fail_Expired() {
            // Given - 발급 즉시 만료되는 provider
            JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -1_000L, -1_000L);
            String expiredToken = expiredProvider.generateAccessToken("testuser", 1L, 10L);

            // When & Then
            assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
        }

        @Test
        @DisplayName("validateToken - 형식이 깨진 토큰은 false")
        void validateToken_Fail_Malformed() {
            // When & Then
            assertThat(jwtTokenProvider.validateToken("this-is-not-a-jwt")).isFalse();
        }

        @Test
        @DisplayName("validateToken - 빈 문자열은 false")
        void validateToken_Fail_Empty() {
            // When & Then
            assertThat(jwtTokenProvider.validateToken("")).isFalse();
        }

        @Test
        @DisplayName("validateToken - null 은 false")
        void validateToken_Fail_Null() {
            // When & Then
            assertThat(jwtTokenProvider.validateToken(null)).isFalse();
        }

        @Test
        @DisplayName("getUsernameFromToken - 만료된 토큰은 ExpiredJwtException")
        void getUsernameFromToken_Fail_Expired() {
            // Given
            JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -1_000L, -1_000L);
            String expiredToken = expiredProvider.generateAccessToken("testuser", 1L, 10L);

            // When & Then
            assertThatThrownBy(() -> jwtTokenProvider.getUsernameFromToken(expiredToken))
                    .isInstanceOf(ExpiredJwtException.class);
        }

        @Test
        @DisplayName("getUsernameFromToken - 다른 키로 서명된 토큰은 서명 검증에 실패한다")
        void getUsernameFromToken_Fail_WrongSignature() {
            // Given
            String forged = forgedToken();

            // When & Then
            assertThatThrownBy(() -> jwtTokenProvider.getUsernameFromToken(forged))
                    .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
        }

        /**
         * 다른 비밀키로 서명한 위조 토큰. 서명 검증 실패 경로를 재현한다.
         */
        private String forgedToken() {
            SecretKey otherKey = Keys.hmacShaKeyFor(
                    "completely-different-secret-key-for-forgery-test-0123456789".getBytes(StandardCharsets.UTF_8));
            Date now = new Date();
            return Jwts.builder()
                    .subject("attacker")
                    .issuedAt(now)
                    .expiration(new Date(now.getTime() + ACCESS_EXPIRATION))
                    .signWith(otherKey)
                    .compact();
        }
    }

    @Nested
    @DisplayName("Bearer 토큰 추출 테스트")
    class ResolveBearerTokenTest {

        @Test
        @DisplayName("resolveBearerToken - 'Bearer {token}' 에서 토큰만 떼어낸다")
        void resolveBearerToken_Success() {
            // When
            String token = jwtTokenProvider.resolveBearerToken("Bearer abc.def.ghi");

            // Then
            assertThat(token).isEqualTo("abc.def.ghi");
        }

        @Test
        @DisplayName("resolveBearerToken - 토큰 앞뒤 공백은 제거된다")
        void resolveBearerToken_TrimsWhitespace() {
            // When
            String token = jwtTokenProvider.resolveBearerToken("Bearer   abc.def.ghi  ");

            // Then
            assertThat(token).isEqualTo("abc.def.ghi");
        }

        @Test
        @DisplayName("resolveBearerToken - 헤더가 null 이면 INVALID_TOKEN")
        void resolveBearerToken_Fail_NullHeader() {
            // When & Then
            assertThatThrownBy(() -> jwtTokenProvider.resolveBearerToken(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Bearer")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("resolveBearerToken - Bearer 접두사가 없으면 INVALID_TOKEN (500 아님)")
        void resolveBearerToken_Fail_NoBearerPrefix() {
            // When & Then
            assertThatThrownBy(() -> jwtTokenProvider.resolveBearerToken("abc.def.ghi"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("resolveBearerToken - 'Bearer ' 뒤가 비어 있으면 INVALID_TOKEN")
        void resolveBearerToken_Fail_EmptyToken() {
            // When & Then
            assertThatThrownBy(() -> jwtTokenProvider.resolveBearerToken("Bearer    "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("토큰이 없습니다")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("resolveBearerToken - 대소문자가 다른 'bearer' 는 거부한다")
        void resolveBearerToken_Fail_LowercasePrefix() {
            // When & Then
            assertThatThrownBy(() -> jwtTokenProvider.resolveBearerToken("bearer abc.def.ghi"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("resolveBearerToken - 추출한 토큰이 그대로 파싱 가능하다")
        void resolveBearerToken_RoundTrip() {
            // Given
            String issued = jwtTokenProvider.generateAccessToken("testuser", 1L, 10L);

            // When
            String resolved = jwtTokenProvider.resolveBearerToken("Bearer " + issued);

            // Then
            assertThat(jwtTokenProvider.getUsernameFromToken(resolved)).isEqualTo("testuser");
        }
    }
}
