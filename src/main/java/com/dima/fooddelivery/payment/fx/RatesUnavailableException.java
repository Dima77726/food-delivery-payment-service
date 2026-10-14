package com.dima.fooddelivery.payment.fx;

/**
 * Курса нет: ЦБ недоступен, а свежего или допустимо устаревшего значения в кэше тоже нет.
 *
 * <p>Это сбой чужого сервиса, а не отказ банка, поэтому платёж им не отклоняется: исключение уходит
 * наверх, транзакция откатывается, и команда оплаты повторяется.
 */
public class RatesUnavailableException extends RuntimeException {

    public RatesUnavailableException(String message) {
        super(message);
    }

    public RatesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
