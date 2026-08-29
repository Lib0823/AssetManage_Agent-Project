package com.inbeom.apiserver.dto.user;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 업비트 API 키 등록·수정 요청.
 *
 * <p><b>빈 값은 "지우기"가 아니라 "그대로 두기"다.</b> 조회 응답이 실제 키를 돌려주지 않으므로
 * 프론트는 저장된 값을 입력칸에 되채울 수 없다. 빈 값을 삭제로 해석하면, 사용자가 Access Key 만
 * 고치려고 저장을 누르는 순간 Secret Key 가 날아간다.
 *
 * <p>그래서 최초 등록 시에만 둘 다 필수이고(서비스 계층에서 검사), 수정 시에는 채운 필드만 바뀐다.
 *
 * <p>{@code @NotBlank} 를 걸지 않은 것은 이 규칙 때문이다 — 걸면 "Access Key 만 수정" 이 불가능해진다.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUpbitAccountRequest {

    /** 비우면 기존 Access Key 유지. 업비트 키는 40자지만 길이를 강제하지 않는다(형식 변경 대비). */
    @Size(max = 100, message = "Access Key가 너무 깁니다")
    private String accessKey;

    /** 비우면 기존 Secret Key 유지. */
    @Size(max = 100, message = "Secret Key가 너무 깁니다")
    private String secretKey;
}
