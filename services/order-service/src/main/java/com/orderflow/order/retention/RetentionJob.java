package com.orderflow.order.retention;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetentionJob {

    private final RetentionCleaner cleaner;

    public RetentionJob(RetentionCleaner cleaner) {
        this.cleaner = cleaner;
    }

    @Scheduled(cron = "${order.retention.cron}", zone = "UTC")
    @SchedulerLock(name = "order-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        LockAssert.assertLocked();
        cleaner.clean();
    }
}
