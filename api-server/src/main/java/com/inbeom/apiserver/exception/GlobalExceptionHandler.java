package com.inbeom.apiserver.exception;

import com.inbeom.apiserver.dto.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * BusinessException 처리.
     *
     * <p>HTTP 상태와 함께 {@link ErrorCode} 의 숫자 대역을 {@code ApiResponse.code} 로 실어 보낸다.
     * 같은 400 이라도 5001(잔고 부족)과 5002(수량 오류)를 프런트가 구분할 수 있어야 하기 때문이다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.error("BusinessException occurred [{}]: {}", errorCode.getCode(), e.getDetailMessage(), e);
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode, e.getDetailMessage()));
    }

    /**
     * BadCredentialsException 처리 (Spring Security)
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentialsException(BadCredentialsException e) {
        log.error("BadCredentialsException occurred: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_CREDENTIALS.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password"));
    }

    /**
     * Validation 예외 처리
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException e
    ) {
        log.error("Validation failed: {}", e.getMessage());

        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, "Validation failed", errors));
    }

    /**
     * IllegalArgumentException 처리
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("IllegalArgumentException occurred: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, e.getMessage()));
    }

    /**
     * MethodArgumentTypeMismatchException 처리
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatchException(
            MethodArgumentTypeMismatchException e
    ) {
        log.error("Type mismatch occurred: {}", e.getMessage());
        String message = String.format("Invalid value '%s' for parameter '%s'",
                e.getValue(), e.getName());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, message));
    }

    /**
     * 읽을 수 없는 요청 본문(깨진 JSON, 빈 body 등) 처리.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE, "Malformed or missing request body"));
    }

    /**
     * 필수 쿼리 파라미터 누락 처리
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE,
                        String.format("Required parameter '%s' is missing", e.getParameterName())));
    }

    /**
     * 필수 요청 헤더 누락 처리
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        log.warn("Missing request header: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT_VALUE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT_VALUE,
                        String.format("Required header '%s' is missing", e.getHeaderName())));
    }

    /**
     * 지원하지 않는 HTTP 메서드 처리
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not supported: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED,
                        String.format("Method '%s' is not supported for this endpoint", e.getMethod())));
    }

    /**
     * 지원하지 않는 Content-Type 처리
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("Media type not supported: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.getMessage()));
    }

    /**
     * 매핑되지 않은 경로 처리.
     *
     * <p>Spring MVC 는 핸들러가 없는 요청을 정적 리소스 탐색으로 넘기므로 최종적으로
     * {@link NoResourceFoundException} 이 올라온다. 이를 잡지 않으면 오탈자 URL 이 500 으로 나간다.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        log.warn("No handler found: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.ENTITY_NOT_FOUND.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.ENTITY_NOT_FOUND, "Requested endpoint does not exist"));
    }

    /**
     * 일반 예외 처리 (Fallback)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected exception occurred", e);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred. Please try again later."));
    }
}
