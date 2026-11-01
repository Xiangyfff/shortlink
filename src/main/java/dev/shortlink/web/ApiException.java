package dev.shortlink.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。message 会原样返回给调用方，所以不要在里面放内部细节。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static ApiException badRequest(String errorCode, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, errorCode, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }
}
