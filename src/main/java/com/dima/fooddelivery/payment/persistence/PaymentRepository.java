package com.dima.fooddelivery.payment.persistence;

import com.dima.fooddelivery.payment.domain.Payment;
import com.dima.fooddelivery.payment.domain.PaymentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Репозиторий платежей на голом Hibernate. Выборки собираются Criteria API.
 *
 * <p>Criteria взята не для красоты. Пять прежних методов чтения отличались только набором
 * условий и порядком сортировки, а SQL в каждом был выписан целиком — пять почти одинаковых
 * запросов, которые предстояло править синхронно. Здесь остался один сборщик
 * {@link #search(PaymentSearch)}, а именованные методы задают ему условия.
 *
 * <p>Тем же самым занимается и конкатенация строк с SQL, но у Criteria есть преимущество,
 * ради которого она и существует: запрос собирается из объектов, а не из текста. Забытый
 * пробел перед WHERE или лишняя запятая при пустом наборе фильтров здесь невозможны
 * в принципе — их просто нечем написать.
 *
 * <p>Слабое место видно ниже: поля адресуются строками — {@code payment.get("orderId")}.
 * Опечатка станет ошибкой в рантайме, а не при компиляции. Лечится это генератором
 * метамодели (hibernate-jpamodelgen), который создаёт класс {@code Payment_} с константами
 * полей, и тогда пишется {@code payment.get(Payment_.orderId)}. Здесь он не подключён
 * намеренно: версия этого артефакта не управляется BOM'ом Spring Boot, её пришлось бы
 * задавать вручную и следить, чтобы она не разъехалась с версией hibernate-core.
 */
@Repository
@RequiredArgsConstructor
public class PaymentRepository {

    private final EntityManager entityManager;

    /**
     * Набор условий поиска. {@code null} в поле означает «не фильтровать по нему».
     *
     * @param newestFirst порядок выдачи: история клиента читается от свежих к старым,
     *                    история одного заказа — в хронологическом порядке
     */
    public record PaymentSearch(
            Long orderId,
            Long customerId,
            String idempotencyKey,
            PaymentStatus status,
            boolean newestFirst
    ) {

        public static PaymentSearch byOrder(Long orderId) {
            return new PaymentSearch(orderId, null, null, null, false);
        }

        public static PaymentSearch byCustomer(Long customerId) {
            return new PaymentSearch(null, customerId, null, null, true);
        }

        public static PaymentSearch byIdempotencyKey(String idempotencyKey) {
            return new PaymentSearch(null, null, idempotencyKey, null, false);
        }

        public static PaymentSearch succeededByOrder(Long orderId) {
            return new PaymentSearch(orderId, null, null, PaymentStatus.SUCCEEDED, false);
        }
    }

    /**
     * Поиск по первичному ключу идёт через {@code find}, а не через Criteria: это
     * единственная выборка, которая может вернуться из персистентного контекста вообще
     * без запроса к базе, если сущность уже загружена в этой транзакции.
     */
    public Optional<Payment> findById(Long paymentId) {
        return Optional.ofNullable(entityManager.find(Payment.class, paymentId));
    }

    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return search(PaymentSearch.byIdempotencyKey(idempotencyKey)).stream().findFirst();
    }

    public Optional<Payment> findSucceededByOrderId(Long orderId) {
        return search(PaymentSearch.succeededByOrder(orderId)).stream().findFirst();
    }

    public List<Payment> findByOrderId(Long orderId) {
        return search(PaymentSearch.byOrder(orderId));
    }

    public List<Payment> findByCustomerId(Long customerId) {
        return search(PaymentSearch.byCustomer(customerId));
    }

    public Payment save(Payment payment) {
        entityManager.persist(payment);

        return payment;
    }

    public void flush() {
        entityManager.flush();
    }

    /**
     * Переводит платёж из ожидаемого статуса в следующий. Возвращает {@code false}, если
     * платёж уже находится в другом статусе — значит, кто-то опередил.
     *
     * <p>Это единственное место в модуле, где грязная проверка не годится. Hibernate строит
     * UPDATE по первичному ключу и только по нему: условие {@code AND status = :expected}
     * в такой запрос попасть не может. Достаточно двух одновременных отмен заказа — и обе
     * прочитали бы статус SUCCEEDED, обе вызвали бы возврат, и деньги вернулись бы дважды.
     *
     * <p>Поэтому здесь массовое обновление (bulk update) — запрос, написанный целиком, как
     * в JDBC-версии, только на JPQL вместо SQL. Число изменённых строк и есть ответ:
     * ноль означает, что условие по статусу не выполнилось.
     *
     * <p>У массового обновления есть особенность, о которой легко забыть и получить
     * труднообъяснимую ошибку: <b>оно идёт мимо персистентного контекста</b>. Отсюда две
     * предосторожности вокруг запроса.
     *
     * <p>Первая — {@code flush} до него. Накопленные, но не отправленные изменения этой же
     * сущности иначе были бы записаны уже после массового обновления и затёрли бы его.
     *
     * <p>Вторая — {@code refresh} после. В памяти сущность продолжает хранить прежний статус,
     * и следующее чтение вернуло бы её из контекста, не заглядывая в базу: платёж уже
     * SUCCEEDED в базе, но PENDING в ответе клиенту. По той же причине {@code updatedAt}
     * проставляется руками — обработчик {@code @PreUpdate} при массовом обновлении
     * не вызывается, Hibernate о таком изменении просто не знает.
     */
    public boolean compareAndSetStatus(
            Long paymentId,
            PaymentStatus expected,
            PaymentStatus next,
            String failureReason
    ) {
        entityManager.flush();

        int updated = entityManager.createQuery("""
                        UPDATE Payment p
                        SET p.status = :next,
                            p.failureReason = :failureReason,
                            p.updatedAt = :now
                        WHERE p.id = :paymentId
                          AND p.status = :expected
                        """)
                .setParameter("next", next)
                .setParameter("failureReason", failureReason)
                .setParameter("now", OffsetDateTime.now())
                .setParameter("paymentId", paymentId)
                .setParameter("expected", expected)
                .executeUpdate();

        if (updated == 0) {
            return false;
        }

        Payment stale = entityManager.find(Payment.class, paymentId);

        if (stale != null) {
            entityManager.refresh(stale);
        }

        return true;
    }

    private List<Payment> search(PaymentSearch criteria) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Payment> query = builder.createQuery(Payment.class);
        Root<Payment> payment = query.from(Payment.class);

        List<Predicate> conditions = new ArrayList<>();

        if (criteria.orderId() != null) {
            conditions.add(builder.equal(payment.get("orderId"), criteria.orderId()));
        }

        if (criteria.customerId() != null) {
            conditions.add(builder.equal(payment.get("customerId"), criteria.customerId()));
        }

        if (criteria.idempotencyKey() != null) {
            conditions.add(builder.equal(payment.get("idempotencyKey"), criteria.idempotencyKey()));
        }

        if (criteria.status() != null) {
            conditions.add(builder.equal(payment.get("status"), criteria.status()));
        }

        // Пустой массив условий — законное состояние: where() без предикатов означает
        // выборку без фильтра, а не сломанный SQL.
        query.where(conditions.toArray(Predicate[]::new));
        query.orderBy(orderBy(builder, payment, criteria.newestFirst()));

        return entityManager.createQuery(query).getResultList();
    }

    /**
     * Сортировка всегда по паре полей. Одного {@code created_at} мало: два платежа могут
     * попасть в одну миллисекунду, и тогда порядок между ними стал бы случайным —
     * страница выдачи меняла бы состав при каждом обращении.
     */
    private List<Order> orderBy(CriteriaBuilder builder, Root<Payment> payment, boolean newestFirst) {
        return newestFirst
                ? List.of(builder.desc(payment.get("createdAt")), builder.desc(payment.get("id")))
                : List.of(builder.asc(payment.get("createdAt")), builder.asc(payment.get("id")));
    }
}
