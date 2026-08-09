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
 *
 * <p>Liquibase v1.21에서 TimescaleDB hypertable로 전환되며 PK가 (id, snapshot_date)
 * 복합키로 바뀌었다({@link AssetDailySnapshotId} 참고) — hypertable은 PK가 파티션
 * 컬럼(snapshot_date)을 포함해야 하기 때문. id는 여전히 IDENTITY 자동증가이며,
 * 애플리케이션 코드는 user_id+snapshot_date로만 조회하므로 동작에 영향 없다.
 */
@Entity
@Table(name = "asset_daily_snapshot")
@IdClass(AssetDailySnapshotId.class)
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

    @Id
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "total_asset", nullable = false)
    private Long totalAsset;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
