package com.dima.fooddelivery.payment.webhook;

import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Входящий вебхук Stripe через HTTP: подпись, окно времени, inbox и изменения платежей.
 * События подписываются тем же алгоритмом, что у Stripe, — секретом из настроек теста.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.payments.stripe.webhook-secret=" + StripeWebhookIT.SECRET)
class StripeWebhookIT extends AbstractIntegrationTest {

    static final String SECRET = "whsec_test_secret_for_integration";
    private static final AtomicLong ORDER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 7_000_000_000L);

    private final StripeSignatureVerifier signer = new StripeSignatureVerifier(SECRET, Duration.ofMinutes(5), Clock.systemUTC());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldMarkPaymentRefundedWhenRefundWasMadeInStripeDashboard() throws Exception {
        long orderId = insertPayment("SUCCEEDED", "pi_refund_" + UUID.randomUUID());
        String reference = referenceOf(orderId);

        send(event("charge.refunded", "{\"object\":{\"payment_intent\":\"" + reference + "\",\"refunded\":true}}"))
                .andExpect(status().isOk());

        assertEquals("REFUNDED", statusOf(orderId));
        assertEquals(1, outboxCount(orderId, "PAYMENT_REFUNDED"));
    }

    @Test
    void shouldIgnoreRepeatedDeliveryOfTheSameEvent() throws Exception {
        long orderId = insertPayment("SUCCEEDED", "pi_dup_" + UUID.randomUUID());
        String event = event("charge.refunded", "{\"object\":{\"payment_intent\":\"" + referenceOf(orderId) + "\",\"refunded\":true}}");

        send(event).andExpect(status().isOk());
        send(event).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("повтор"));

        assertEquals(1, outboxCount(orderId, "PAYMENT_REFUNDED"));
    }

    @Test
    void shouldCompletePendingPaymentOnReconciliation() throws Exception {
        long orderId = insertPayment("PENDING", "pi_pending_" + UUID.randomUUID());

        send(event("payment_intent.succeeded", "{\"object\":{\"id\":\"" + referenceOf(orderId) + "\"}}"))
                .andExpect(status().isOk());

        assertEquals("SUCCEEDED", statusOf(orderId));
        assertEquals(1, outboxCount(orderId, "PAYMENT_SUCCEEDED"));
    }

    @Test
    void shouldNotOverwriteFinalStatusOnMismatch() throws Exception {
        long orderId = insertPayment("FAILED", "pi_mismatch_" + UUID.randomUUID());

        send(event("payment_intent.succeeded", "{\"object\":{\"id\":\"" + referenceOf(orderId) + "\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("расхождение: у нас FAILED, у Stripe SUCCEEDED"));

        assertEquals("FAILED", statusOf(orderId));
    }

    @Test
    void shouldRejectForgedStaleAndUnsignedRequests() throws Exception {
        String body = event("charge.refunded", "{\"object\":{}}");
        long now = System.currentTimeMillis() / 1000;

        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Stripe-Signature", "t=" + now + ",v1=" + "0".repeat(64)))
                .andExpect(status().isBadRequest());
        long stale = now - 600;
        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Stripe-Signature", "t=" + stale + ",v1=" + signer.sign(stale + "." + body)))
                .andExpect(status().isBadRequest());
        // Подпись верная, но тело изменено после подписи.
        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body + " ")
                        .header("Stripe-Signature", "t=" + now + ",v1=" + signer.sign(now + "." + body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldAcceptAnyOfSeveralSignaturesDuringSecretRotation() throws Exception {
        String body = event("customer.created", "{\"object\":{}}");
        long now = System.currentTimeMillis() / 1000;

        mockMvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Stripe-Signature", "t=" + now + ",v1=" + "a".repeat(64) + ",v1=" + signer.sign(now + "." + body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("пропущено: тип не обрабатывается"));
    }

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        return mockMvc.perform(post("/api/v1/webhooks/stripe")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Stripe-Signature", "t=" + now + ",v1=" + signer.sign(now + "." + body)));
    }

    private static String event(String type, String data) {
        return "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"object\":\"event\",\"type\":\"" + type + "\",\"data\":" + data + "}";
    }

    private long insertPayment(String status, String reference) {
        long orderId = ORDER_IDS.incrementAndGet();
        jdbcTemplate.update("""
                INSERT INTO payment (order_id, customer_id, amount, status, method, idempotency_key, provider_reference)
                VALUES (?, 1, 450.00, ?, 'CARD', ?, ?)
                """, orderId, status, UUID.randomUUID().toString(), reference);
        return orderId;
    }

    private String referenceOf(long orderId) {
        return jdbcTemplate.queryForObject("SELECT provider_reference FROM payment WHERE order_id = ?", String.class, orderId);
    }

    private String statusOf(long orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
    }

    private int outboxCount(long orderId, String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_outbox WHERE aggregate_id = ? AND event_type = ?", Integer.class, orderId, eventType);
    }
}
