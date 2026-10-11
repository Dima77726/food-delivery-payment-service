package com.dima.fooddelivery.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * База для интеграционных тестов сервиса платежей: настоящие PostgreSQL и Kafka.
 * {@link Transactional} — откат после каждого теста.
 */
@SpringBootTest(properties = {
        // Публикацию outbox тесты запускают сами, когда им нужно.
        "app.outbox.publish-enabled=false",
        "logging.level.liquibase=WARN",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import(TestcontainersConfiguration.class)
@Transactional
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestJwt jwt;
}
