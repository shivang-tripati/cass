package com.shivang.obd.common.exception;

import com.shivang.obd.common.api.error.ApiErrorCode;
import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final transient ApiErrorCode errorCode;

    public BusinessException(ApiErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ApiErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiErrorCode getErrorCode() {
        return errorCode;
    }

    public String getCode() {
        return errorCode.code();
    }

    public HttpStatus getHttpStatus() {
        return errorCode.status();
    }
}
