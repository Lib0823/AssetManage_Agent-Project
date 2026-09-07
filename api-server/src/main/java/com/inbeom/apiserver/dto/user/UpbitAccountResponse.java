package com.inbeom.apiserver.dto.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 업비트 계좌 등록 상태 (조회 응답).
 *
 * <p><b>Secret Key 는 어떤 형태로도 실리지 않는다.</b> 필드 자체가 없다 — 마스킹 문자열조차 두지
 * 않는 이유는, 필드가 있으면 언젠가 누군가 "여기 실제 값을 넣으면 편하겠다"고 채우기 때문이다.
 * 등록 여부는 {@code secretKeyRegistered} 로만 알린다.
 *
 * <p><b>{@code KisAccountResponse} 의 {@code decryptForDisplay} 패턴을 의도적으로 따르지 않았다.</b>
 * 그쪽은 암호화 도입 이전의 평문 레코드 때문에 프로필 화면이 500 으로 죽으면 사용자가 키를
 * 재등록할 방법조차 없어지는 상황을 피하려는 트레이드오프다. {@code user_upbit_accounts} 는 신규
 * 테이블이라 레거시 평문 레코드가 존재하지 않으므로, 그 트레이드오프를 물려받을 이유가 없다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpbitAccountResponse {

    private Long id;

    /** 계좌 등록 여부. false 면 나머지 필드는 전부 null 이다. */
    private boolean registered;

    /**
     * Access Key 앞 4자 + {@code ****} (예: {@code "ab12****"}).
     * 어느 키가 등록돼 있는지 사용자가 식별하기 위한 최소 정보다.
     */
    private String accessKeyMasked;

    /** Secret Key 가 저장돼 있는지. 값은 절대 내려보내지 않는다. */
    private boolean secretKeyRegistered;

    /** 저장 시 {@code GET /v1/accounts} 1회 호출이 성공했는지. */
    private Boolean isVerified;

    /** 저장 시 검증이 실패했다면 그 이유. 성공 시 null. */
    private String verificationNotice;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
