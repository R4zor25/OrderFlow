package com.orderflow.order.config;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaHealthIndicatorTest {

    private final Admin admin = mock(Admin.class);
    private final KafkaHealthIndicator indicator =
        new KafkaHealthIndicator(admin, new KafkaHealthProperties(Duration.ofSeconds(2)));

    @Test
    void reportsUpWhenTheClusterHasNodes() {
        cluster(KafkaFuture.completedFuture(List.of(new Node(1, "localhost", 9092))));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenDescribeFails() {
        var failed = new KafkaFutureImpl<Collection<Node>>();
        failed.completeExceptionally(new TimeoutException("no broker"));
        cluster(failed);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reusesTheSameAdminClientAcrossProbes() {
        cluster(KafkaFuture.completedFuture(List.of(new Node(1, "localhost", 9092))));

        indicator.health();
        indicator.health();

        verify(admin, times(2)).describeCluster(any(DescribeClusterOptions.class));
        verify(admin, never()).close(any(Duration.class));
        verify(admin, never()).close();
    }

    private void cluster(KafkaFuture<Collection<Node>> nodes) {
        var result = mock(DescribeClusterResult.class);
        when(result.nodes()).thenReturn(nodes);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(result);
    }
}
