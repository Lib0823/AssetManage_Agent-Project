package com.inbeom.apiserver.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common Errors (1000~1999)
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, 1000, "Internal server error"),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, 1001, "Invalid input value"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, 1002, "Method not allowed"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, 1003, "Entity not found"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, 1004, "Unsupported media type"),

    // Authentication & Authorization Errors (2000~2999)
    AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, 2000, "Authentication failed"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, 2001, "Invalid username or password"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, 2002, "Invalid or expired token"),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, 2003, "Refresh token not found"),
    REFRESH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, 2004, "Refresh token has been revoked"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, 2005, "Access denied"),

    // User Errors (3000~3999)
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, 3000, "User not found"),
    USER_ALREADY_EXISTS(HttpStatus.CONFLICT, 3001, "User already exists"),
    USERNAME_DUPLICATE(HttpStatus.CONFLICT, 3002, "Username already exists"),
    EMAIL_DUPLICATE(HttpStatus.CONFLICT, 3003, "Email already exists"),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, 3004, "Password confirmation does not match"),
    PHONE_MISMATCH(HttpStatus.BAD_REQUEST, 3005, "Phone number does not match"),

    // KIS Account Errors (4000~4999)
    KIS_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, 4000, "KIS account not found"),
    KIS_API_CLIENT_ERROR(HttpStatus.BAD_REQUEST, 4001, "KIS API client error (invalid credentials or parameters)"),
    KIS_API_SERVER_ERROR(HttpStatus.SERVICE_UNAVAILABLE, 4002, "KIS API server error"),
    KIS_API_NETWORK_ERROR(HttpStatus.SERVICE_UNAVAILABLE, 4003, "KIS API network error"),
    KIS_OAUTH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, 4004, "Failed to obtain KIS OAuth token"),
    KIS_ACCOUNT_DUPLICATE(HttpStatus.CONFLICT, 4005, "Account number already exists"),
    KIS_CREDENTIAL_DECRYPT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, 4006,
            "Stored KIS credentials could not be decrypted — re-register the account"),
    /**
     * 자체 rate limit(토큰 버킷)에 걸려 <b>KIS 를 호출하지 않고</b> 거부한 경우.
     * KIS 가 응답한 오류(4001~4003)와 구분된다 — 잠시 후 재시도하면 성공할 수 있다.
     */
    KIS_API_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, 4007,
            "KIS API call rate limit exceeded — try again shortly"),

    // Trade Errors (5000~5999)
    TRADE_HISTORY_NOT_FOUND(HttpStatus.NOT_FOUND, 5000, "Trade history not found"),
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, 5001, "Insufficient balance"),
    INVALID_TRADE_QUANTITY(HttpStatus.BAD_REQUEST, 5002, "Invalid trade quantity"),
    INVALID_TRADE_PRICE(HttpStatus.BAD_REQUEST, 5003, "Invalid trade price");

    private final HttpStatus httpStatus;
    private final int code;
    private final String message;
}
