package com.inbeom.apiserver.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일별 총자산 스냅샷 (자산 화면 - 자산 추이 라인차트).
 *
 * <p>unique(user_id, snapshot_date) 로 하루 1건을 보장하며, 자산 화면이 매일 총자산을
 * upsert 하면 프론트가 이를 라인차트로 표시한다. Liquibase v1.17 에서 생성된다.
 */
@Entity
@Table(name = "asset_daily_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssetDailySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "total_asset", nullable = false)
    private Long totalAsset;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
