package com.orderflow.notification.api;

import com.orderflow.notification.infrastructure.persistence.NotificationRepository;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;

import java.util.List;
import java.util.UUID;

@Controller("/api/v1/notifications")
@Secured("ADMIN")
public class NotificationController {

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Get
    @ExecuteOn(TaskExecutors.BLOCKING)
    public List<NotificationView> byOrder(@QueryValue UUID orderId) {
        return notifications.findByOrderIdOrderByCreatedAt(orderId).stream().map(NotificationView::from).toList();
    }
}
