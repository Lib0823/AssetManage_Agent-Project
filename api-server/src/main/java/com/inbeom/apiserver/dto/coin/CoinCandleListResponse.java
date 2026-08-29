package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 캔들 목록 + 안내 문구.
 *
 * <p>{@link CoinMarketListResponse} 와 같은 이유로 감싼다 — 빈 목록이 "데이터 없음"인지
 * "조회 실패"인지 화면이 구분할 수 있어야 차트 자리에 무엇을 그릴지 정할 수 있다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinCandleListResponse {

    private String market;

    /** 요청한 단위 그대로 (예: {@code days}, {@code 60}). */
    private String unit;

    /** 항상 non-null. 실패 시 빈 목록. 과거→현재 순으로 정렬해 내려보낸다. */
    private List<CoinCandleResponse> candles;

    /** 정상일 때 null. 실패 시 안내 문구. */
    private String notice;
}
