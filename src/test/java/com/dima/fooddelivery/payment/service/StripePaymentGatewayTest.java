package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.payment.domain.PaymentMethod;
import com.dima.fooddelivery.payment.service.PaymentService.PaymentGateway.ChargeRequest;
import com.dima.fooddelivery.payment.service.PaymentService.PaymentGateway.ChargeResult;
import com.dima.fooddelivery.payment.service.PaymentService.PaymentGateway.RefundRequest;
import com.dima.fooddelivery.payment.service.StripePaymentGateway.PaymentGatewayException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Шлюз Stripe против локального HTTP-сервера, изображающего Stripe, — без сети и без ключа.
 *
 * <p>Проверяется то, что уходит по проводу (путь, заголовки, поля формы) и как ответы
 * превращаются в исходы. Важнее всего граница между «банк отказал» (результат) и «ответа нет»
 * (исключение): от неё зависит, получит ли клиент отказ из-за чужого сбоя.
 *
 * <p>Живую проверку с настоящим тестовым ключом это не заменяет: форму ответов Stripe тест
 * знает только из документации.
 */
class StripePaymentGatewayTest {

    private static final String TEST_KEY = "sk_test_unit";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Recorded> requests = new ConcurrentLinkedQueue<>();

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldCreateConfirmedPaymentIntentWithIdempotencyKey() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(200, """
                {"id":"pi_123","status":"succeeded"}"""));

        ChargeResult result = gateway.charge(charge("900.20", 48L, "key-1"));

        Recorded sent = requests.peek();
        assertAll(
                () -> assertTrue(result.successful()),
                () -> assertEquals("pi_123", result.providerReference()),
                () -> assertEquals("POST /v1/payment_intents", sent.method() + " " + sent.path()),
                () -> assertEquals("Bearer " + TEST_KEY, sent.header("Authorization")),
                // Ключ списания и возврата различаются префиксом: иначе возврат получил бы
                // закэшированный ответ о списании.
                () -> assertEquals("charge-key-1", sent.header("Idempotency-Key")),
                () -> assertEquals("90020", sent.form().get("amount"), "в минимальных единицах валюты"),
                () -> assertEquals("usd", sent.form().get("currency")),
                () -> assertEquals("pm_card_visa", sent.form().get("payment_method")),
                () -> assertEquals("true", sent.form().get("confirm")),
                () -> assertEquals("never", sent.form().get("automatic_payment_methods[allow_redirects]")),
                () -> assertEquals("48", sent.form().get("metadata[order_id]"))
        );
    }

    /** Отказ банка — исход оплаты, а не сбой: он записывается в платёж, и клиент видит причину. */
    @Test
    void shouldReturnBankDeclineAsFailureWithReference() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(402, """
                {"error":{"type":"card_error","code":"card_declined","decline_code":"generic_decline",
                 "message":"Your card was declined.","payment_intent":{"id":"pi_declined"}}}"""));

        ChargeResult result = gateway.charge(charge("450.00", 1L, "key-2"));

        assertAll(
                () -> assertFalse(result.successful()),
                () -> assertEquals("Your card was declined.", result.failureReason()),
                () -> assertEquals("pi_declined", result.providerReference(), "по номеру неудачную попытку находят у Stripe")
        );
    }

    @Test
    void shouldNotTreatUnfinishedPaymentIntentAsPaid() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(200, """
                {"id":"pi_3ds","status":"requires_action"}"""));

        ChargeResult result = gateway.charge(charge("450.00", 1L, "key-3"));

        assertAll(
                () -> assertFalse(result.successful(), "деньги не списаны — это не успех"),
                () -> assertTrue(result.failureReason().contains("requires_action")),
                () -> assertEquals("pi_3ds", result.providerReference())
        );
    }

    /**
     * Нет ответа — не значит «банк отказал». Исключение откатывает транзакцию, команда уходит на
     * повтор, а Stripe на тот же ключ вернёт прежний результат.
     */
    @Test
    void shouldThrowOnServerErrorInsteadOfFailingThePayment() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(503, """
                {"error":{"type":"api_error","message":"temporarily unavailable"}}"""));

        PaymentGatewayException error = assertThrows(PaymentGatewayException.class,
                () -> gateway.charge(charge("450.00", 1L, "key-4")));

        assertTrue(error.getMessage().contains("HTTP 503"), error.getMessage());
    }

    @Test
    void shouldThrowOnInvalidKeyAndOnRateLimit() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(401, """
                {"error":{"type":"invalid_request_error","message":"Invalid API Key provided"}}"""));
        replies.add(new Reply(429, """
                {"error":{"type":"rate_limit_error","message":"Too many requests"}}"""));

        assertThrows(PaymentGatewayException.class, () -> gateway.charge(charge("450.00", 1L, "key-5")));
        assertThrows(PaymentGatewayException.class, () -> gateway.charge(charge("450.00", 1L, "key-6")));
    }

    @Test
    void shouldThrowWhenStripeIsUnreachable() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        server.stop(0);

        assertThrows(PaymentGatewayException.class, () -> gateway.charge(charge("450.00", 1L, "key-7")));
    }

    @Test
    void shouldNotCallStripeForCashOnDelivery() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);

        ChargeResult result = gateway.charge(
                new ChargeRequest(new BigDecimal("450.00"), PaymentMethod.CASH_ON_DELIVERY, 1L, "key-8")
        );

        assertAll(
                () -> assertTrue(result.successful()),
                () -> assertNull(result.providerReference()),
                () -> assertTrue(requests.isEmpty(), "наличные провайдеру не показывают")
        );
    }

    /** В тестовом режиме копейки .13 подтверждаются отклоняемой картой — как у заглушки. */
    @Test
    void shouldUseDeclinedTestCardForThirteenKopecksInTestMode() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(402, """
                {"error":{"type":"card_error","code":"card_declined","message":"declined"}}"""));

        gateway.charge(charge("100.13", 1L, "key-9"));

        assertEquals("pm_card_chargeDeclined", requests.peek().form().get("payment_method"));
    }

    /** С боевым ключом этого хука нет: сумма с .13 не должна менять способ оплаты настоящего клиента. */
    @Test
    void shouldIgnoreDeclineHookWithLiveKey() throws Exception {
        StripePaymentGateway gateway = gateway("sk_live_unit");
        replies.add(new Reply(200, """
                {"id":"pi_live","status":"succeeded"}"""));

        gateway.charge(charge("100.13", 1L, "key-10"));

        assertEquals("pm_card_visa", requests.peek().form().get("payment_method"));
    }

    @Test
    void shouldRefundByPaymentIntentWithOwnIdempotencyKey() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(200, """
                {"id":"re_1","status":"succeeded"}"""));

        gateway.refund(new RefundRequest(new BigDecimal("900.20"), 48L, "pi_123", "key-1"));

        Recorded sent = requests.peek();
        assertAll(
                () -> assertEquals("POST /v1/refunds", sent.method() + " " + sent.path()),
                () -> assertEquals("refund-key-1", sent.header("Idempotency-Key")),
                () -> assertEquals("pi_123", sent.form().get("payment_intent")),
                () -> assertEquals("90020", sent.form().get("amount"))
        );
    }

    @Test
    void shouldDoNothingOnRefundWithoutProviderReference() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);

        gateway.refund(new RefundRequest(new BigDecimal("450.00"), 1L, null, "key-11"));

        assertTrue(requests.isEmpty(), "платежа у провайдера нет — возвращать нечего");
    }

    @Test
    void shouldTreatAlreadyRefundedChargeAsDone() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(400, """
                {"error":{"type":"invalid_request_error","code":"charge_already_refunded","message":"already refunded"}}"""));

        gateway.refund(new RefundRequest(new BigDecimal("450.00"), 1L, "pi_1", "key-12"));
    }

    @Test
    void shouldThrowOnOtherRefundErrors() throws Exception {
        StripePaymentGateway gateway = gateway(TEST_KEY);
        replies.add(new Reply(400, """
                {"error":{"type":"invalid_request_error","code":"resource_missing","message":"No such payment_intent"}}"""));

        assertThrows(PaymentGatewayException.class,
                () -> gateway.refund(new RefundRequest(new BigDecimal("450.00"), 1L, "pi_missing", "key-13")));
    }

    @Test
    void shouldConvertAmountsToMinorUnitsExactly() {
        assertAll(
                () -> assertEquals(90020L, StripePaymentGateway.toMinorUnits(new BigDecimal("900.20"))),
                () -> assertEquals(45000L, StripePaymentGateway.toMinorUnits(new BigDecimal("450"))),
                // Три знака — это не округление, а ошибка данных: копейка, потерянная молча, — чужие деньги.
                () -> assertThrows(ArithmeticException.class,
                        () -> StripePaymentGateway.toMinorUnits(new BigDecimal("1.005")))
        );
    }

    @Test
    void shouldRefuseToStartWithoutApiKey() throws Exception {
        StripePaymentGateway gateway = gateway("");

        assertThrows(IllegalStateException.class, gateway::validate);
    }

    private ChargeRequest charge(String amount, Long orderId, String key) {
        return new ChargeRequest(new BigDecimal(amount), PaymentMethod.CARD, orderId, key);
    }

    private StripePaymentGateway gateway(String apiKey) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));

            requests.add(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    headers,
                    parseForm(new String(body, StandardCharsets.UTF_8))
            ));

            Reply reply = replies.poll();
            if (reply == null) {
                reply = new Reply(500, "{\"error\":{\"message\":\"в тесте не задан ответ\"}}");
            }

            byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        StripeProperties properties = new StripeProperties(
                apiKey,
                "http://localhost:" + server.getAddress().getPort(),
                "usd",
                "pm_card_visa",
                "pm_card_chargeDeclined",
                new BigDecimal("0.13"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );

        return new StripePaymentGateway(properties, objectMapper, RestClient.builder());
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();

        if (body.isBlank()) {
            return form;
        }

        for (String pair : body.split("&")) {
            String[] keyValue = pair.split("=", 2);
            form.put(
                    URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
                    keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : ""
            );
        }

        return form;
    }

    private record Reply(int status, String body) {
    }

    private record Recorded(String method, String path, Map<String, String> headers, Map<String, String> form) {

        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }
}
