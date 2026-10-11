package com.dima.fooddelivery.payment.api;

import com.dima.fooddelivery.payment.domain.PaymentMethod;
import com.dima.fooddelivery.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentResponse(
        Long id,
        Long orderId,
        Long customerId,
        BigDecimal amount,
        PaymentStatus status,
        PaymentMethod method,
        String failureReason,
        OffsetDateTime createdAt
) {
}
