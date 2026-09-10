package com.orderflow.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import com.orderflow.inventory.lowstock.application.LowStockScanService;
import com.orderflow.inventory.lowstock.infrastructure.LowStockJob;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LowStockLockIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LockProvider provider;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledTasks;

    @Autowired
    private LowStockJob job;

    @MockitoBean
    private LowStockScanService scan;

    @Test
    void lowStockJobIsNotAutomaticallyScheduledInTests() {
        assertThat(scheduledTasks.getScheduledTasks()).noneMatch(task ->
            task.getTask().getRunnable().toString().equals(LowStockJob.class.getName() + ".run"));
    }

    @Test
    void directJobInvocationStillAcquiresDatabaseLock() {
        job.run();
        verify(scan).scan();
    }

    @Test
    void secondInstanceCannotAcquireHeldJobLock() {
        var otherInstance = new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("inventory.shedlock")
                .usingDbTime()
                .build());
        var lock = new LockConfiguration(Instant.now(), "low-stock-test-" + UUID.randomUUID(),
            Duration.ofMinutes(1), Duration.ZERO);

        var first = provider.lock(lock);
        assertThat(first).isPresent();
        try {
            assertThat(otherInstance.lock(lock)).isEmpty();
        } finally {
            first.orElseThrow().unlock();
        }

        var afterUnlock = otherInstance.lock(lock);
        assertThat(afterUnlock).isPresent();
        afterUnlock.orElseThrow().unlock();
    }
}
