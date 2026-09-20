package com.buildflow.notification.global.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class NotificationKafkaErrorConfigTest {

    private final KafkaTemplate<String, String> dltTemplate = mock(KafkaTemplate.class);

    @Test
    void dltPreservesOriginalMessageAndTopic() {
        String raw = "{\"eventId\":\"broken\"}";
        ConsumerRecord<String, String> source = new ConsumerRecord<>("estimate.parsed", 1, 12L, "7", raw);
        when(dltTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        DeadLetterPublishingRecoverer recoverer = NotificationKafkaErrorConfig.createRecoverer(dltTemplate);
        recoverer.accept(source, null, new IllegalArgumentException("bad event"));

        var captor = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(dltTemplate).send(captor.capture());
        ProducerRecord<?, ?> published = captor.getValue();
        assertThat(published.topic()).isEqualTo("estimate.parsed.DLT");
        assertThat(published.partition()).isNull();
        assertThat(published.key()).isEqualTo("7");
        assertThat(published.value()).isEqualTo(raw);
    }

    @Test
    void dltPublishFailurePropagatesSoSourceIsNotRecovered() {
        ConsumerRecord<String, String> source = new ConsumerRecord<>("estimate.parsed", 0, 12L, "7", "bad-json");
        when(dltTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        DeadLetterPublishingRecoverer recoverer = NotificationKafkaErrorConfig.createRecoverer(dltTemplate);

        assertThatThrownBy(() -> recoverer.accept(source, null, new IllegalArgumentException("bad event")))
                .isInstanceOf(RuntimeException.class);
    }
}
