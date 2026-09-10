package com.orderflow.inventory;

import com.orderflow.inventory.lowstock.application.LowStockScanService;
import com.orderflow.inventory.lowstock.infrastructure.LowStockJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "inventory.low-stock.cron=0 */5 * * * *")
@Import(PostgresTestConfiguration.class)
class LowStockJobSchedulingIntegrationTest {

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledTasks;

    @MockitoBean
    private LowStockScanService scan;

    @Test
    void productionCronRegistersTheLowStockJob() {
        assertThat(scheduledTasks.getScheduledTasks()).anyMatch(task ->
            task.getTask().getRunnable().toString().equals(LowStockJob.class.getName() + ".run"));
    }
}
