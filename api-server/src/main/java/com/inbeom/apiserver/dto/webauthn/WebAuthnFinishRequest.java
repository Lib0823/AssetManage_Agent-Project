package com.inbeom.apiserver.dto.webauthn;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * finish(register/login) 요청.
 *
 * <p>{@code credential} 은 navigator.credentials.create/get 결과를 직렬화한 WebAuthn JSON 문자열이다.
 * 서버는 Yubico {@code PublicKeyCredential.parseRegistrationResponseJson /
 * parseAssertionResponseJson} 로 파싱한다(앱 ObjectMapper 미사용). {@code flowId} 로 저장된
 * ceremony state 를 찾는다.
 */
@Getter
@Setter
@NoArgsConstructor
public class WebAuthnFinishRequest {

    @NotBlank
    private String flowId;

    @NotBlank
    private String credential;
}
