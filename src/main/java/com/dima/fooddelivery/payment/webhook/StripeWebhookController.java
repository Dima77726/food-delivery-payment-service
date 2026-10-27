package com.dima.fooddelivery.payment.webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Входящий вебхук Stripe. Токена здесь нет и быть не может — Stripe не наш пользователь;
 * подлинность доказывает подпись {@code Stripe-Signature} секретом конечной точки из кабинета.
 *
 * <p>Ответы — по правилам Stripe: 2xx — «принято, больше не присылай»; любой другой код —
 * Stripe будет повторять до трёх суток. Поэтому неверная подпись — 400 (повтор не поможет, но
 * в кабинете Stripe видно, что что-то не так), а сбой обработки — 500 (повтор поможет).
 *
 * <p>Тело принимается строкой, а не объектом: подпись считается от сырых байт.
 */
@Slf4j
@RestController
@Tag(name = "Webhooks (inbound)", description = "Входящие вебхуки провайдеров")
public class StripeWebhookController {

    private final StripeWebhookService service;
    private final StripeSignatureVerifier verifier;

    public StripeWebhookController(
            StripeWebhookService service,
            @Value("${app.payments.stripe.webhook-secret:}") String secret,
            @Value("${app.payments.stripe.webhook-tolerance:PT5M}") Duration tolerance
    ) {
        this.service = service;
        this.verifier = secret.isBlank() ? null : new StripeSignatureVerifier(secret, tolerance, Clock.systemUTC());
    }

    @PostMapping("/api/v1/webhooks/stripe")
    @Operation(summary = "Событие Stripe (charge.refunded, payment_intent.*, charge.dispute.created)")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(name = "Stripe-Signature", required = false) String signature,
            @RequestBody String payload
    ) {
        if (verifier == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Приём вебхуков Stripe не настроен: задайте STRIPE_WEBHOOK_SECRET"));
        }
        try {
            verifier.verify(signature, payload);
            return ResponseEntity.ok(Map.of("outcome", service.handle(payload)));
        } catch (StripeSignatureVerifier.InvalidSignatureException exception) {
            log.warn("Вебхук Stripe отклонён: {}", exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Неверная подпись или событие"));
        }
    }
}
