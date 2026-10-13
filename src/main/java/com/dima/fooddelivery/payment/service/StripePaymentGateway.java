package com.dima.fooddelivery.payment.service;

import com.dima.fooddelivery.payment.domain.PaymentMethod;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.http.HttpClient;

/**
 * Шлюз к Stripe: настоящее списание и возврат через REST API провайдера.
 *
 * <p>Включается настройкой {@code app.payments.gateway=stripe}; без неё работает заглушка
 * ({@link SimulatedPaymentGateway}). Ключ, адрес API, валюта и способ оплаты — в
 * {@link StripeProperties}.
 *
 * <p><b>Что здесь настоящее, а что нет.</b> Платёж — настоящий {@code PaymentIntent} со
 * списанием, возвратом и ключами идемпотентности. А вот карту клиента сервис не видит: в команде
 * оплаты её нет, и подтверждается платёж фиксированным способом оплаты Stripe
 * ({@code pm_card_visa} — тестовая карта). Для боевой оплаты клиент должен получить способ
 * оплаты у Stripe (Elements) и передать его в команде — это изменение контракта, а не шлюза.
 *
 * <p><b>Ключ идемпотентности уходит провайдеру в каждом запросе</b> ({@code Idempotency-Key}).
 * Сервис вызывает шлюз внутри транзакции: если процесс упадёт между ответом Stripe и коммитом,
 * команда придёт снова, и Stripe на тот же ключ вернёт прежний результат, а не спишет второй раз.
 * Ключи списания и возврата разные ({@code charge-…}, {@code refund-…}): иначе возврат
 * получил бы закэшированный ответ о списании.
 *
 * <p><b>Отказ банка — результат, всё остальное — исключение.</b> Ответ 402 ({@code card_error})
 * означает «банк отказал»: это нормальный исход, он записывается в платёж как FAILED с причиной.
 * Всё прочее — сеть, таймаут, 5xx, 429, неверный ключ — значит, что ответа нет. Исключение
 * откатывает транзакцию, и команда уходит на повтор, а после попыток — в DLT; записать такое
 * как «платёж не прошёл» значило бы отказать клиенту из-за чужого сбоя.
 *
 * <p><b>Только в тестовом режиме</b> ({@code sk_test_…}) сумма с копейками {@code .13}
 * подтверждается картой, которую Stripe отклоняет. Так сохраняется детерминированный отказ
 * заглушки, на котором держатся сценарии Postman. С боевым ключом это не работает.
 */
@Slf4j
@Component
@EnableConfigurationProperties(StripeProperties.class)
@ConditionalOnProperty(name = "app.payments.gateway", havingValue = "stripe")
public class StripePaymentGateway implements PaymentService.PaymentGateway {

    private static final MediaType FORM = MediaType.APPLICATION_FORM_URLENCODED;

    /** Копейки в минимальных единицах: валюты с двумя знаками (usd, eur, rub). Для иен было бы иначе. */
    private static final int MINOR_UNITS_SCALE = 2;

