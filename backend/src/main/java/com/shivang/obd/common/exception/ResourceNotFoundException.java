package com.shivang.obd.common.exception;

import com.shivang.obd.common.api.error.CommonErrorCode;

public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException() {
        super(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    public ResourceNotFoundException(String message) {
        super(CommonErrorCode.RESOURCE_NOT_FOUND, message);
    }
}
