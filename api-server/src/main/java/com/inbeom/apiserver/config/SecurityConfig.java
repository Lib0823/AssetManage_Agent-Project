package com.inbeom.apiserver.config;

import com.inbeom.apiserver.security.InternalAuthFilter;
import com.inbeom.apiserver.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                .requestMatchers("/health", "/health/**", "/auth/**", "/actuator/**", "/test/**", "/market/**", "/company/**", "/stocks/**", "/overseas/stocks/**", "/news/**", "/ws/**", "/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(internalAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
