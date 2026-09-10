package com.orderflow.inventory.lowstock.infrastructure;

import com.orderflow.inventory.lowstock.application.LowStockScanService;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LowStockJob {

    private final LowStockScanService service;

    public LowStockJob(LowStockScanService service) {
        this.service = service;
    }

    @Scheduled(cron = "${inventory.low-stock.cron:0 */5 * * * *}", zone = "UTC")
    @SchedulerLock(name = "inventoryLowStockScan", lockAtMostFor = "10m", lockAtLeastFor = "4m")
    public void run() {
        LockAssert.assertLocked();
        service.scan();
    }
}
