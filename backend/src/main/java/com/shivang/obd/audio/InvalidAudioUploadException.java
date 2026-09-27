package com.shivang.obd.audio;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;

/** A rejected audio upload (unsupported/invalid content, size, or format). */
public class InvalidAudioUploadException extends BusinessException {

    public InvalidAudioUploadException(String message) {
        super(CommonErrorCode.VALIDATION_ERROR, message);
    }
}
