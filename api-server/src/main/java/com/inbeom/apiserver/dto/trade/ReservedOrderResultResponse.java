package com.inbeom.apiserver.dto.trade;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 예약주문 접수/취소 결과 응답 DTO (camelCase).
 *
 * <p>KIS 응답 rt_cd != "0"이면 예외를 던지지 않고 success=false + message(msg1)로 graceful 반환한다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReservedOrderResultResponse {

    /** 처리 성공 여부 (KIS rt_cd == "0"). */
    private boolean success;

    /** 처리 결과 메시지 (KIS msg1 또는 안내). */
    private String message;

    /** 예약주문순번 (접수 시 KIS RSVN_ORD_SEQ). 실패/미제공 시 null. */
    private String reservationSeq;

    /** 예약주문 조직번호 (접수 시 KIS RSVN_ORD_ORGNO). 실패/미제공 시 null. */
    private String orgNo;
}