    private final StripeProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public StripePaymentGateway(
            StripeProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<ObservationRegistry> observations
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;

        // Таймауты обязательны: без них зависший Stripe держит поток обработчика и соединение
        // с базой (вызов стоит внутри транзакции) сколько угодно.
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        // Свой билдер, а не внедрённый: автонастроенного RestClient.Builder в сервисе нет (в Spring Boot 4
        // он живёт в отдельном стартере). Наблюдаемость подключается вручную — вызовы Stripe видны в трассах
        // и метриках; адрес запроса секретов не содержит (ключ едет в заголовке).
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .observationRegistry(observations.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    /**
     * Сервис без ключа не должен стартовать молча: первая же оплата упала бы, а клиенты
     * получили бы отказ на ровном месте.
     */
    @PostConstruct
    void validate() {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "app.payments.gateway=stripe, но ключ не задан: укажите STRIPE_API_KEY (sk_test_… для теста)"
            );
        }

        if (properties.apiKey().startsWith("sk_live_")) {
            log.warn("Шлюз Stripe работает с БОЕВЫМ ключом: платежи будут настоящими");
        }

        log.info("Шлюз Stripe включён: baseUrl={}, currency={}, testMode={}",
                properties.baseUrl(), properties.currency(), isTestMode());
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        if (request.method() == PaymentMethod.CASH_ON_DELIVERY) {
            // Наличные при получении: провайдеру нечего списывать.
            return ChargeResult.success();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", String.valueOf(toMinorUnits(request.amount())));
        form.add("currency", properties.currency());
        form.add("payment_method", paymentMethodFor(request.amount()));
        form.add("confirm", "true");
        // Без редиректов: возвращающие клиента на страницу банка способы оплаты нам не подходят,
        // подтверждать платёж здесь некому.
        form.add("automatic_payment_methods[enabled]", "true");
        form.add("automatic_payment_methods[allow_redirects]", "never");
        form.add("description", "Заказ №" + request.orderId());
        form.add("metadata[order_id]", String.valueOf(request.orderId()));

        Response response = post("/v1/payment_intents", form, "charge-" + request.idempotencyKey());

        if (response.status() == 200) {
            return interpretPaymentIntent(response.body(), request.orderId());
        }

        if (response.status() == 402) {
            // Банк отказал. Платёж у Stripe при этом создан, и его номер пригодится при разборе.
            JsonNode error = response.body().path("error");
            String reason = error.path("message").asString("Банк отклонил платёж");
            String reference = error.path("payment_intent").path("id").asString(null);

            log.info("Stripe: банк отказал: orderId={}, code={}, decline={}",
                    request.orderId(), error.path("code").asString(""), error.path("decline_code").asString(""));

            return ChargeResult.failure(reason, reference);
        }

        throw unexpected("списание", response);
    }

    @Override
    public void refund(RefundRequest request) {
        if (request.providerReference() == null) {
            log.info("Возврат у провайдера не нужен, платежа там нет: orderId={}", request.orderId());
            return;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("payment_intent", request.providerReference());
        form.add("amount", String.valueOf(toMinorUnits(request.amount())));
        form.add("metadata[order_id]", String.valueOf(request.orderId()));

        Response response = post("/v1/refunds", form, "refund-" + request.idempotencyKey());

        if (response.status() == 200) {
            log.info("Stripe: возврат оформлен: orderId={}, refund={}",
                    request.orderId(), response.body().path("id").asString(""));
            return;
        }

        // Возврат уже сделан (например, команда пришла вторым путём): результат тот же, что нужен нам.
        if (response.status() == 400
                && "charge_already_refunded".equals(response.body().path("error").path("code").asString(""))) {
            log.info("Stripe: платёж уже возвращён ранее: orderId={}", request.orderId());
            return;
        }

        throw unexpected("возврат", response);
    }

    private ChargeResult interpretPaymentIntent(JsonNode intent, Long orderId) {
        String reference = intent.path("id").asString(null);
        String status = intent.path("status").asString("");

        if ("succeeded".equals(status)) {
            log.info("Stripe: платёж прошёл: orderId={}, paymentIntent={}", orderId, reference);
            return ChargeResult.success(reference);
        }

        // requires_action, requires_payment_method, processing…: деньги не списаны. Ждать здесь
        // нечего — подтверждения (например, 3-D Secure) в этом потоке никто не даст.
        log.warn("Stripe: платёж не завершён: orderId={}, paymentIntent={}, status={}", orderId, reference, status);

        return ChargeResult.failure("Платёж не завершён провайдером (статус " + status + ")", reference);
    }

    private Response post(String path, MultiValueMap<String, String> form, String idempotencyKey) {
        try {
            return client.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(FORM)
                    .body(form)
                    .exchange((request, response) -> new Response(
                            response.getStatusCode().value(),
                            parse(response.getBody().readAllBytes())
                    ));
        } catch (RuntimeException exception) {
            // Сеть, таймаут, обрыв: ответа нет — пусть команда уйдёт на повтор.
            throw new PaymentGatewayException("Stripe недоступен: " + exception.getMessage(), exception);
        }
    }

    private JsonNode parse(byte[] body) {
        if (body.length == 0) {
            return objectMapper.createObjectNode();
        }

        return objectMapper.readTree(body);
    }

    private PaymentGatewayException unexpected(String action, Response response) {
        JsonNode error = response.body().path("error");

        // Тело ответа Stripe не содержит секретов, но номер карты в сообщении быть не должен —
        // поэтому в лог идёт только тип, код и текст ошибки.
        return new PaymentGatewayException("Stripe: %s не выполнено, HTTP %d, type=%s, code=%s, message=%s".formatted(
                action,
                response.status(),
                error.path("type").asString(""),
                error.path("code").asString(""),
                error.path("message").asString("")
        ));
    }

    /** Сумма в минимальных единицах валюты. Ровно два знака после запятой — иначе это ошибка, а не округление. */
    static long toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(MINOR_UNITS_SCALE).longValueExact();
    }

    private String paymentMethodFor(BigDecimal amount) {
        if (isTestMode() && properties.declineFraction() != null && hasFraction(amount, properties.declineFraction())) {
            return properties.declinedPaymentMethod();
        }

        return properties.paymentMethod();
    }

    private boolean isTestMode() {
        return properties.apiKey() != null && properties.apiKey().startsWith("sk_test_");
    }

    private static boolean hasFraction(BigDecimal amount, BigDecimal fraction) {
        return amount.remainder(BigDecimal.ONE).abs().compareTo(fraction) == 0;
    }

    private record Response(int status, JsonNode body) {
    }

    /** Нет ответа провайдера или он непригоден: команду нужно повторить, а не считать платёж проваленным. */
    public static class PaymentGatewayException extends RuntimeException {

        public PaymentGatewayException(String message) {
            super(message);
        }

        public PaymentGatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
