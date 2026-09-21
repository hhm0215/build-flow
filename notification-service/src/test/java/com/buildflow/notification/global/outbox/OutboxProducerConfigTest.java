package com.buildflow.notification.global.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class OutboxProducerConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void rawJsonProducerHasBoundedBlockingAndInheritsAcks() {
        KafkaProperties properties = new KafkaProperties();
        properties.getProducer().setAcks("all");
        ObjectProvider<SslBundles> bundles = mock(ObjectProvider.class);

        var template = new OutboxProducerConfig().outboxKafkaTemplate(properties, bundles);
        var factory = (DefaultKafkaProducerFactory<String, String>) template.getProducerFactory();
        var config = factory.getConfigurationProperties();

        assertEquals("all", config.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(StringSerializer.class, config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(StringSerializer.class, config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals(5000, config.get(ProducerConfig.MAX_BLOCK_MS_CONFIG));
    }
}
