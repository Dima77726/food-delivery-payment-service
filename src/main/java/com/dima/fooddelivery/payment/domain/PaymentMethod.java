package com.dima.fooddelivery.payment.domain;

public enum PaymentMethod {

    CARD("CARD"),
    CASH_ON_DELIVERY("CASH_ON_DELIVERY");

    private final String dbValue;

    PaymentMethod(String dbValue) {
        this.dbValue = dbValue;
    }

    public String getDbValue() {
        return dbValue;
    }

    public static PaymentMethod fromDbValue(String dbValue) {
        for (PaymentMethod method : values()) {
            if (method.dbValue.equals(dbValue)) {
                return method;
            }
        }

        throw new IllegalArgumentException("Неизвестный способ оплаты из базы данных: " + dbValue);
    }
}
