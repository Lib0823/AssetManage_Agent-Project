package com.inbeom.apiserver.dto.coin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 코인 주문 요청 — <b>사용자 의도</b>를 그대로 받는다.
 *
 * <p>업비트의 {@code ord_type} 3종({@code limit}/{@code price}/{@code market})으로의 변환은
 * {@code CoinTradingService} 가 한다. 프론트가 "시장가 매수는 price, 시장가 매도는 market" 같은
 * 업비트 내부 규칙을 알 필요가 없게 하기 위해서다 — 그 규칙이 프론트로 새면 화면마다 다르게
 * 구현되고, 한 곳만 틀려도 의도와 다른 주문이 나간다.
 *
 * <p><b>{@code quantity}/{@code price} 중 무엇이 필요한지는 주문 타입에 따라 다르다:</b>
 * <table border="1">
 *   <tr><th>의도</th><th>quantity</th><th>price</th></tr>
 *   <tr><td>지정가 매수/매도</td><td>수량 (필수)</td><td>단가 (필수)</td></tr>
 *   <tr><td>시장가 매수</td><td>불필요</td><td><b>주문 총액</b> (필수)</td></tr>
 *   <tr><td>시장가 매도</td><td>수량 (필수)</td><td>불필요</td></tr>
 * </table>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinOrderRequest {

    /** 마켓 코드 (예: {@code KRW-BTC}). 원화 마켓만 지원한다. */
    @NotBlank(message = "마켓 코드는 필수입니다")
    private String market;

    @NotNull(message = "주문 타입(LIMIT/MARKET)은 필수입니다")
    private OrderType orderType;

    /**
     * 주문 수량. 코인은 소수다({@code 0.00012345 BTC}) — {@code BigDecimal} 이어야 한다.
     * 시장가 매수에서는 사용하지 않는다(업비트가 수량이 아닌 총액을 받는다).
     */
    private BigDecimal quantity;

    /** 지정가 단가, 또는 <b>시장가 매수의 주문 총액(원)</b>. 시장가 매도에서는 사용하지 않는다. */
    private BigDecimal price;

    /**
     * 멱등키(선택). 같은 키로 재요청이 오면 업비트를 다시 호출하지 않고 이미 접수된 주문을 돌려준다.
     *
     * <p>네트워크 타임아웃 뒤의 재시도가 중복 주문이 되는 것을 막기 위한 것이므로,
     * <b>프론트가 "주문 시도" 단위로 한 번 생성해 재시도 때 같은 값을 보내야</b> 의미가 있다.
     * 매번 새로 만들면 없는 것과 같다. 미지정 시 서버가 생성한다.
     */
    @Size(max = 64, message = "멱등키는 64자를 넘을 수 없습니다")
    private String idempotencyKey;

    public enum OrderType {
        /** 지정가 — 수량과 단가를 모두 지정. */
        LIMIT,
        /** 시장가 — 매수는 총액, 매도는 수량을 지정(업비트 규칙). */
        MARKET
    }
}
