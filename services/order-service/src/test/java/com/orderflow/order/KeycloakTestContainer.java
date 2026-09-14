package com.orderflow.order;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

public final class KeycloakTestContainer {

    public static final String CLIENT_SECRET = "test-client-secret";
    public static final String USER_PASSWORD = "test-password";

    private static final GenericContainer<?> CONTAINER =
        new GenericContainer<>("quay.io/keycloak/keycloak:26.8.0")
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "bootstrap")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "bootstrap")
            .withEnv("ORDER_SERVICE_CLIENT_SECRET", CLIENT_SECRET)
            .withEnv("DEMO_USER_PASSWORD", USER_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("../../infra/keycloak/orderflow-realm.json"),
                "/opt/keycloak/data/import/orderflow-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    private KeycloakTestContainer() {
    }

    public static synchronized String realmUrl() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8080) + "/realms/orderflow";
    }
}
