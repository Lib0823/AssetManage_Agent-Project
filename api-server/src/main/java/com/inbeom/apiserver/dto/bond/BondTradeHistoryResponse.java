package com.inbeom.apiserver.dto.bond;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 장내채권 거래내역 응답 DTO (CTSC8013R, inquire-daily-ccld).
 *
 * <p><b>DB 에 기록하지 않고 KIS 에서 직접 조회한다.</b> 국내주식과 같은 방식이며, 이 선택이
 * 중요한 이유는 장내채권이 유동성이 낮아 <b>미체결이 정상적으로 자주 발생</b>하기 때문이다.
 * 주문 시점에 DB 에 쓰는 방식이었다면 화면은 "주문 요청"을 "체결"인 것처럼 보여주고,
 * 사용자는 팔리지 않은 채권을 팔았다고 믿게 된다.
 *
 * <p>목록을 그대로 반환하지 않고 래퍼를 두는 이유는 {@link #notice} 때문이다 —
 * 조회 실패를 예외로 올리지 않고 "빈 목록 + 안내"로 내려보내려면 안내를 실을 자리가 필요하다.
 *
 * <p><b>MUST-VERIFY</b>: 이 TR 의 응답 필드명은 실측 계약 문서가 다루지 않은 범위라
 * 국내주식 체결조회 관례를 따라 후보 키를 순회한다. 실계좌 응답으로 확정 필요.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BondTradeHistoryResponse {

    /** 거래내역 목록 (없거나 조회 실패면 빈 목록). */
    private List<BondTradeHistoryItem> list;

    /** 통화 (고정 "KRW"). */
    private String currency;

    /** 미연동/실패 안내 (정상이면 null). */
    private String notice;

    /** 거래내역 1건. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BondTradeHistoryItem {

        /** 주문일자 (yyyyMMdd). */
        private String orderDate;

        /** 주문시각 (HHmmss). */
        private String orderTime;

        /** 채권 표준종목코드. */
        private String bondCode;

        /** 종목명. */
        private String bondName;

        /** BUY / SELL 로 정규화한 매매구분. 판별 불가면 원문. */
        private String side;

        /** 주문수량. */
        private BigDecimal orderQty;

        /** 체결수량. 미체결이면 0 이거나 주문수량보다 작다. */
        private BigDecimal executedQty;

        /** 체결단가. */
        private BigDecimal executedPrice;

        /** 체결금액. */
        private BigDecimal executedAmount;

        /** 주문번호 (ODNO). */
        private String orderNo;

        /** 처리상태명 (그대로 노출 — 미체결/부분체결을 사용자가 구분할 수 있어야 한다). */
        private String status;
    }
}
