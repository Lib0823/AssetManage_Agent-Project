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
    INVALID_TRADE_PRICE(HttpStatus.BAD_REQUEST, 5003, "Invalid trade price"),

    // Coin / Upbit Errors (6000~6999)
    UPBIT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, 6000, "Upbit account not registered"),
    UPBIT_API_ERROR(HttpStatus.SERVICE_UNAVAILABLE, 6001, "Upbit API error"),
    /**
     * 업비트 API 키에 등록된 허용 IP 와 이 서버의 공인 IP 가 다를 때.
     *
     * <p>일반 401 로 뭉개지 않고 별도 코드를 두는 이유: 사용자가 취할 행동이 완전히 다르다.
     * 키가 틀렸으면 재발급이지만, IP 문제면 업비트에서 <b>허용 IP 를 다시 등록</b>해야 한다.
     * 서버 IP 가 바뀌면 전 요청이 실패하므로 원인이 즉시 드러나야 한다.
     */
    UPBIT_IP_NOT_ALLOWED(HttpStatus.FORBIDDEN, 6002, "Server IP is not registered on the Upbit API key"),
    /** 자체 토큰 버킷에 걸려 <b>업비트를 호출하지 않고</b> 거부한 경우 (4007 과 같은 성격). */
    UPBIT_API_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, 6003,
            "Upbit API call rate limit exceeded — try again shortly"),
    /** 저장된 업비트 자격증명 복호화 실패. 평문 폴백 없이 여기서 끊는다 (4006 과 같은 성격). */
    UPBIT_CREDENTIAL_DECRYPT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, 6004,
            "Stored Upbit credentials could not be decrypted — re-register the account"),
    /**
     * 주문 타입과 파라미터 조합이 업비트 규칙에 어긋날 때 (예: 시장가 매수인데 총액이 없음).
     * 업비트로 나가기 전에 서비스 계층이 거부한 것이므로 400 이다.
     */
    INVALID_COIN_ORDER(HttpStatus.BAD_REQUEST, 6005, "Invalid coin order parameters"),
    /**
     * Secret Key 가 HS256 서명 최소 길이(32바이트)에 미달할 때.
     *
     * <p>별도 코드를 두는 이유: 이 상태를 걸러내지 않으면 jjwt 가 {@code WeakKeyException}
     * ({@code RuntimeException}) 을 던져 <b>주문·자산 조회가 500</b> 으로 터진다. 실제 업비트 키는
     * 40자라 오타·잘못된 붙여넣기에서만 생기는데, 그때 사용자가 봐야 할 것은 "서버 오류"가 아니라
     * "키를 다시 확인하라"는 안내다.
     */
    UPBIT_SECRET_KEY_TOO_SHORT(HttpStatus.BAD_REQUEST, 6006,
            "Upbit Secret Key is too short to sign requests — re-check the key");

    private final HttpStatus httpStatus;
    private final int code;
    private final String message;
}
