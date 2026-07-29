package com.inbeom.apiserver.dto.overseas;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 해외주식 주문(매수/매도) 요청 DTO.
 *
 * <p>모의 해외 주문은 지정가(ORD_DVSN="00")만 지원하므로 단가(price)는 필수다.
 * exchange 는 잔고/매매용 거래소코드(NASD/NYSE/AMEX)를 받는다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OverseasOrderRequest {

    @NotBlank(message = "Symbol is required")
    private String symbol;

    @NotBlank(message = "Exchange is required (NASD/NYSE/AMEX)")
    private String exchange;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    @NotNull(message = "Price is required (지정가 only)")
    private BigDecimal price;
}
