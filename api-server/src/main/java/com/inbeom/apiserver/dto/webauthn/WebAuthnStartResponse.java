package com.inbeom.apiserver.dto.webauthn;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * start(register/login) 응답.
 *
 * <p>{@code options} 는 Yubico 라이브러리가 직렬화한 WebAuthn JSON 문자열
 * (toCredentialsCreateJson / toCredentialsGetJson). 그대로 navigator.credentials API 형식이며,
 * 앱 ObjectMapper 로 재가공하지 않는다. 프론트는 {@code JSON.parse(options)} 후 사용한다.
 * {@code flowId} 는 finish 단계에서 ceremony state 를 찾기 위한 키다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebAuthnStartResponse {
    private String flowId;
    private String options;
}
