package com.inbeom.apiserver.dto.bond;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 장내채권 매도 주문 요청 DTO (TTTC0958U).
 *
 * <p><b>매수 요청과 같은 DTO 를 쓸 수 없다.</b> 매도에만 있는 필수 파라미터가 셋이고
 * ({@code ORD_DVSN}, {@code SPRX_YN}, {@code SLL_AGCO_OPPS_SLL_YN}), 결정적으로
 * <b>매도는 로트를 지정해야 한다</b> — {@link #buyDate} + {@link #buySeq} 없이는 KIS 가
 * 어느 매수분을 파는지 알 수 없다.
 *
 * <p>{@link #buyDate}/{@link #buySeq} 는 <b>사용자가 입력하는 값이 아니다.</b> 잔고 조회 응답의
 * {@code buy_dt}/{@code buy_sqno} 를 화면이 그대로 실어 보내는 값이며, 임의로 만들면 안 된다.
 *
 * <p>{@link #unitPrice} 는 {@link BigDecimal} 이다. 채권 단가는 소수를 가지며, KIS 로 나갈 때는
 * {@code toPlainString()} 으로 직렬화해야 한다 — {@code toString()} 은 작은 값을 {@code 1E-4}
 * 같은 지수표기로 만들고 KIS 는 그것을 해석하지 못한다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BondSellRequest {

    /**
     * 채권 표준종목코드 (PDNO). <b>12자리 영숫자 혼합</b> — {@code KR2033022D33}.
     * 주식의 6자리 숫자 패턴을 쓰면 정상 채권이 전부 거부된다.
     */
    @NotBlank(message = "채권 종목코드는 필수입니다")
    @Pattern(regexp = "[A-Za-z0-9]{12}", message = "채권 종목코드는 12자리 영숫자입니다")
    private String bondCode;

    /** 종목명 (로깅·확인 다이얼로그용. KIS 로는 보내지 않는다). */
    private String bondName;

    /** 주문수량 (ORD_QTY2). 단위가 액면금액인지 좌수인지 미확정이라 정수 아닌 BigDecimal 로 받는다. */
    @NotNull(message = "주문 수량은 필수입니다")
    private BigDecimal quantity;

    /** 주문단가 (BOND_ORD_UNPR). 소수 허용. */
    @NotNull(message = "주문 단가는 필수입니다")
    private BigDecimal unitPrice;

    /** 매수일자 (BUY_DT, yyyyMMdd) — 잔고의 {@code buy_dt} 를 그대로 넣는다. */
    @NotBlank(message = "매수일자(로트 식별자)는 필수입니다")
    private String buyDate;

    /** 매수순번 (<b>BUY_SEQ</b>) — 잔고의 <b>{@code buy_sqno}</b> 를 그대로 넣는다 (이름이 다름). */
    @NotBlank(message = "매수순번(로트 식별자)은 필수입니다")
    private String buySeq;

    /**
     * 분리과세여부 (SPRX_YN).
     *
     * <p><b>임의 기본값을 두지 않는다</b> — 값에 따라 세금 처리가 달라지므로 서버가 마음대로
     * "N" 으로 채우면 사용자가 의도하지 않은 과세 방식으로 매도된다. 잔고의
     * {@code sprx_qty}/{@code agrx_qty} 에서 유도한 값을 화면이 사용자에게 확인받아 보낸다.
     * 값이 없으면 주문을 거부한다.
     */
    @NotNull(message = "분리과세 여부는 필수입니다")
    private Boolean separateTaxation;
}
