package com.buildflow.site.domain.profit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "processed_profit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedProfitEvent {

    @Id
    @Column(length = 36, nullable = false)
    private String eventId;

    @Column(nullable = false)
    private Long siteId;

    @Column(nullable = false, length = 40)
    private String eventType;

    public ProcessedProfitEvent(String eventId, Long siteId, String eventType) {
        this.eventId = eventId;
        this.siteId = siteId;
        this.eventType = eventType;
    }
}
