package com.shivang.obd.common.api.error;

import org.springframework.http.HttpStatus;

public interface ApiErrorCode {

    String code();

    HttpStatus status();

    String defaultMessage();
}
