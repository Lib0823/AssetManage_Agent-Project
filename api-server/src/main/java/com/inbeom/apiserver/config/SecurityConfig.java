package com.inbeom.apiserver.config;

import com.inbeom.apiserver.security.InternalAuthFilter;
import com.inbeom.apiserver.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * 공개(비인증) 채권 시세 경로.
     *
     * <p>{@code /bonds/**} 를 통째로 열면 잔고·매도·거래내역이 같이 열리므로 경로를 하나씩 명시하고,
     * 종목코드 자리에는 12자리 영숫자 제약을 건다. <b>제약이 없으면 {@code /bonds/*} 가
     * {@code /bonds/balance}·{@code /bonds/history} 까지 매칭해 인증 없이 잔고가 노출된다.</b>
     *
     * <p>상수로 뽑아 둔 이유는 {@code SecurityConfigBondPathsTest} 가 이 패턴을 그대로 파싱해
     * "잔고·매도·거래내역이 공개로 새지 않는다" 를 회귀 테스트로 고정하기 때문이다 —
     * 여기를 느슨하게 바꾸면 그 테스트가 깨진다.
     */
    public static final String[] PUBLIC_BOND_QUOTE_PATTERNS = {
            "/bonds/{bondCode:[A-Za-z0-9]{12}}",
            "/bonds/{bondCode:[A-Za-z0-9]{12}}/issue-info",
            "/bonds/{bondCode:[A-Za-z0-9]{12}}/price",
            "/bonds/{bondCode:[A-Za-z0-9]{12}}/orderbook"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalAuthFilter internalAuthFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configure(http))  // Enable CORS (configured in WebConfig)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // WebAuthn 생체 등록은 로그인된 상태(JWT)에서만 — 광범위한 /auth/** permitAll 보다 먼저 매칭.
                .requestMatchers("/auth/webauthn/register/**").authenticated()
                // WebAuthn 생체 로그인(usernameless)은 공개.
                .requestMatchers("/auth/webauthn/login/**").permitAll()
                // /ws/** : 실시간 WebSocket 핸드셰이크. 인증은 JwtHandshakeInterceptor(?token=)가 수행.
                // JwtAuthenticationFilter 는 Authorization 헤더만 보므로 WS upgrade 요청엔 무해.
                // /internal/** : ai-agent 서비스-투-서비스 채널. 인증은 InternalAuthFilter(X-Internal-Api-Key)가 수행.
                .requestMatchers("/health", "/health/**", "/auth/**", "/actuator/**", "/market/**", "/company/**", "/stocks/**", "/overseas/stocks/**", "/news/**", "/ws/**", "/internal/**").permitAll()
                .requestMatchers(HttpMethod.GET, PUBLIC_BOND_QUOTE_PATTERNS).permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
