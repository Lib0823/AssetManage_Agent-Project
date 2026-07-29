package com.inbeom.apiserver.realtime;

/**
 * 상향 KIS WebSocket 연결 1개에 대한 자격증명 값객체.
 *
 * <p>Phase 1 은 시세(quote) 앱키 1세트로 단일 상향 연결만 사용한다(평문 프레임, flag 0).
 * Phase 2(체결통보 H0STCNI0/H0GSCNI0, 사용자키 + AES)에서 사용자별 연결을 추가할 때,
 * {@link KisRealtimeUpstreamClient} 가 이 값객체만 바꿔 받으면 되도록 자격증명을 외부화한다.
 *
 * @param appKey    KIS app key (시세: quote-app-key)
 * @param appSecret KIS app secret (시세: quote-app-secret)
 * @param approvalBaseUrl approval_key 발급용 base URL (시세: quote-base-url, 실전 도메인)
 */
public record ConnectionCredentials(String appKey, String appSecret, String approvalBaseUrl) {

    public boolean isUsable() {
        return appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank()
                && approvalBaseUrl != null && !approvalBaseUrl.isBlank();
    }
}
