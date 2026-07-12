package com.inbeom.apiserver.dto.asset;

import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * 일별 총자산 스냅샷 upsert 요청 (자산 화면 - 자산 추이).
 * POST /assets/snapshot
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetSnapshotRequest {

    @NotNull(message = "totalAsset is required")
    private Long totalAsset;
}
