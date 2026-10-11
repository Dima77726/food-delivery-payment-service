package com.dima.fooddelivery.common.api;

public record ApiErrorResponse(
        int status,
        String error,
        String message,
        String path
) {
}
