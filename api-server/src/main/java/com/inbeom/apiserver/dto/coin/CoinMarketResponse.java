package com.inbeom.apiserver.dto.coin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 원화(KRW) 마켓 한 종목.
 *
 * <p>{@code warning}/{@code cautions} 는 {@code /v1/market/all?isDetails=true} 로만 온다.
 * 실제 자금이 오가는 화면이므로 업비트 앱과 같이 유의/주의 배지를 표시할 수 있게 함께 내려보낸다
 * (조회 비용은 파라미터 한 글자다).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinMarketResponse {

    /** 마켓 코드 (예: {@code KRW-BTC}). */
    private String market;

    /** 심볼 (예: {@code BTC}) — {@code market} 에서 통화 접두를 뗀 값. 검색·표시 편의용. */
    private String symbol;

    private String koreanName;
    private String englishName;

    /** 업비트 유의 종목 지정 여부. */
    private Boolean warning;

    /**
     * 활성화된 주의 플래그 이름들 (예: {@code GLOBAL_PRICE_DIFFERENCES}).
     * 없으면 빈 목록이며 null 이 아니다.
     */
    private List<String> cautions;
}
