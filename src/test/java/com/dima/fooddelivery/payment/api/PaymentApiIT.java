package com.dima.fooddelivery.payment.api;

import com.dima.fooddelivery.common.kafka.KafkaTopics;
import com.dima.fooddelivery.contracts.payment.PaymentCommand;
import com.dima.fooddelivery.payment.service.PaymentService;
import com.dima.fooddelivery.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST сервиса платежей: только чтение и только своих платежей.
 */
@AutoConfigureMockMvc
class PaymentApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentService paymentService;

    @Test
    void shouldShowOwnPayments() throws Exception {
        long customerId = jwt.newUserId();
        long orderId = paidOrder(customerId);

        mockMvc.perform(get("/api/v1/customers/{c}/orders/{o}/payments", customerId, orderId)
                        .header("Authorization", "Bearer " + jwt.tokenFor(customerId, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].orderId").value(orderId));

        mockMvc.perform(get("/api/v1/customers/{c}/payments", customerId)
                        .header("Authorization", "Bearer " + jwt.tokenFor(customerId, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void shouldForbidReadingForeignPayments() throws Exception {
        long ownerId = jwt.newUserId();
        long strangerId = jwt.newUserId();

        mockMvc.perform(get("/api/v1/customers/{c}/payments", ownerId)
                        .header("Authorization", "Bearer " + jwt.tokenFor(strangerId, "CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{c}/payments", 1L))
                .andExpect(status().isUnauthorized());
    }

    /** Запустить оплату здесь нельзя — это делает сервис заказов. */
    @Test
    void shouldNotAcceptPaymentRequests() throws Exception {
        long customerId = jwt.newUserId();

        mockMvc.perform(post("/api/v1/customers/{c}/orders/{o}/payments", customerId, 1L)
                        .header("Authorization", "Bearer " + jwt.tokenFor(customerId, "CUSTOMER"))
                        .contentType("application/json")
                        .content("{\"method\":\"CARD\",\"idempotencyKey\":\"k\"}"))
                .andExpect(status().isMethodNotAllowed());
    }

    private long paidOrder(long customerId) {
        long orderId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1;

        paymentService.handle(new PaymentCommand(
                UUID.randomUUID(),
                PaymentCommand.CURRENT_SCHEMA_VERSION,
                PaymentCommand.PAYMENT_REQUESTED,
                orderId,
                customerId,
                new BigDecimal("450.00"),
                "CARD",
                UUID.randomUUID().toString(),
                OffsetDateTime.now()
        ), KafkaTopics.PAYMENT_COMMANDS);

        return orderId;
    }
}
