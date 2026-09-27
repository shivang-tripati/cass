package com.shivang.obd.common.api.response;

import java.util.List;
import org.slf4j.MDC;

public final class ResponseFactory {

    private ResponseFactory() {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, ResponseMetadata.now());
    }

    /** Success body without payload (e.g. logout): data/message omitted by NON_NULL. */
    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null, null, null, ResponseMetadata.now());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, null, ResponseMetadata.now());
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, data, null, null, ResponseMetadata.now());
    }

    public static <T> ApiResponse<List<T>> page(List<T> data, PaginationMetadata pagination) {
        return new ApiResponse<>(true, data, null, pagination, ResponseMetadata.now());
    }
}
