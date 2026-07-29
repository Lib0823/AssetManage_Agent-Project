package com.inbeom.apiserver.dto.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 실시간 연결 상태 메시지 (브라우저로 push).
 *
 * <p>{@code {type:"status", state:..., notice:...}}.
 * state 예: {@code disabled}(상향 비활성, REST 스냅샷 유지), {@code reconnecting}, {@code reconnected},
 * {@code limit}(구독상한 초과), {@code subscribed}/{@code unsubscribed}(ACK).
 * 프론트는 이 메시지로 연결상태 배지/안내를 갱신하고 degrade 시 마지막 스냅샷을 유지한다.
 *
 * @param type   항상 "status"
 * @param state  상태 코드
 * @param notice 사용자 안내 메시지(없으면 null → 직렬화 제외)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusMessage(
        @JsonProperty("type") String type,
        @JsonProperty("state") String state,
        @JsonProperty("notice") String notice) {

    public static StatusMessage of(String state, String notice) {
        return new StatusMessage("status", state, notice);
    }
}
