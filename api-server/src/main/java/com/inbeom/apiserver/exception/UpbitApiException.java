package com.inbeom.apiserver.exception;

/**
 * 업비트 Open API 호출 실패의 <b>단일 정규화 타입</b>.
 *
 * <p>업비트는 실패를 여러 형태로 돌려준다: 정상적인 {@code {"error":{...}}} JSON, 404/400 처럼
 * {@code error.name} 이 <b>숫자</b>인 JSON, 점검 중 HTML, 그리고 5xx. 이 셋을 각각 다른 예외로
 * 흘리면 조회 경로의 graceful degrade 가 무너진다(파싱 예외가 그대로 터져 화면이 사라진다).
 * 그래서 {@code UpbitApiClient} 는 <b>모든</b> 실패를 이 타입 하나로 바꾼 뒤 던진다.
 *
 * <p>{@link BusinessException} 을 상속하므로 주문 경로에서 전파되면
 * {@code GlobalExceptionHandler} 가 {@link ErrorCode} 의 HTTP 상태와 코드로 응답한다.
 */
public class UpbitApiException extends BusinessException {

    public UpbitApiException(String message) {
        super(ErrorCode.UPBIT_API_ERROR, message);
    }

    public UpbitApiException(String message, Throwable cause) {
        super(ErrorCode.UPBIT_API_ERROR, message, cause);
    }

    public UpbitApiException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public UpbitApiException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /** 서버 공인 IP 가 업비트 키의 허용 IP 목록에 없음. 일반 401 과 구분해 안내를 다르게 한다. */
    public static UpbitApiException ipNotAllowed(String message) {
        return new UpbitApiException(ErrorCode.UPBIT_IP_NOT_ALLOWED, message);
    }

    /** 자체 토큰 버킷에 걸려 업비트로 나가지 않은 호출. 재시도해도 안전하다. */
    public static UpbitApiException rateLimited(String message) {
        return new UpbitApiException(ErrorCode.UPBIT_API_RATE_LIMITED, message);
    }
}
