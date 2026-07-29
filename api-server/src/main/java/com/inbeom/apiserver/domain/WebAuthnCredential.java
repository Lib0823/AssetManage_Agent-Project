package com.inbeom.apiserver.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * WebAuthn(패스키/생체) 자격증명.
 *
 * <p>한 user 가 기기별로 여러 row 를 가질 수 있고(1:N), 각 credential_id 는 전역 UNIQUE 다.
 * userHandle 은 별도 컬럼 없이 {@code userId} 8바이트 big-endian 으로 결정적 파생하므로
 * users 테이블을 수정하지 않는다.
 */
@Entity
@Table(name = "webauthn_credentials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAuthnCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** WebAuthn credential ID (base64url). */
    @Column(name = "credential_id", nullable = false, unique = true, length = 512)
    private String credentialId;

    /** COSE 공개키 (base64url). */
    @Column(name = "public_key_cose", nullable = false, columnDefinition = "TEXT")
    private String publicKeyCose;

    @Builder.Default
    @Column(name = "signature_count", nullable = false)
    private Long signatureCount = 0L;

    /** 인증기 transports (csv, nullable). 예: "internal,hybrid". */
    @Column(name = "transports", length = 255)
    private String transports;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
