package com.dima.fooddelivery.payment.webhook;

import com.dima.fooddelivery.common.metrics.PaymentMetrics;
import com.dima.fooddelivery.contracts.payment.PaymentEvent;
import com.dima.fooddelivery.payment.domain.Payment;
import com.dima.fooddelivery.payment.domain.PaymentStatus;
import com.dima.fooddelivery.payment.persistence.PaymentRepository;
import com.dima.fooddelivery.payment.service.PaymentEventWriter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Обработка событий Stripe после проверки подписи.
 *
 * <p>Платёж проводится синхронно ({@code confirm=true}), так что исход списания сервис узнаёт из
 * ответа Stripe, а не из вебхука. Вебхук нужен для того, что случается <em>вне</em> нашего
 * вызова:
 * <ul>
 *     <li>{@code charge.refunded} — поддержка вернула деньги в кабинете Stripe. Платёж становится
 *     REFUNDED, а в Kafka уходит PAYMENT_REFUNDED — заказ получает запись в истории. Наш
 *     собственный возврат тоже порождает это событие; тогда платёж уже REFUNDED, и сравнение
 *     со статусом делает обработку пустой;</li>
 *     <li>{@code payment_intent.succeeded} / {@code payment_intent.payment_failed} — сверка
 *     (reconciliation). Если наш вызов оборвался по таймауту, а Stripe платёж провёл, платёж
 *     в базе остался бы PENDING; вебхук доводит его до исхода. Расхождение с уже финальным
 *     статусом не исправляется молча — оно пишется в лог и метрику для разбора человеком;</li>
 *     <li>{@code charge.dispute.created} — клиент оспорил платёж в банке: только лог и метрика.</li>
 * </ul>
 *
 * <p><b>Inbox.</b> Stripe доставляет «хотя бы один раз». Идентификатор события записывается
 * в той же транзакции, что и изменение платежа: повтор находит запись и ничего не делает, а
 * откат обработки откатывает и запись — повтор доделает работу.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final PaymentRepository paymentRepository;
    private final PaymentEventWriter paymentEvents;
    private final PaymentMetrics metrics;
    private final MeterRegistry meterRegistry;

    /** @return что сделано — для журнала и ответа */
    @Transactional
    public String handle(String payload) {
        JsonNode event = objectMapper.readTree(payload);
        String id = event.path("id").asString("");
        String type = event.path("type").asString("");
        JsonNode object = event.path("data").path("object");

        if (id.isEmpty() || type.isEmpty()) {
            throw new StripeSignatureVerifier.InvalidSignatureException("в событии нет id или type");
        }

        Integer seen = jdbc.queryForObject("SELECT COUNT(*) FROM stripe_webhook_event WHERE event_id = ?", Integer.class, id);
        if (seen != null && seen > 0) {
            return "повтор";
        }

        String outcome = switch (type) {
            case "charge.refunded" -> onRefunded(object);
            case "payment_intent.succeeded" -> reconcile(object.path("id").asString(""), PaymentStatus.SUCCEEDED, null);
            case "payment_intent.payment_failed" -> reconcile(object.path("id").asString(""), PaymentStatus.FAILED,
                    object.path("last_payment_error").path("message").asString("Платёж отклонён"));
            case "charge.dispute.created" -> onDispute(object);
            // Подписаться в кабинете Stripe можно на что угодно; незнакомое событие — не ошибка.
            default -> "пропущено: тип не обрабатывается";
        };

        // ON CONFLICT: два одновременных повтора одного события — второй вставит ничего.
        jdbc.update("INSERT INTO stripe_webhook_event (event_id, event_type, outcome) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                id, type, outcome);
        meterRegistry.counter("stripe.webhooks", "type", type).increment();
        log.info("Вебхук Stripe: id={}, type={}, исход: {}", id, type, outcome);
        return outcome;
    }

    private String onRefunded(JsonNode charge) {
        if (!charge.path("refunded").asBoolean(false)) {
            // Частичный возврат: сервис сам таких не делает, статуса для них нет.
            return "частичный возврат: только запись в журнале";
        }
        String reference = charge.path("payment_intent").asString("");
        Payment payment = paymentRepository.findByProviderReference(reference).orElse(null);
        if (payment == null) {
            return "платёж " + reference + " не найден";
        }
        if (!paymentRepository.compareAndSetStatus(payment.getId(), PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED, null)) {
            return "платёж уже " + payment.getStatus().name();
        }
        metrics.paymentRefunded(payment.getMethod().name());
        paymentEvents.append(PaymentEvent.PAYMENT_REFUNDED, payment);
        return "возврат из кабинета Stripe: платёж " + payment.getId() + " → REFUNDED";
    }

    private String reconcile(String reference, PaymentStatus providerStatus, String failureReason) {
        Payment payment = paymentRepository.findByProviderReference(reference).orElse(null);
        if (payment == null) {
            return "платёж " + reference + " не найден";
        }
        if (payment.getStatus() == providerStatus) {
            return "сходится";
        }
        if (payment.getStatus() == PaymentStatus.PENDING
                && paymentRepository.compareAndSetStatus(payment.getId(), PaymentStatus.PENDING, providerStatus, failureReason)) {
            paymentEvents.append(providerStatus == PaymentStatus.SUCCEEDED ? PaymentEvent.PAYMENT_SUCCEEDED : PaymentEvent.PAYMENT_FAILED, payment);
            return "сверка: PENDING → " + providerStatus.name();
        }
        meterRegistry.counter("payment.reconciliation.mismatch").increment();
        log.warn("Расхождение с Stripe: paymentId={}, у нас {}, у провайдера {}", payment.getId(), payment.getStatus(), providerStatus);
        return "расхождение: у нас " + payment.getStatus().name() + ", у Stripe " + providerStatus.name();
    }

    private String onDispute(JsonNode dispute) {
        meterRegistry.counter("payment.disputes").increment();
        log.warn("Stripe: клиент оспорил платёж: charge={}, reason={}",
                dispute.path("charge").asString(""), dispute.path("reason").asString(""));
        return "спор по платежу: нужен разбор";
    }
}
