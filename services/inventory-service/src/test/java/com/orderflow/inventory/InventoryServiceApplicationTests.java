package com.orderflow.inventory;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class InventoryServiceApplicationTests {

    private final Flyway flyway;
    private final Environment environment;

    @Autowired
    InventoryServiceApplicationTests(Flyway flyway, Environment environment) {
        this.flyway = flyway;
        this.environment = environment;
    }

    @Test
    void startsWithInventorySchemaOnPort8083() {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("inventory");
        assertThat(environment.getProperty("server.port")).isEqualTo("8083");
    }

}
