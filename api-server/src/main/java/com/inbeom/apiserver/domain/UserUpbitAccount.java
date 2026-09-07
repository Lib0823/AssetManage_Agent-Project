package com.inbeom.apiserver.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 사용자별 업비트 Open API 자격증명 (1:1).
 *
 * <p>{@link UserKisAccount} 와 같은 패턴이지만 계좌번호가 없다 — 업비트는 계좌 개념 없이
 * Access Key / Secret Key 두 개로만 인증하고, KIS 처럼 OAuth 토큰을 받아 캐시하지 않고
 * <b>요청마다 JWT 를 새로 만든다.</b>
 *
 * <p>{@code accessKey}/{@code secretKey} 에는 <b>Jasypt 암호문</b>이 들어간다. 복호화는
 * {@code UpbitAuthService} 만 하며, 조회 응답에는 어느 쪽도 평문으로 싣지 않는다.
 */
@Entity
@Table(name = "user_upbit_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserUpbitAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "access_key", nullable = false, length = 255)
    private String accessKey;

    @Column(name = "secret_key", nullable = false, length = 255)
    private String secretKey;

    /** 저장 시 {@code GET /v1/accounts} 1회 호출이 성공했는지. 실패해도 저장은 되고 false 로 남는다. */
    @Builder.Default
    @Column(name = "is_verified", nullable = false)
    private Boolean isVerified = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
