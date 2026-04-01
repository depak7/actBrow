package com.actbrow.actbrow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"actbrow.gemini.api-key=test-key",
	"actbrow.gemini.base-url=http://localhost:9999/v1beta",
	"spring.datasource.url=jdbc:h2:mem:actbrow-test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.h2.console.enabled=false"
})
class ActbrowApplicationTests {

	@Test
	void contextLoads() {
	}
}
