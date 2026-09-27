package com.shivang.obd;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires 'dev' profile with live Postgres and Redis; enable once a Testcontainers harness exists")
class ObdApplicationTests {

	@Test
	void contextLoads() {
	}

}
