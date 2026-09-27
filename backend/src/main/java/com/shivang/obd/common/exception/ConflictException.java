package com.shivang.obd.common.exception;

import com.shivang.obd.common.api.error.CommonErrorCode;

public class ConflictException extends BusinessException {

    public ConflictException() {
        super(CommonErrorCode.CONFLICT);
    }

    public ConflictException(String message) {
        super(CommonErrorCode.CONFLICT, message);
    }
}
