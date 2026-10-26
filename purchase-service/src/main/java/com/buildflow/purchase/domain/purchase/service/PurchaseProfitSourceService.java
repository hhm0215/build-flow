package com.buildflow.purchase.domain.purchase.service;

import com.buildflow.purchase.domain.purchase.dto.PurchaseProfitSourceResponse;
import com.buildflow.purchase.domain.purchase.entity.Purchase;
import com.buildflow.purchase.domain.purchase.repository.PurchaseRepository;
import com.buildflow.purchase.global.exception.BusinessException;
import com.buildflow.purchase.global.exception.ErrorCode;
import com.buildflow.purchase.global.outbox.OutboxEvent;
import com.buildflow.purchase.global.outbox.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 손익 재계산 원천: 현재 매입과 삭제된 매입의 이벤트까지 같은 읽기 스냅샷으로 반환한다. */
@Service
@RequiredArgsConstructor
public class PurchaseProfitSourceService {

    private static final Map<String, String> PROFIT_TOPICS = Map.of(
            "purchase.registered", "PURCHASE_REGISTERED",
            "purchase.updated", "PURCHASE_UPDATED",
            "purchase.deleted", "PURCHASE_DELETED");

    private final PurchaseRepository purchaseRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PurchaseProfitSourceResponse snapshot(Long siteId) {
        if (siteId == null || siteId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_SITE_REFERENCE);
        }
        List<Purchase> purchases = purchaseRepository.findBySiteIdOrderByIdAsc(siteId);
        BigDecimal total = purchases.stream()
                .map(Purchase::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return PurchaseProfitSourceResponse.builder()
                .siteId(siteId)
                .totalAmount(total)
                .profitEvents(profitEvents(siteId))
                .purchases(purchases.stream()
                        .map(purchase -> PurchaseProfitSourceResponse.Item.builder()
                                .purchaseId(purchase.getId())
                                .revision(purchase.getEventRevision())
                                .totalAmount(purchase.getTotalAmount())
                                .build())
                        .toList())
                .build();
    }

    private List<PurchaseProfitSourceResponse.ProfitEvent> profitEvents(Long siteId) {
        List<PurchaseProfitSourceResponse.ProfitEvent> result = new ArrayList<>();
        for (OutboxEvent event : outboxEventRepository.findProfitEvents(PROFIT_TOPICS.keySet())) {
            JsonNode envelope;
            try {
                envelope = objectMapper.readTree(event.getPayloadJson());
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw unavailable();
            }
            if (envelope == null || !envelope.isObject()) {
                throw unavailable();
            }
            JsonNode payload = envelope.path("payload");
            String expectedType = PROFIT_TOPICS.get(event.getTopic());
            if (!validEventId(event.getEventId())
                    || !envelope.path("eventId").isTextual()
                    || !event.getEventId().equals(envelope.path("eventId").textValue())
                    || expectedType == null || !envelope.path("eventType").isTextual()
                    || !expectedType.equals(envelope.path("eventType").textValue())
                    || !payload.isObject() || !positiveLong(payload.path("siteId"))
                    || !positiveLong(payload.path("purchaseId"))
                    || !Long.toString(payload.path("purchaseId").longValue()).equals(event.getRecordKey())) {
                throw unavailable();
            }
            if (payload.path("siteId").longValue() == siteId.longValue()) {
                result.add(PurchaseProfitSourceResponse.ProfitEvent.builder()
                        .eventId(event.getEventId())
                        .eventType(expectedType)
                        .build());
            }
        }
        return result;
    }

    private boolean positiveLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0;
    }

    private boolean validEventId(String value) {
        try {
            return value != null && value.equals(UUID.fromString(value).toString());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private BusinessException unavailable() {
        // 원천 payload나 파싱 예외 메시지는 응답·로그에 노출하지 않는다.
        return new BusinessException(ErrorCode.PROFIT_SOURCE_UNAVAILABLE);
    }
}
