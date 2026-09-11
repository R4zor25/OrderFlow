package com.orderflow.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "management.health.redis.enabled=false")
@Import(PostgresTestConfiguration.class)
class OrderServiceApplicationTests {

    @Autowired
    private HealthEndpointGroups groups;

    @Autowired
    private ApplicationContext context;

    @Test
    void baseProbesStartWithoutRedisOrMessagingContributors() {
        assertThat(groups.get("liveness").isMember("livenessState")).isTrue();
        assertThat(groups.get("liveness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("db")).isTrue();
        assertThat(groups.get("readiness").isMember("redis")).isFalse();
        assertThat(groups.get("readiness").isMember("kafka")).isFalse();
        assertThat(context.containsBean("kafkaHealthIndicator")).isFalse();
        assertThat(context.containsBean("redisHealthContributor")).isFalse();
    }
}
