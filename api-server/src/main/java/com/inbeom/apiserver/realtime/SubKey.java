package com.inbeom.apiserver.realtime;

/**
 * 상향 KIS 구독 키 = (TR, 종목코드).
 *
 * <p>{@link SubscriptionManager} 의 refcount 단위이며, KIS 상향 연결당 1회만 등록/해제한다.
 * 다수 브라우저 세션이 같은 (TR, 종목)을 구독해도 상향에는 1개의 SubKey 만 등록된다.
 *
 * <p>{@code trKey} 는 상향 구독 프레임의 {@code body.input.tr_key} 값이다:
 * 국내는 종목코드 그대로(예 005930), 미국은 {@link OverseasTrKey} 가 만드는 포맷(예 DNASAAPL).
 *
 * @param tr     실시간 TR (호가/체결, 국내/미국)
 * @param symbol 원본 종목 식별자 (국내 6자리 / 미국 티커) — fan-out 시 브라우저 메시지의 symbol
 * @param trKey  KIS 상향 구독 프레임 tr_key (국내=symbol, 미국=거래소접두 포함)
 */
public record SubKey(RealtimeTr tr, String symbol, String trKey) {
}
