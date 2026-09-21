package com.buildflow.notification.global.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class OutboxApplicationContextTest {

    @Test
    void contextLoadsWithOutboxDispatcherAndWarrantyScheduler() {
        // Bean wiring and the H2 schema are verified by context startup.
    }
}
