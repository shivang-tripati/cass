package com.shivang.obd.authz;

import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.exception.BusinessException;

public class RoleScopeViolationException extends BusinessException {

    public RoleScopeViolationException(String message) {
        super(CommonErrorCode.BUSINESS_RULE_VIOLATION, message);
    }
}
