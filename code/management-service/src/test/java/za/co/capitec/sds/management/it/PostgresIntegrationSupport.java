package za.co.capitec.sds.management.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import za.co.capitec.sds.management.repository.DocumentRepository;
import za.co.capitec.sds.management.repository.OutboxEventRepository;

import javax.sql.DataSource;

/**
 * Shared Postgres + Flyway + JDBC wiring for repository/outbox integration tests.
 * Requires Docker (Testcontainers).
 */
@Testcontainers(disabledWithoutDocker = true)
abstract class PostgresIntegrationSupport {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("sds_it")
            .withUsername("test")
            .withPassword("test");

    protected DataSource dataSource;
    protected JdbcClient jdbc;
    protected DocumentRepository documentRepository;
    protected OutboxEventRepository outboxEventRepository;
    protected PlatformTransactionManager transactionManager;
    protected TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUpDatabase() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbc = JdbcClient.create(dataSource);
        documentRepository = new DocumentRepository(jdbc);
        outboxEventRepository = new OutboxEventRepository(jdbc);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDownDatabase() {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}
