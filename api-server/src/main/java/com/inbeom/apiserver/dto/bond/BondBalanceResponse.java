package com.inbeom.apiserver.dto.bond;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 보유 채권 잔고 응답 DTO (CTSC8407R, inquire-balance).
 *
 * <p>{@link #holdings} 는 <b>매수 로트 단위</b>다 — 같은 채권을 다른 날 샀으면 행이 여럿이다.
 *
 * <p>{@link #totalBuyAmount} 는 <b>매수금액 합계</b>이지 평가금액이 아니다. KIS 채권 잔고에
 * 평가금액 필드가 없기 때문이며, 화면은 이 사실을 반드시 밝혀야 한다. 원화가 아닌 채권
 * ({@code iso_crcy_cd != "KRW"})은 합계에서 제외한다 — 그대로 더하면 총액이 조용히 틀어진다.
 *
 * <p>{@link #faceValueDivisor} 는 서버 설정({@code kis.bond.face-value-divisor})을 그대로 내려보낸
 * 값이다. 프론트가 예상 금액을 계산할 때 이 값을 쓰게 해서, 서버와 화면이 서로 다른 상수로
 * 100배 다른 금액을 보여주는 사고를 막는다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BondBalanceResponse {

    /** 보유 로트 목록 (없으면 빈 목록). */
    private List<BondHoldingResponse> holdings;

    /** 매수금액 합계 (원화 로트만). <b>평가금액이 아니다.</b> */
    private BigDecimal totalBuyAmount;

    /** 통화 (고정 "KRW"). */
    private String currency;

    /**
     * 예상 금액 환산 계수 = {@code 수량 × 단가 / faceValueDivisor}.
     * 수량 단위가 미확정이라 설정값으로 분리했다 — 프론트도 이 값을 써야 한다.
     */
    private BigDecimal faceValueDivisor;

    /** 미연동/실패 안내 (정상이면 null). */
    private String notice;
}
