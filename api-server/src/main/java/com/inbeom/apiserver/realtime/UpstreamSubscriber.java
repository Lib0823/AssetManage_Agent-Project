package com.inbeom.apiserver.realtime;

import java.util.Collection;

/**
 * 상향 KIS 연결에 대한 구독 등록/해제 계약.
 *
 * <p>{@link SubscriptionManager}(refcount) 와 {@link KisRealtimeUpstreamClient}(실제 연결) 사이의
 * 순환 의존을 끊기 위한 인터페이스. Manager 는 0→1(register)/1→0(unregister) 전이에서만 호출한다.
 * UpstreamClient 가 미연동/비활성이면 no-op 으로 처리한다(graceful degrade).
 */
public interface UpstreamSubscriber {

    /** 상향에 SubKey 1개 등록(첫 구독자 발생 시). lazy connect 트리거. */
    void register(SubKey key);

    /** 상향에서 SubKey 1개 해제(마지막 구독자 이탈 시). */
    void unregister(SubKey key);

    /** 재연결 후 호출되어 현재 살아있는 모든 SubKey 를 재등록하기 위한 스냅샷 제공원에서 사용. */
    void registerAll(Collection<SubKey> keys);
}
