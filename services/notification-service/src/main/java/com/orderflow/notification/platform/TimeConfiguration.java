package com.orderflow.notification.platform;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

@Factory
public final class TimeConfiguration {

    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
