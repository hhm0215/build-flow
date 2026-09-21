package com.buildflow.tax.global.kafka;

import com.buildflow.tax.domain.taxinvoice.event.TaxInvoiceRegisteredPayload;
import com.buildflow.tax.global.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private static final String TOPIC_TAX_REGISTERED = "tax.registered";
    private static final String TOPIC_TAX_PAYMENT_CONFIRMED = "tax.payment.confirmed";

    private final OutboxWriter outboxWriter;

    public void sendTaxRegistered(TaxInvoiceRegisteredPayload payload) {
        outboxWriter.enqueue(TOPIC_TAX_REGISTERED, String.valueOf(payload.getTaxInvoiceId()),
                "TAX_REGISTERED", payload);
    }

    public void sendTaxPaymentConfirmed(TaxInvoiceRegisteredPayload payload) {
        outboxWriter.enqueue(TOPIC_TAX_PAYMENT_CONFIRMED, String.valueOf(payload.getTaxInvoiceId()),
                "TAX_PAYMENT_CONFIRMED", payload);
    }
}
