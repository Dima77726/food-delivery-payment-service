package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.common.metrics.PaymentMetrics;
import com.dima.fooddelivery.common.security.AccessDeniedForResourceException;
import com.dima.fooddelivery.contracts.payment.PaymentCommand;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.payment.api.PaymentResponse;
import com.dima.fooddelivery.payment.api.PaymentResponseMapper;
import com.dima.fooddelivery.payment.domain.Payment;
import com.dima.fooddelivery.payment.domain.PaymentMethod;
import com.dima.fooddelivery.payment.domain.PaymentStatus;
import com.dima.fooddelivery.payment.persistence.InboxRepository;
import com.dima.fooddelivery.payment.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Платежи: исполнитель саги оплаты.
 *
 * <p>Сам сервис ничего не решает о заказе — он исполняет команды сервиса заказов («спиши»,
 * «верни») и сообщает исход событием. Статус заказа он не знает и знать не должен: заказ
 * мог быть отменён, пока команда шла по топику, и правильный ответ на это даёт оркестратор,
 * а не исполнитель.
 *
 * <p><b>Идемпотентность — на двух уровнях.</b> Inbox отсеивает повтор того же сообщения
 * (Kafka доставляет «хотя бы один раз»). Уникальный ключ идемпотентности платежа защищает
 * от второй команды на ту же оплату — например, если сервис заказов отправил её дважды.
 *
 * <p><b>Обращение к шлюзу внутри транзакции</b> здесь допустимо, потому что шлюз — имитация.
 * С настоящим эквайрингом шаг разбивается на два: записать платёж в PENDING и закоммитить,
 * затем списать с ключом идемпотентности на стороне банка, затем записать исход. Тогда падение
 * между списанием и записью лечится повтором с тем же ключом — банк вернёт прежний результат.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentEventWriter paymentEvents;
    private final InboxRepository inbox;
    private final PaymentMetrics metrics;

    /**
     * Команда из Kafka. Отметка в inbox, работа и исход в outbox — одна транзакция:
     * либо всё, либо ничего, и тогда сообщение придёт снова.
     */
    @Transactional
    public void handle(PaymentCommand command, String topic) {
        if (!inbox.markProcessed(command.commandId(), topic)) {
            log.info("Команда уже обработана, пропускаем: commandId={}", command.commandId());
            return;
        }

        switch (command.commandType()) {
            case PaymentCommand.PAYMENT_REQUESTED -> charge(command);
            case PaymentCommand.REFUND_REQUESTED -> refund(command.orderId());
            default -> log.warn("Неизвестная команда, пропускаем: commandType={}, orderId={}",
                    command.commandType(), command.orderId());
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForCustomer(Long customerId) {
        return PaymentResponseMapper.toResponses(paymentRepository.findByCustomerId(customerId));
    }

    /**
     * Платежи по заказу. Заказ живёт в другом сервисе, но клиент записан в каждом платеже —
     * этого хватает, чтобы не показать чужие. Заказ без платежей и чужой заказ без платежей
     * выглядят одинаково: пустым списком, и ничего лишнего этим не раскрывается.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsForOrder(Long customerId, Long orderId) {
        List<Payment> payments = paymentRepository.findByOrderId(orderId);

        if (payments.stream().anyMatch(payment -> !payment.getCustomerId().equals(customerId))) {
            throw new AccessDeniedForResourceException("Заказ с id=" + orderId + " принадлежит другому клиенту");
        }

        return PaymentResponseMapper.toResponses(payments);
    }

    private void charge(PaymentCommand command) {
        var existing = paymentRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            // Та же оплата уже проведена по другой команде: повторно не списываем, а повторяем
            // исход — сервис заказов мог не получить первый.
            Payment payment = existing.get();
            log.info("Оплата по этому ключу уже есть, повторяем исход: paymentId={}", payment.getId());
            paymentEvents.append(outcomeOf(payment), payment);
            return;
        }

        PaymentMethod method = PaymentMethod.valueOf(command.method());
        BigDecimal amount = command.amount();

        Payment payment = paymentRepository.save(
                new Payment(command.orderId(), command.customerId(), amount, method, command.idempotencyKey())
        );

        // Идентификатор нужен событию; стратегия IDENTITY проставила его при persist.
        paymentRepository.flush();

        PaymentGateway.ChargeResult result = paymentGateway.charge(amount, method, command.orderId());

        if (result.successful()) {
            payment.moveTo(PaymentStatus.SUCCEEDED, null);
            metrics.paymentSucceeded(method.name());

            log.info("Оплата прошла: orderId={}, paymentId={}, amount={}", command.orderId(), payment.getId(), amount);
        } else {
            payment.moveTo(PaymentStatus.FAILED, result.failureReason());
            metrics.paymentFailed(method.name());

            log.warn("Оплата не прошла: orderId={}, reason={}", command.orderId(), result.failureReason());
        }

        paymentEvents.append(outcomeOf(payment), payment);
    }

    /**
     * Возврат. Молча ничего не делает, если успешного платежа нет: заказ мог быть отменён
     * раньше, чем дошла команда на списание, или списание не прошло. Это штатный исход.
     */
    private void refund(Long orderId) {
        var succeeded = paymentRepository.findSucceededByOrderId(orderId);

        if (succeeded.isEmpty()) {
            log.info("Возврат не требуется, успешных платежей нет: orderId={}", orderId);
            return;
        }

        Payment payment = succeeded.get();

        paymentGateway.refund(payment.getAmount(), orderId);

        // Compare-and-set, а не грязная проверка: см. PaymentRepository#compareAndSetStatus.
        if (!paymentRepository.compareAndSetStatus(payment.getId(), PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED, null)) {
            log.warn("Возврат уже оформлен другим обработчиком: paymentId={}", payment.getId());
            return;
        }

        metrics.paymentRefunded(payment.getMethod().name());
        paymentEvents.append(PaymentEvent.PAYMENT_REFUNDED, payment);

        log.info("Оформлен возврат: orderId={}, paymentId={}, amount={}", orderId, payment.getId(), payment.getAmount());
    }

    private static String outcomeOf(Payment payment) {
        return switch (payment.getStatus()) {
            case SUCCEEDED -> PaymentEvent.PAYMENT_SUCCEEDED;
            case REFUNDED -> PaymentEvent.PAYMENT_REFUNDED;
            case FAILED, PENDING -> PaymentEvent.PAYMENT_FAILED;
        };
    }

    /**
     * Граница с внешним миром. Отдельный интерфейс, а не метод внутри сервиса, чтобы
     * в тестах подменять исход оплаты, не поднимая ничего лишнего.
     */
    public interface PaymentGateway {

        ChargeResult charge(BigDecimal amount, PaymentMethod method, Long orderId);

        void refund(BigDecimal amount, Long orderId);

        record ChargeResult(boolean successful, String failureReason) {

            public static ChargeResult success() {
                return new ChargeResult(true, null);
            }

            public static ChargeResult failure(String reason) {
                return new ChargeResult(false, reason);
            }
        }
    }
}
