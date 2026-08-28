package com.inbeom.apiserver.dto.bond;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 보유 채권 1건 = <b>하나의 매수 로트</b> (CTSC8407R, inquire-balance 의 output 1행).
 *
 * <p>주식 잔고와 결정적으로 다른 점: 채권 잔고는 종목 단위가 아니라
 * {@code pdno + buy_dt + buy_sqno} 단위로 쪼개져 온다. 같은 채권을 다른 날 사면 별개의 행이다.
 * 따라서 <b>매도는 "종목"이 아니라 "로트"를 지정</b>해야 하고, {@link #buyDate}/{@link #buySeq}
 * 없이는 팔 수 없다.
 *
 * <p><b>필드명 함정</b>: 잔고 응답은 {@code buy_sqno}(일련번호)인데 매도 요청 파라미터는
 * {@code BUY_SEQ}(순번)다. 이름이 달라서 무심코 같은 키로 매핑하면 <b>빈 값이 조용히 나가고</b>
 * KIS 는 로트를 특정하지 못한 채 주문을 처리한다.
 *
 * <p><b>평가금액이 없다.</b> KIS 채권 잔고 output 11개 필드에 현재가 기준 평가금액이 없고
 * {@link #buyAmount}(매수금액)만 있다. 화면은 "매수금액 기준"임을 반드시 밝혀야 하며,
 * 평가금액이 필요하면 종목별 시세를 따로 호출해야 한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BondHoldingResponse {

    /** 상품번호 = 채권 표준종목코드 (pdno), 12자리 영숫자 혼합. */
    private String bondCode;

    /**
     * 종목명. <b>잔고 응답에는 종목명이 없다</b> — 필요하면 기본조회(CTPF1114R)를 따로 불러야 한다.
     * 여기서는 응답에 이름 필드가 섞여 오는 경우에만 채워지고, 보통 null 이다.
     */
    private String bondName;

    /** 잔고수량 (cblc_qty). */
    private BigDecimal quantity;

    /** 주문가능수량 (ord_psbl_qty) — 매도 수량 상한. */
    private BigDecimal orderableQuantity;

    /** 매수단가 (buy_unpr). */
    private BigDecimal buyUnitPrice;

    /** 매수금액 (buy_amt) — 평가금액이 아니다. */
    private BigDecimal buyAmount;

    /** 매수수익율 (buy_erng_rt). */
    private BigDecimal buyYield;

    /** 매수일자 (buy_dt, yyyyMMdd) → 매도 시 {@code BUY_DT} 로 되돌려준다. */
    private String buyDate;

    /** 매수일련번호 (<b>buy_sqno</b>) → 매도 시 <b>{@code BUY_SEQ}</b> 로 되돌려준다 (이름이 다름). */
    private String buySeq;

    /** 분리과세수량 (sprx_qty) — 매도의 {@code SPRX_YN} 유도 근거. */
    private BigDecimal sprxQty;

    /** 종합과세수량 (agrx_qty) — 매도의 {@code SPRX_YN} 유도 근거. */
    private BigDecimal agrxQty;

    /** 만기일 (exdt, yyyyMMdd). */
    private String maturityDate;

    /** 통화코드 (iso_crcy_cd). 응답에 없으면 null 이며 원화로 간주한다. */
    private String currencyCode;

    /**
     * 이 로트의 분리과세 여부 추정값. {@code sprxQty > 0} 이면 true, {@code agrxQty > 0} 이면 false,
     * 둘 다 판단 불가면 null.
     *
     * <p>매도 화면이 사용자에게 보여주고 확인받는 값이다 — {@code SPRX_YN} 을 임의로 고정하면
     * 세금 처리가 달라지므로 서버가 추정하되 사용자가 최종 확인한다.
     */
    private Boolean separateTaxation;
}
