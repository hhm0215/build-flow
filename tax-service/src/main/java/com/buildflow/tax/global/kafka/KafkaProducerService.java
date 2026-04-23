package com.buildflow.tax.global.kafka;

import com.buildflow.tax.domain.taxinvoice.event.TaxInvoiceRegisteredPayload;
import com.buildflow.tax.global.event.KafkaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_TAX_REGISTERED = "tax.registered";
    private static final String TOPIC_TAX_PAYMENT_CONFIRMED = "tax.payment.confirmed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendTaxRegistered(TaxInvoiceRegisteredPayload payload) {
        KafkaEvent<TaxInvoiceRegisteredPayload> event = KafkaEvent.of("TAX_REGISTERED", payload);
        kafkaTemplate.send(TOPIC_TAX_REGISTERED, String.valueOf(payload.getTaxInvoiceId()), event);
        log.info("Kafka 발행: {} taxInvoiceId={}, siteId={}", TOPIC_TAX_REGISTERED, payload.getTaxInvoiceId(), payload.getSiteId());
    }

    public void sendTaxPaymentConfirmed(TaxInvoiceRegisteredPayload payload) {
        KafkaEvent<TaxInvoiceRegisteredPayload> event = KafkaEvent.of("TAX_PAYMENT_CONFIRMED", payload);
        kafkaTemplate.send(TOPIC_TAX_PAYMENT_CONFIRMED, String.valueOf(payload.getTaxInvoiceId()), event);
        log.info("Kafka 발행: {} taxInvoiceId={}, siteId={}", TOPIC_TAX_PAYMENT_CONFIRMED, payload.getTaxInvoiceId(), payload.getSiteId());
    }
}
