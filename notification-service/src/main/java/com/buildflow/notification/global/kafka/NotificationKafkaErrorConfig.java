package com.buildflow.notification.global.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.Map;

@Configuration
public class NotificationKafkaErrorConfig {

    @Bean
    public DefaultErrorHandler notificationKafkaErrorHandler(
            KafkaProperties kafkaProperties, ObjectProvider<SslBundles> sslBundles) {
        // DLT에는 파싱에 실패한 원본 문자열을 그대로 보존한다. 일반 이벤트 발행의
        // JSON 직렬화 KafkaTemplate과 분리해 문자열에 JSON 따옴표가 추가되지 않게 한다.
        Map<String, Object> producerProperties = kafkaProperties.buildProducerProperties(sslBundles.getIfAvailable());
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaTemplate<String, String> dltTemplate = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(producerProperties));

        DeadLetterPublishingRecoverer recoverer = createRecoverer(dltTemplate);

        // 최초 처리 + 재시도 2회. DLT 전송이 실패하면 recoverer가 예외를 던져
        // 원본 레코드의 offset이 성공 처리되지 않는다.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }

    static DeadLetterPublishingRecoverer createRecoverer(KafkaTemplate<String, String> dltTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dltTemplate, (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(10));
        return recoverer;
    }
}
