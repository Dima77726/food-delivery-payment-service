package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.payment.domain.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Заглушка платёжного шлюза.
 *
 * <p>Настоящей интеграции в проекте нет, и притворяться, что есть, не нужно. Реализация
 * подтверждает любое списание — кроме одного случая: сумма, оканчивающаяся на {@code .13},
 * считается отклонённой банком. Это даёт детерминированный способ проверить ветку отказа
 * в интеграционных тестах, не подменяя бины.
 *
 * <p>Случайный отказ здесь был бы хуже: тесты стали бы «мигающими», а такие тесты команда
 * рано или поздно начинает игнорировать.
 */
@Slf4j
@Component
public class SimulatedPaymentGateway implements PaymentService.PaymentGateway {

    private static final BigDecimal DECLINED_REMAINDER = new BigDecimal("0.13");

    @Override
    public ChargeResult charge(BigDecimal amount, PaymentMethod method, Long orderId) {
        if (method == PaymentMethod.CASH_ON_DELIVERY) {
            // Наличные при получении: списывать нечего, платёж считается принятым сразу.
            return ChargeResult.success();
        }

        if (isDeclinedAmount(amount)) {
            log.info("Имитация отказа банка: orderId={}, amount={}", orderId, amount);

            return ChargeResult.failure("Недостаточно средств на карте");
        }

        log.info("Имитация успешного списания: orderId={}, amount={}", orderId, amount);

        return ChargeResult.success();
    }

    @Override
    public void refund(BigDecimal amount, Long orderId) {
        log.info("Имитация возврата средств: orderId={}, amount={}", orderId, amount);
    }

    private boolean isDeclinedAmount(BigDecimal amount) {
        BigDecimal fractional = amount.remainder(BigDecimal.ONE).abs().stripTrailingZeros();

        return fractional.compareTo(DECLINED_REMAINDER) == 0;
    }
}
