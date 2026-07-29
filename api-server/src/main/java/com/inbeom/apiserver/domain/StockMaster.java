package com.inbeom.apiserver.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 종목 검색 카탈로그 (KOSPI 종목 코드/명).
 *
 * <p>검색 화면(SearchView)에서 종목 코드 prefix / 종목명 부분일치 검색에 사용한다.
 * Liquibase v1.8 에서 ai-agent constants(STOCK_NAMES) 기반으로 seed 된다.
 */
@Entity
@Table(name = "stock_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockMaster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_code", nullable = false, unique = true, length = 10)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 50)
    private String stockName;

    @Builder.Default
    @Column(name = "market", nullable = false, length = 20)
    private String market = "KOSPI";

    /**
     * 해외 거래소 코드 (NASD/NYSE/AMEX). 국내 종목은 NULL.
     */
    @Column(name = "exchange_code", length = 10)
    private String exchangeCode;

    /**
     * 통화 (KRW/USD). 국내 종목은 KRW.
     */
    @Builder.Default
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "KRW";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
