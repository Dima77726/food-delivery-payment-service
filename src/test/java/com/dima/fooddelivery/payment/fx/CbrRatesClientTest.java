package com.dima.fooddelivery.payment.fx;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SOAP-клиент к ЦБ РФ против заглушки по настоящему HTTP. Ответ в {@code fx/cbr-get-curs-on-date-response.xml} —
 * настоящий ответ {@code DailyInfo.GetCursOnDateXML}, сокращённый до трёх валют: клиент разбирает то, что
 * реально присылает ЦБ, а не придуманный нами формат.
 */
class CbrRatesClientTest {

    private HttpServer server;

    private final AtomicReference<String> soapAction = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldParseRealCbrResponseAndComputeRatePerUnit() throws Exception {
        CbrRatesClient client = clientAnswering(200, fixture());

        Map<String, BigDecimal> rates = client.ratesOn(LocalDate.of(2026, 10, 14));

        assertAll(
                () -> assertEquals(new BigDecimal("83.8675"), rates.get("USD")),
                () -> assertEquals(new BigDecimal("93.7723"), rates.get("EUR")),
                // Иена котируется за 100 единиц: курс единицы — деление, а не значение из ответа как есть.
                () -> assertEquals(0, new BigDecimal("0.529166").compareTo(rates.get("JPY"))),
                () -> assertEquals(3, rates.size())
        );
    }

    @Test
    void shouldSendSoapActionAndDateInRequest() throws Exception {
        CbrRatesClient client = clientAnswering(200, fixture());

        client.ratesOn(LocalDate.of(2026, 10, 14));

        assertAll(
                () -> assertEquals("\"http://web.cbr.ru/GetCursOnDateXML\"", soapAction.get()),
                () -> assertTrue(requestBody.get().contains("<On_date>2026-10-14T00:00:00</On_date>"), requestBody.get()),
                () -> assertTrue(requestBody.get().contains("GetCursOnDateXML"), requestBody.get())
        );
    }

    @Test
    void shouldReportUnavailableOnHttpError() throws Exception {
        CbrRatesClient client = clientAnswering(503, "Service Unavailable");

        assertThrows(RatesUnavailableException.class, () -> client.ratesOn(LocalDate.of(2026, 10, 14)));
    }

    @Test
    void shouldReportUnavailableOnSoapFault() throws Exception {
        CbrRatesClient client = clientAnswering(500, """
                <?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                <soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>Server was unable to process request</faultstring>
                </soap:Fault></soap:Body></soap:Envelope>""");

        assertThrows(RatesUnavailableException.class, () -> client.ratesOn(LocalDate.of(2026, 10, 14)));
    }

    @Test
    void shouldReportUnavailableWhenResponseHasNoRates() throws Exception {
        CbrRatesClient client = clientAnswering(200, """
                <?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                <soap:Body><GetCursOnDateXMLResponse xmlns="http://web.cbr.ru/"><GetCursOnDateXMLResult>
                <ValuteData OnDate="20261014" xmlns=""/></GetCursOnDateXMLResult></GetCursOnDateXMLResponse></soap:Body></soap:Envelope>""");

        assertThrows(RatesUnavailableException.class, () -> client.ratesOn(LocalDate.of(2026, 10, 14)));
    }

    /** Зависший ЦБ не должен держать транзакцию оплаты: ожидание обрывается по таймауту. */
    @Test
    void shouldGiveUpWhenCbrDoesNotAnswerInTime() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        CbrRatesClient client = new CbrRatesClient(properties(Duration.ofMillis(300)));

        long started = System.nanoTime();
        assertThrows(RatesUnavailableException.class, () -> client.ratesOn(LocalDate.of(2026, 10, 14)));

        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_500, "ожидание обязано оборваться по таймауту");
    }

    // --- вспомогательное -----------------------------------------------------------------------

    private CbrRatesClient clientAnswering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            soapAction.set(exchange.getRequestHeaders().getFirst("SOAPAction"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        return new CbrRatesClient(properties(Duration.ofSeconds(5)));
    }

    private FxProperties properties(Duration readTimeout) {
        return new FxProperties(
                true,
                "http://localhost:" + server.getAddress().getPort() + "/DailyInfoWebServ/DailyInfo.asmx",
                Duration.ofSeconds(2),
                readTimeout,
                Duration.ofHours(6),
                Duration.ofHours(72)
        );
    }

    private static String fixture() throws IOException {
        try (InputStream in = CbrRatesClientTest.class.getResourceAsStream("/fx/cbr-get-curs-on-date-response.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
