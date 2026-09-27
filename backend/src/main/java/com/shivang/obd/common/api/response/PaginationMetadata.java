package com.shivang.obd.common.api.response;

public record PaginationMetadata(
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean hasNext,
    boolean hasPrevious
) {

    public static PaginationMetadata of(int page, int size, long totalElements) {
        int safeSize = Math.max(size, 1);
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);
        boolean hasNext = page + 1 < totalPages;
        boolean hasPrevious = page > 0 && totalElements > 0;
        return new PaginationMetadata(page, safeSize, totalElements, totalPages, hasNext, hasPrevious);
    }
}
