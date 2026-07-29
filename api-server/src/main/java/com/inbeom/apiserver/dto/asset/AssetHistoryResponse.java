package com.inbeom.apiserver.dto.asset;

import lombok.*;

/**
 * 일별 총자산 스냅샷 조회 응답 (자산 화면 - 자산 추이 라인차트).
 * GET /assets/history
 *
 * <p>필드는 camelCase 로 직렬화된다({@code date}, {@code totalAsset}).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssetHistoryResponse {

    // 스냅샷 날짜 (YYYY-MM-DD)
    private String date;

    // 해당 일자 총자산
    private Long totalAsset;
}
