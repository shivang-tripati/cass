package com.shivang.obd.common.api.response;

import java.time.Instant;

public record ResponseMetadata(String requestId, Instant timestamp) {

    public static ResponseMetadata now() {
        return new ResponseMetadata(RequestIdFilter.currentRequestId(), Instant.now());
    }
}
