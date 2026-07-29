package com.inbeom.apiserver.dto.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 종목 호가 응답 (검색/종목 상세 화면).
 * GET /stocks/{stockCode}/orderbook
 *
 * KIS 주식현재가 호가/예상체결(FHKST01010200) output1 매핑:
 * askp1..askp10/askp_rsqn1..askp_rsqn10 → asks (매도호가/잔량),
 * bidp1..bidp10/bidp_rsqn1..bidp_rsqn10 → bids (매수호가/잔량).
 * current_price 는 주식현재가 시세(FHKST01010100) stck_prpr 를 별도로 조회해 채운다.
 * 시세 미연동(quote 비활성) 또는 조회 실패 시 asks/bids 는 빈 리스트, 가격은 null, notice 에 안내 메시지.
 * JSON 직렬화는 camelCase — 프론트가 camelCase로 읽는다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderbookResponse {

    private String stockCode;

    // 현재가 (원, FHKST01010100 stck_prpr)
    private Long currentPrice;

    // 매도호가 (1~10단계, 0원 호가 제외)
    private List<OrderbookLevel> asks;

    // 매수호가 (1~10단계, 0원 호가 제외)
    private List<OrderbookLevel> bids;

    // 시세 미연동 시 UI 안내용 메시지 (정상이면 null)
    private String notice;

    /**
     * 호가 단계 (가격 + 잔량).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderbookLevel {

        // 호가 (원)
        private Long price;

        // 잔량 (주)
        private Long quantity;
    }
}
