package com.inbeom.apiserver.dto.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inbeom.apiserver.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 전 컨트롤러 공용 응답 래퍼. 형태는 다음을 유지한다:
 *
 * <pre>{@code
 * // 성공
 * { "success": true,  "message": "...", "data": { } }
 * // 실패 (ErrorCode 를 아는 경우에만 code 포함)
 * { "success": false, "message": "...", "data": null, "code": 5001 }
 * }</pre>
 *
 * <p>{@code code} 는 {@link ErrorCode} 의 숫자 대역(1000s 공통 / 2000s 인증 / 3000s 사용자 /
 * 4000s KIS / 5000s 거래)을 클라이언트에 노출하기 위한 <b>선택</b> 필드다. HTTP 상태만으로는
 * "잔고 부족(5001)"과 "수량 오류(5002)"를 구분할 수 없어(둘 다 400) 프런트가 분기할 수 없었다.
 *
 * <p>기존 소비자 보호를 위해 {@code NON_NULL} 을 <b>{@code code} 필드에만</b> 건다(클래스 단위로
 * 걸면 종전에 {@code "data": null} 로 나가던 실패 응답에서 {@code data} 키가 사라져 계약이 바뀐다).
 * 따라서 {@code code} 를 채우지 않는 모든 기존 응답의 JSON 은 종전과 동일하며,
 * web-app/ai-agent 의 기존 파싱 코드는 영향을 받지 않는다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private T data;

    /** {@link ErrorCode#getCode()} 값. 성공 응답과 코드 미상 실패 응답에서는 null → 직렬화 시 생략. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer code;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
            .success(true)
            .message("Success")
            .data(data)
            .build();
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
            .success(true)
            .message(message)
            .data(data)
            .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .build();
    }

    public static <T> ApiResponse<T> error(String message, T data) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(data)
            .build();
    }

    /**
     * ErrorCode 대역을 함께 실어 보내는 실패 응답. {@code GlobalExceptionHandler} 전용 경로다.
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(null)
            .code(errorCode.getCode())
            .build();
    }

    /**
     * ErrorCode 대역 + 부가 데이터(예: 필드별 validation 메시지)를 함께 싣는 실패 응답.
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, T data) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(message)
            .data(data)
            .code(errorCode.getCode())
            .build();
    }
}
