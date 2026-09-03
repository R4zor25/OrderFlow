package com.orderflow.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OrderServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
