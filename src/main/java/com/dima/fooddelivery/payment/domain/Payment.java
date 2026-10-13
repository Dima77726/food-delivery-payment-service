package com.dima.fooddelivery.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Платёж по заказу. Сущность Hibernate.
 *
 * <p>Как и {@code AppUser}, живёт без Spring Data: репозиторий написан руками поверх
 * {@code EntityManager}, а выборки собираются Criteria API.
 *
 * <p>Строка создаётся на каждую попытку оплаты, включая неудачные, — отсюда и статус FAILED
 * с причиной. Успешный платёж у заказа при этом может быть только один, за чем следит
 * частичный уникальный индекс {@code uq_payment_succeeded_order}.
 */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Заказ живёт в другом сервисе, поэтому связь — только по идентификатору,
    // без внешнего ключа: базы у сервисов разные.
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * {@code @Enumerated(STRING)}, а не по умолчанию ORDINAL.
     *
     * <p>С порядковыми номерами добавление нового статуса в середину перечисления молча
     * переименовало бы статусы у всех уже сохранённых платежей: PENDING стал бы SUCCEEDED.
     * В колонке лежит VARCHAR, а список допустимых значений закреплён constraint'ом
     * {@code chk_payment_status}.
     *
     * <p>Строковые значения совпадают с {@code name()} перечисления, поэтому
     * {@code PaymentStatus.getDbValue()} и {@code fromDbValue()} здесь больше не участвуют —
     * преобразование делает Hibernate.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PaymentMethod method;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * Платёж у внешнего провайдера (для Stripe — PaymentIntent). Нужен возврату: вернуть деньги
     * можно только по нему. {@code null}, если провайдер платёж не видел — наличные при
     * получении, заглушка шлюза, платежи до появления колонки.
     */
    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Payment(Long orderId, Long customerId, BigDecimal amount, PaymentMethod method, String idempotencyKey) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.method = method;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;
    }

    /**
     * Единственный способ сменить статус — и сеттера рядом нет намеренно.
     *
     * <p>Проверку допустимости перехода делает не этот метод, а репозиторий: там смена
     * статуса выполняется под блокировкой строки, потому что решение зависит от того,
     * что в базе, а не от того, что в памяти.
     */
    public void moveTo(PaymentStatus next, String failureReason) {
        this.status = next;
        this.failureReason = failureReason;
    }

    /**
     * Запоминает платёж у провайдера. Вызывается один раз — сразу после ответа шлюза, в той же
     * транзакции, что и смена статуса: ссылка и статус «оплачено» не могут разойтись.
     */
    public void attachProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    @PrePersist
    void onInsert() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Payment that && id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Payment.class.hashCode();
    }
}
