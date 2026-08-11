package com.inbeom.apiserver.exception;

/**
 * 자체 rate limit(토큰 버킷)에 걸려 KIS 호출을 <b>보내지 않고</b> 거부했음을 뜻한다.
 *
 * <p><b>왜 별도 타입인가</b>: {@link KisApiException} 의 나머지 실패
 * (4001 client / 4002 server / 4003 network)는 전부 "요청이 이미 KIS 로 나갔고 결과가 확정됐거나
 * 불확실하다"는 뜻이다. 매매 경로에서는 그래서 재시도가 금지된다(중복 주문 위험).
 * 반면 이 예외는 소켓조차 열리지 않았음을 보장하므로 <b>재시도해도 안전한 유일한 KIS 실패</b>다.
 * {@code TradeOrderConsumer} 가 이 타입만 PHASE 1(재시도 가능)로 되돌리기 때문에, 타입 자체가
 * 계약이다 — 다른 실패와 한 타입으로 합치면 유효한 주문이 rate limit 때문에 즉시 DLQ 로 유실된다.
 */
public class KisRateLimitExceededException extends KisApiException {

    public KisRateLimitExceededException(String message) {
        super(ErrorCode.KIS_API_RATE_LIMITED, message);
    }
}
