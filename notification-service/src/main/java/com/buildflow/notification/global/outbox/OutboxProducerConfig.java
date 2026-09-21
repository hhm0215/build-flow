package com.buildflow.notification.global.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

@Configuration
public class OutboxProducerConfig {

    /** Preserve the exact JSON written with the business transaction on every retry. */
    @Bean("outboxKafkaTemplate")
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            KafkaProperties kafkaProperties, ObjectProvider<SslBundles> sslBundles) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(sslBundles.getIfAvailable());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(properties));
    }
}
