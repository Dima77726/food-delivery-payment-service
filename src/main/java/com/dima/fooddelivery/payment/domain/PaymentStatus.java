package com.dima.fooddelivery.payment.domain;

public enum PaymentStatus {

    PENDING("PENDING"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    REFUNDED("REFUNDED");

    private final String dbValue;

    PaymentStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public boolean canBeRefunded() {
        return this == SUCCEEDED;
    }

    public static PaymentStatus fromDbValue(String dbValue) {
        for (PaymentStatus status : values()) {
            if (status.dbValue.equals(dbValue)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Неизвестный статус платежа из базы данных: " + dbValue);
    }
}
