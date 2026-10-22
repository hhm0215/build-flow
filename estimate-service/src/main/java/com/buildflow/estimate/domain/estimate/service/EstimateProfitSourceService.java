package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.EstimateProfitSourceResponse;
import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import com.buildflow.estimate.global.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 손익 재계산 원천. 확정 견적 합계와 손익 이벤트 목록을 하나의 REPEATABLE READ 스냅샷에서 읽어
 * "합계에 반영된 이벤트 = 목록의 이벤트"가 되도록 한다. 견적 확정·대체는 상태 변경과 outbox 적재가
 * 같은 트랜잭션이므로 스냅샷에 보이는 이벤트는 합계에도 반영되어 있다.
 */
@Service
@RequiredArgsConstructor
public class EstimateProfitSourceService {

    static final Map<String, String> PROFIT_TOPICS = Map.of(
            "estimate.parsed", "ESTIMATE_PARSED",
            "estimate.deleted", "ESTIMATE_DELETED");

    private final EstimateRepository estimateRepository;
    private final OutboxEventRepository outboxEventRepository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EstimateProfitSourceResponse snapshot(Long siteId) {
        if (siteId == null || siteId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_SITE_REFERENCE);
        }
        List<Estimate> confirmed = estimateRepository.findBySiteIdAndStatus(siteId, EstimateStatus.CONFIRMED);
        BigDecimal total = confirmed.stream()
                .map(Estimate::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 확정 견적은 삭제할 수 없고 초안 삭제는 손익 이벤트를 만들지 않으므로,
        // 손익 이벤트가 있는 견적은 모두 현재 행으로 남아 있다(record_key = estimateId).
        List<String> recordKeys = estimateRepository.findIdsBySiteId(siteId).stream()
                .map(String::valueOf)
                .toList();
        List<EstimateProfitSourceResponse.ProfitEvent> events = recordKeys.isEmpty()
                ? List.of()
                : outboxEventRepository.findByTopicInAndRecordKeyIn(PROFIT_TOPICS.keySet(), recordKeys)
                        .stream()
                        .map(event -> EstimateProfitSourceResponse.ProfitEvent.builder()
                                .eventId(event.getEventId())
                                .eventType(PROFIT_TOPICS.get(event.getTopic()))
                                .build())
                        .toList();

        return EstimateProfitSourceResponse.builder()
                .siteId(siteId)
                .confirmedTotalAmount(total)
                .confirmedEstimateCount(confirmed.size())
                .profitEvents(events)
                .build();
    }
}
