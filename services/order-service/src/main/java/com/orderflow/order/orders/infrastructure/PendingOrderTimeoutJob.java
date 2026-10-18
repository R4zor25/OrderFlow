package com.orderflow.order.orders.infrastructure;

import com.orderflow.order.orders.application.PendingOrderTimeoutService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PendingOrderTimeoutJob {

    private final PendingOrderTimeoutService timeouts;

    public PendingOrderTimeoutJob(PendingOrderTimeoutService timeouts) {
        this.timeouts = timeouts;
    }

    @Scheduled(cron = "${order.saga.timeout-sweep-cron}", zone = "UTC")
    @SchedulerLock(name = "pending-order-timeout", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void run() {
        timeouts.cancelExpired();
    }
}
