package com.inbeom.apiserver.dto.trade;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 국내주식 예약주문 접수 요청 DTO (camelCase).
 *
 * <p>KIS 예약주문(order-resv, CTSC0008U)은 실전 계좌 전용 TR 이다.
 * 프런트가 모드 안내로 게이트하므로 백엔드는 실전 경로만 구현한다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PlaceReservedOrderRequest {

    @NotBlank(message = "Stock code is required")
    private String stockCode;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    /** 주문단가. 시장가(priceType=market)면 무시되고 "0"으로 전송된다. */
    @NotNull(message = "Price is required")
    private Long price;

    /** "buy" 또는 "sell". */
    @NotBlank(message = "Side is required (buy or sell)")
    private String side;

    /** "limit"(지정가, 기본) 또는 "market"(시장가). */
    private String priceType = "limit";

    /** 예약주문 종료일 (YYYYMMDD, 익영업일~최대 30일). */
    @NotBlank(message = "End date is required (YYYYMMDD)")
    private String endDate;
}
