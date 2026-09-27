package com.shivang.obd.common.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success,
    T data,
    String message,
    PaginationMetadata pagination,
    ResponseMetadata meta
) {
}
