package com.inbeom.apiserver.domain;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * {@link AssetDailySnapshot}의 복합키.
 *
 * <p>TimescaleDB hypertable은 PK/UNIQUE 제약이 파티션 컬럼(snapshot_date)을 포함해야
 * 하므로, 기존 surrogate {@code id} 단독 PK 대신 (id, snapshot_date) 복합키를 쓴다.
 * 필드명은 {@link AssetDailySnapshot}의 {@code @Id} 필드와 정확히 일치해야 한다(JPA {@code @IdClass} 규약).
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AssetDailySnapshotId implements Serializable {
    private Long id;
    private LocalDate snapshotDate;
}
