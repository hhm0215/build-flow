package com.buildflow.site.global.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaErrorHandlingConfigTest {

    private final KafkaErrorHandlingConfig config = new KafkaErrorHandlingConfig();

    @Test
    void deadLetterProducerKeepsConfiguredBrokerAndSecuritySettings() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("broker.internal:9092"));
        properties.getProperties().put("security.protocol", "SASL_SSL");
        @SuppressWarnings("unchecked")
        ObjectProvider<SslBundles> bundles = mock(ObjectProvider.class);

        Map<String, Object> producerProperties = config.deadLetterProducerFactory(properties, bundles)
                .getConfigurationProperties();

        assertEquals(List.of("broker.internal:9092"), producerProperties.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("SASL_SSL", producerProperties.get("security.protocol"));
        assertEquals(StringSerializer.class, producerProperties.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
    }

    @Test
    void failedDeadLetterPublishDoesNotMarkSourceRecordRecovered() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        ProducerFactory<String, String> factory = mock(ProducerFactory.class);
        when(template.getProducerFactory()).thenReturn(factory);
        when(factory.getConfigurationProperties()).thenReturn(Map.of(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000));
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        DefaultErrorHandler handler = (DefaultErrorHandler) config.kafkaErrorHandler(template);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "estimate.parsed", 0, 7L, "1", "original-json");

        @SuppressWarnings("unchecked")
        Consumer<String, String> consumer = mock(Consumer.class);
        boolean recovered = handler.handleOne(new IllegalArgumentException("bad event"),
                record, consumer, mock(MessageListenerContainer.class));

        assertFalse(recovered);
        verify(template).send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any());
    }

    @Test
    void successfulDeadLetterPublishCanCompleteRecovery() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        ProducerFactory<String, String> factory = mock(ProducerFactory.class);
        when(template.getProducerFactory()).thenReturn(factory);
        when(factory.getConfigurationProperties()).thenReturn(Map.of(
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000));
        @SuppressWarnings("unchecked")
        SendResult<String, String> result = mock(SendResult.class);
        when(template.send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(result));

        DefaultErrorHandler handler = (DefaultErrorHandler) config.kafkaErrorHandler(template);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "estimate.parsed", 0, 7L, "1", "original-json");
        @SuppressWarnings("unchecked")
        Consumer<String, String> consumer = mock(Consumer.class);

        assertTrue(handler.handleOne(new IllegalArgumentException("bad event"),
                record, consumer, mock(MessageListenerContainer.class)));
        verify(template).send(org.mockito.ArgumentMatchers.<ProducerRecord<String, String>>argThat(
                sent -> "estimate.parsed.DLT".equals(sent.topic())
                        && "original-json".equals(sent.value())));
    }
}
