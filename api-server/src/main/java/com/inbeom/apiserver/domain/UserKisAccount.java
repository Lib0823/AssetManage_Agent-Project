package com.inbeom.apiserver.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_kis_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserKisAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "account_number", nullable = false, unique = true, length = 50)
    private String accountNumber;

    @Builder.Default
    @Column(name = "account_product_code", nullable = false, length = 10)
    private String accountProductCode = "01";

    @Column(name = "app_key", nullable = false, length = 255)
    private String appKey;

    @Column(name = "app_secret", nullable = false, length = 255)
    private String appSecret;

    // KIS HTS ID — 체결통보(실시간 fill) tr_key. 시세와 무관하며 평문 저장(Jasypt 암호화 안 함).
    @Column(name = "hts_id", length = 50)
    private String htsId;

    // KIS 모드 MOCK(모의)/REAL(실전) — 매매/조회/체결통보의 도메인·TR 라우팅 기준(per-user).
    // 기존 계정은 DB 기본값 'MOCK' 으로 하위호환.
    @Builder.Default
    @Column(name = "account_mode", nullable = false, length = 10)
    private String accountMode = "MOCK";

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
