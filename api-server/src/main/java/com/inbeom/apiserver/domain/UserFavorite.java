package com.inbeom.apiserver.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 사용자별 관심 종목 (관심종목 화면 - FavoritesView).
 *
 * <p>unique(user_id, stock_code) 로 종목 중복을 막으며, 종목명은 표시용으로 비정규화 저장한다.
 * Liquibase v1.8 에서 생성된다.
 */
@Entity
@Table(name = "user_favorites")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false, length = 10)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    // 해외 거래소 코드 (NASD/NYSE/AMEX). 국내는 null → 국내/해외 구분 및 해외 시세 라우팅에 사용.
    @Column(name = "exchange_code", length = 10)
    private String exchangeCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
