package com.orderflow.notification.config;

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
