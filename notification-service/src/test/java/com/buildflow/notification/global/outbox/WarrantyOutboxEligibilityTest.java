package com.buildflow.notification.global.outbox;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** 오래된 만료 알림이 outbox를 떠나기 직전의 실패-닫힘 판정을 사유 코드별로 고정한다. */
class WarrantyOutboxEligibilityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private final ObjectMapper mapper = new ObjectMapper();
    private DefectWarranty warranty;
    private String eventId;

    @BeforeEach
    void setUp() {
        warranty = DefectWarranty.builder()
                .siteId(7L).insuranceCompany("서울보증보험")
                .startDate(TODAY.minusYears(2)).endDate(TODAY.plusDays(10))
                .build();
        ReflectionTestUtils.setField(warranty, "id", 42L);
        eventId = UUID.randomUUID().toString();
    }

    private ObjectNode envelope() {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", eventId);
        root.put("eventType", "WARRANTY_EXPIRING");
        ObjectNode payload = root.putObject("payload");
        payload.put("warrantyId", 42);
        payload.put("siteId", 7);
        payload.put("insuranceCompany", "서울보증보험");
        payload.put("endDate", TODAY.plusDays(10).toString());
        payload.put("daysUntilExpiry", 10);
        payload.put("expiryConfirmationRef", warranty.getExpiryConfirmedAt().toString());
        return root;
    }

    private String reason(Consumer<ObjectNode> change) {
        return reason(change, "42", warranty.getExpiryConfirmedAt().plusSeconds(1));
    }

    private String reason(Consumer<ObjectNode> change, String recordKey, Instant createdAt) {
        ObjectNode root = envelope();
        change.accept(root);
        OutboxEvent event = new OutboxEvent(eventId, "warranty.expiring", recordKey, root.toString(), createdAt);
        return WarrantyOutboxEligibility.rejectionReason(event, warranty, TODAY, 30, mapper);
    }

    private static ObjectNode payload(ObjectNode root) {
        return (ObjectNode) root.get("payload");
    }

    @Test
    void 확인된_수기_보증의_기간_안_알림은_통과한다() {
        assertThat(reason(root -> { })).isNull();
    }

    @Test
    void 봉투나_본문이_틀리면_MALFORMED로_막는다() {
        assertThat(reason(root -> root.put("eventType", "OTHER"))).isEqualTo("MALFORMED_WARRANTY_EVENT");
        assertThat(reason(root -> root.put("eventId", UUID.randomUUID().toString()))).isEqualTo("MALFORMED_WARRANTY_EVENT");
        assertThat(reason(root -> payload(root).put("endDate", "2026-13-01"))).isEqualTo("MALFORMED_WARRANTY_EVENT");
        assertThat(reason(root -> payload(root).put("siteId", 0))).isEqualTo("MALFORMED_WARRANTY_EVENT");
        assertThat(reason(root -> payload(root).put("daysUntilExpiry", -1))).isEqualTo("MALFORMED_WARRANTY_EVENT");
        assertThat(reason(root -> payload(root).remove("insuranceCompany"))).isEqualTo("MALFORMED_WARRANTY_EVENT");
        assertThat(reason(root -> { }, "43", warranty.getExpiryConfirmedAt().plusSeconds(1)))
                .isEqualTo("MALFORMED_WARRANTY_EVENT");
    }

    @Test
    void 보증이_지워졌거나_미확정이면_막는다() {
        OutboxEvent event = new OutboxEvent(eventId, "warranty.expiring", "42", envelope().toString(),
                warranty.getExpiryConfirmedAt().plusSeconds(1));
        assertThat(WarrantyOutboxEligibility.rejectionReason(event, null, TODAY, 30, mapper))
                .isEqualTo("WARRANTY_REMOVED");

        ReflectionTestUtils.setField(warranty, "expiryConfirmedAt", null);
        assertThat(WarrantyOutboxEligibility.rejectionReason(event, warranty, TODAY, 30, mapper))
                .isEqualTo("WARRANTY_UNCONFIRMED");
    }

    @Test
    void 재확인_이전에_만든_알림은_CONFIRMATION_CHANGED로_막는다() {
        assertThat(reason(root -> payload(root).put("expiryConfirmationRef", "2026-01-01T00:00:00Z")))
                .isEqualTo("WARRANTY_CONFIRMATION_CHANGED");
        assertThat(reason(root -> { }, "42", warranty.getExpiryConfirmedAt().minusSeconds(1)))
                .isEqualTo("WARRANTY_CONFIRMATION_CHANGED");
    }

    @Test
    void 알림_이후_보증_사실이_바뀌면_FACTS_CHANGED로_막는다() {
        assertThat(reason(root -> payload(root).put("insuranceCompany", "삼성화재"))).isEqualTo("WARRANTY_FACTS_CHANGED");
        assertThat(reason(root -> payload(root).put("siteId", 8))).isEqualTo("WARRANTY_FACTS_CHANGED");
        assertThat(reason(root -> payload(root).put("endDate", TODAY.plusDays(11).toString())))
                .isEqualTo("WARRANTY_FACTS_CHANGED");
    }

    @Test
    void 만료일이_알림_기간_밖이면_막는다() {
        for (LocalDate end : new LocalDate[]{TODAY.minusDays(1), TODAY.plusDays(31)}) {
            ReflectionTestUtils.setField(warranty, "endDate", end);
            assertThat(reason(root -> payload(root).put("endDate", end.toString())))
                    .as(end.toString()).isEqualTo("WARRANTY_OUTSIDE_ALERT_WINDOW");
        }
        ReflectionTestUtils.setField(warranty, "endDate", TODAY);
        assertThat(reason(root -> payload(root).put("endDate", TODAY.toString()))).isNull();
        ReflectionTestUtils.setField(warranty, "endDate", TODAY.plusDays(30));
        assertThat(reason(root -> payload(root).put("endDate", TODAY.plusDays(30).toString()))).isNull();
    }
}
