package com.buildflow.site.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class KafkaConsumerContextTest {

    private final CommonErrorHandler errorHandler;

    KafkaConsumerContextTest(CommonErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }

    @Test
    void applicationStartsWithDeadLetterErrorHandler() {
        assertNotNull(errorHandler);
    }
}
