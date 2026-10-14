package com.dima.fooddelivery.payment.fx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.core.support.WebServiceGatewaySupport;
import org.springframework.ws.soap.client.core.SoapActionCallback;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.JdkHttpClientMessageSender;
import org.springframework.xml.transform.StringSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import java.math.BigDecimal;
import java.math.MathContext;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * SOAP-клиент к ЦБ РФ: официальные курсы валют на дату ({@code DailyInfo.GetCursOnDateXML}).
 *
 * <p>Это внешний сервис со своим контрактом, поэтому классы под него не генерируются: нужны два
 * поля из ответа, и клиент строит запрос строкой, а ответ разбирает как DOM. Зато ответ ЦБ —
 * {@code xs:any} внутри конверта — не привязан к схеме, которую ЦБ может поменять.
 *
 * <p>Таймауты обязательны: вызов стоит внутри транзакции оплаты. Отказы (сеть, HTTP-ошибка, SOAP Fault,
 * пустой набор) превращаются в {@link RatesUnavailableException} — решать, что делать без курса,
 * должен вызывающий, а не клиент.
 */
@Slf4j
class CbrRatesClient extends WebServiceGatewaySupport {

    private static final String NAMESPACE = "http://web.cbr.ru/";
    private static final String SOAP_ACTION = "\"http://web.cbr.ru/GetCursOnDateXML\"";

    CbrRatesClient(FxProperties properties) {
        JdkHttpClientMessageSender sender = new JdkHttpClientMessageSender(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build()
        );
        // Ограничивает ожидание ответа целиком; без него зависший ЦБ держал бы транзакцию оплаты.
        sender.setRequestTimeout(properties.readTimeout());

        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
        messageFactory.afterPropertiesSet();

        WebServiceTemplate template = getWebServiceTemplate();
        template.setMessageFactory(messageFactory);
        template.setMessageSender(sender);
        template.setDefaultUri(properties.cbrUrl());
    }

    /**
     * Курсы на дату: код валюты (USD) -> сколько рублей стоит одна её единица.
     *
     * <p>ЦБ отдаёт курс за {@code Vnom} единиц (иена — за 100), поэтому курс единицы считается делением.
     * Дата в выходной день возвращает последний опубликованный курс — это поведение самого ЦБ.
     */
    Map<String, BigDecimal> ratesOn(LocalDate date) {
        String request = "<GetCursOnDateXML xmlns=\"" + NAMESPACE + "\"><On_date>" + date + "T00:00:00</On_date>"
                + "</GetCursOnDateXML>";

        DOMResult result = new DOMResult();

        try {
            getWebServiceTemplate().sendSourceAndReceiveToResult(
                    new StringSource(request),
                    new SoapActionCallback(SOAP_ACTION),
                    result
            );
        } catch (RuntimeException exception) {
            // WebServiceIOException, SoapFaultClientException, таймауты — для вызывающего всё одно: курса нет.
            throw new RatesUnavailableException("ЦБ РФ не ответил: " + exception.getMessage(), exception);
        }

        Map<String, BigDecimal> rates = parse(result);

        if (rates.isEmpty()) {
            throw new RatesUnavailableException("ЦБ РФ вернул пустой набор курсов на " + date);
        }

        log.info("Курсы ЦБ РФ получены: date={}, currencies={}", date, rates.size());

        return rates;
    }

    private static Map<String, BigDecimal> parse(DOMResult result) {
        Map<String, BigDecimal> rates = new HashMap<>();

        if (!(result.getNode() instanceof Document || result.getNode() instanceof Element)) {
            return rates;
        }

        // Элементы Valute* лежат в пространстве имён "" (xmlns="" в ответе), поэтому ищем без него.
        NodeList entries = ((result.getNode() instanceof Document document)
                ? document.getDocumentElement()
                : (Element) result.getNode()).getElementsByTagName("ValuteCursOnDate");

        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);

            try {
                String code = text(entry, "VchCode");
                BigDecimal nominal = new BigDecimal(text(entry, "Vnom"));
                BigDecimal rate = new BigDecimal(text(entry, "Vcurs"));

                if (nominal.signum() > 0 && rate.signum() > 0) {
                    rates.put(code, rate.divide(nominal, MathContext.DECIMAL64));
                }
            } catch (RuntimeException broken) {
                // Одна битая запись не должна лишать нас остальных курсов.
                log.warn("Пропущена запись курса ЦБ РФ: {}", broken.getMessage());
            }
        }

        return rates;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);

        if (nodes.getLength() == 0) {
            throw new IllegalArgumentException("нет элемента " + tag);
        }

        return nodes.item(0).getTextContent().trim();
    }
}
