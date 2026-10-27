package com.dima.fooddelivery.payment.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Проверка заголовка {@code Stripe-Signature} входящего вебхука.
 *
 * <pre>
 * Stripe-Signature: t=1730000000,v1=5257a869e7ec…,v1=…,v0=…
 * подпись = hex(HMAC-SHA256(секрет конечной точки, t + "." + сырое тело))
 * </pre>
 *
 * <ul>
 *     <li><b>Сырое тело, байт в байт.</b> Подпись считается от того, что пришло по сети. Разобрать
 *     JSON и собрать его заново нельзя — порядок полей и пробелы изменятся, подпись не сойдётся;</li>
 *     <li><b>окно времени.</b> {@code t} входит в подпись, и запрос старше {@code tolerance}
 *     отвергается: перехваченный вебхук нельзя проиграть заново (replay);</li>
 *     <li><b>несколько v1.</b> Во время смены секрета в кабинете Stripe подписывает обоими —
 *     достаточно совпадения с любым;</li>
 *     <li><b>сравнение за постоянное время</b> ({@link MessageDigest#isEqual}): обычное сравнение
 *     строк выходит на первом несовпавшем символе и по времени ответа подсказывает подпись.</li>
 * </ul>
 *
 * <p>Ключ HMAC у Stripe — сама строка секрета {@code whsec_…} в UTF-8, без декодирования. У
 * Standard Webhooks (наши исходящие вебхуки) — наоборот, декодированные байты после префикса.
 */
public class StripeSignatureVerifier {

    private final byte[] secret;
    private final Duration tolerance;
    private final Clock clock;

    public StripeSignatureVerifier(String secret, Duration tolerance, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.tolerance = tolerance;
        this.clock = clock;
    }

    /** @throws InvalidSignatureException с причиной для лога (наружу причина не уходит) */
    public void verify(String header, String payload) {
        if (header == null || header.isBlank()) {
            throw new InvalidSignatureException("нет заголовка Stripe-Signature");
        }

        Long timestamp = null;
        java.util.List<String> signatures = new java.util.ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            if ("t".equals(kv[0])) {
                try {
                    timestamp = Long.parseLong(kv[1]);
                } catch (NumberFormatException exception) {
                    throw new InvalidSignatureException("t не число");
                }
            } else if ("v1".equals(kv[0])) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw new InvalidSignatureException("в заголовке нет t или v1");
        }

        long age = clock.instant().getEpochSecond() - timestamp;
        if (Math.abs(age) > tolerance.toSeconds()) {
            throw new InvalidSignatureException("подпись вне окна времени: возраст " + age + " с");
        }

        byte[] expected = sign(timestamp + "." + payload).getBytes(StandardCharsets.US_ASCII);
        for (String signature : signatures) {
            if (MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
                return;
            }
        }
        throw new InvalidSignatureException("подпись не совпала");
    }

    /** Подпись тем же способом, что Stripe, — для тестов и проверки руками. */
    public String sign(String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) {
            super(message);
        }
    }
}
