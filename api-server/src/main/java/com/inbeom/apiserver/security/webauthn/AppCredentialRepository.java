package com.inbeom.apiserver.security.webauthn;

import com.inbeom.apiserver.domain.User;
import com.inbeom.apiserver.domain.WebAuthnCredential;
import com.inbeom.apiserver.repository.UserRepository;
import com.inbeom.apiserver.repository.WebAuthnCredentialRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.exception.Base64UrlException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Yubico {@link CredentialRepository} 구현.
 *
 * <p>userHandle 은 {@code users.id}(Long) 의 하위 8바이트 big-endian 표현이다(결정적 파생).
 * 별도 userHandle 컬럼을 두지 않으므로 users 테이블을 수정하지 않는다.
 * username 은 {@code users.username} 그대로 사용한다.
 */
@Component
@RequiredArgsConstructor
public class AppCredentialRepository implements CredentialRepository {

    private final UserRepository userRepository;
    private final WebAuthnCredentialRepository credentialRepository;

    // ---- userHandle <-> userId 파생 (8바이트 big-endian) ----

    /** userId(Long) → 8바이트 big-endian ByteArray. */
    public static ByteArray userHandleFor(long userId) {
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES);
        buf.putLong(userId);
        return new ByteArray(buf.array());
    }

    /** 8바이트 ByteArray → userId(Long). 길이가 8이 아니면 비어있는 결과. */
    public static Optional<Long> userIdFromHandle(ByteArray handle) {
        byte[] bytes = handle.getBytes();
        if (bytes.length != Long.BYTES) {
            return Optional.empty();
        }
        return Optional.of(ByteBuffer.wrap(bytes).getLong());
    }

    // ---- CredentialRepository 구현 ----

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return userRepository.findByUsername(username)
                .map(user -> credentialRepository.findByUserId(user.getId()).stream()
                        .map(this::toDescriptor)
                        .collect(Collectors.toSet()))
                .orElseGet(Set::of);
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return userRepository.findByUsername(username)
                .map(user -> userHandleFor(user.getId()));
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return userIdFromHandle(userHandle)
                .flatMap(userRepository::findById)
                .map(User::getUsername);
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return credentialRepository.findByCredentialId(credentialId.getBase64Url())
                .filter(cred -> userIdFromHandle(userHandle)
                        .map(uid -> uid.equals(cred.getUser().getId()))
                        .orElse(false))
                .map(this::toRegisteredCredential);
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return credentialRepository.findByCredentialId(credentialId.getBase64Url())
                .map(this::toRegisteredCredential)
                .map(Set::of)
                .orElseGet(Set::of);
    }

    // ---- 매핑 헬퍼 ----

    private PublicKeyCredentialDescriptor toDescriptor(WebAuthnCredential cred) {
        return PublicKeyCredentialDescriptor.builder()
                .id(decodeBase64Url(cred.getCredentialId()))
                .build();
    }

    private RegisteredCredential toRegisteredCredential(WebAuthnCredential cred) {
        return RegisteredCredential.builder()
                .credentialId(decodeBase64Url(cred.getCredentialId()))
                .userHandle(userHandleFor(cred.getUser().getId()))
                .publicKeyCose(decodeBase64Url(cred.getPublicKeyCose()))
                .signatureCount(cred.getSignatureCount())
                .build();
    }

    /**
     * 저장된 base64url 문자열을 {@link ByteArray} 로 디코드한다.
     * 저장 값은 항상 라이브러리의 {@code getBase64Url()} 로 만든 유효 값이므로,
     * 디코드 실패는 데이터 무결성 손상을 의미한다 → IllegalStateException.
     */
    private static ByteArray decodeBase64Url(String value) {
        try {
            return ByteArray.fromBase64Url(value);
        } catch (Base64UrlException e) {
            throw new IllegalStateException("Corrupt base64url value in webauthn_credentials: " + value, e);
        }
    }
}
