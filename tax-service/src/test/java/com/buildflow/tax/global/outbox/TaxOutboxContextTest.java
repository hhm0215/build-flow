package com.buildflow.tax.global.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tax-outbox-context-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.outbox.poll-delay-ms=3600000",
        "spring.kafka.listener.auto-startup=false"
})
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TaxOutboxContextTest {

    private final OutboxDispatcher dispatcher;
    private final KafkaTemplate<String, String> kafkaTemplate;

    TaxOutboxContextTest(OutboxDispatcher dispatcher, KafkaTemplate<String, String> kafkaTemplate) {
        this.dispatcher = dispatcher;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Test
    void applicationWiresStringProducerAndScheduledDispatcher() {
        assertThat(dispatcher).isNotNull();
        assertThat(kafkaTemplate).isNotNull();
    }
}
