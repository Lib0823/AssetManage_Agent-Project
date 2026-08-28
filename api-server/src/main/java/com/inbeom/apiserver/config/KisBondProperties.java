package com.inbeom.apiserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 장내채권 주문 관련 설정 ({@code kis.bond.*}).
 *
 * <p>여기에 값이 하나뿐인 이유는 그 하나가 <b>확정되지 않았기 때문</b>이다. 채권 주문 수량
 * ({@code ORD_QTY2})이 액면금액인지 좌수인지 KIS 문서·예제 어디에도 명시가 없고, 스펙과 계획이
 * 각각 "액면 10,000원 기준"과 "÷100"을 적어 <b>같은 문서 안에서 100배 어긋나 있었다</b>.
 *
 * <p>이 값을 코드에 리터럴로 박으면, 틀렸을 때 사용자가 주문 확인 화면에서 100배 틀린 금액을
 * 보고 실전 주문을 낸다. 그래서 설정으로 분리해 <b>재배포 없이 정정 가능</b>하게 두고,
 * 서버와 프론트가 같은 값을 쓰도록 잔고 응답에 실어 내려보낸다.
 *
 * <p><b>확정 방법</b>: 실계좌로 {@code inquire-psbl-order}(TTTC8910R)를 알려진 단가로 1회 호출해
 * {@code buy_psbl_amt ÷ buy_psbl_qty} 비율을 보면 즉시 판별된다(자금 이동 없음).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "kis.bond")
public class KisBondProperties {

    /** 예상 금액 = 수량 × 단가 ÷ 이 값. 기본 100 (미검증 잠정값). */
    private BigDecimal faceValueDivisor = new BigDecimal("100");
}
