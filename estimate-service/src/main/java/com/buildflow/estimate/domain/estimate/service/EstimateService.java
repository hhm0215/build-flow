package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.*;
import com.buildflow.estimate.domain.estimate.entity.Estimate;
import com.buildflow.estimate.domain.estimate.entity.EstimateItem;
import com.buildflow.estimate.domain.estimate.entity.EstimateKind;
import com.buildflow.estimate.domain.estimate.entity.EstimateStatus;
import com.buildflow.estimate.domain.estimate.event.EstimateParsedPayload;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import com.buildflow.estimate.global.kafka.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EstimateService {

    private static final BigDecimal MAX_QUANTITY = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999.99");

    private final EstimateRepository estimateRepository;
    private final KafkaProducerService kafkaProducerService;
    private final SiteReferenceValidator siteReferenceValidator;

    @Transactional
    public EstimateResponse create(EstimateCreateRequest request) {
        BigDecimal totalAmount = calculateTotal(request.getItems());
        EstimateKind kind = request.getKind() == null && request.getRevisionOfId() == null
                ? EstimateKind.ORIGINAL : request.getKind();
        Estimate source = resolveRelationSource(request.getSiteId(), kind, request.getRevisionOfId());
        siteReferenceValidator.requireExistingSite(request.getSiteId());

        // 수정본은 원 견적의 체인에 들어가고, 원본·추가공사는 자기 자신이 루트(NULL)다.
        Long chainRootId = kind == EstimateKind.REVISION ? source.effectiveChainRootId() : null;
        Estimate estimate = Estimate.builder()
                .siteId(request.getSiteId())
                .title(request.getTitle())
                .estimateDate(request.getEstimateDate())
                .totalAmount(totalAmount)
                .memo(request.getMemo())
                .kind(kind)
                .revisionOfId(source == null ? null : source.getId())
                .chainRootId(chainRootId)
                .build();

        addItems(estimate, request.getItems());
        estimateRepository.save(estimate);
        estimateRepository.flush();
        // 첫 검증 후 저장 사이에 현장이 보관됐으면 커밋 전에 롤백한다 (다른 DB라 잠금으로 직렬화 불가)
        siteReferenceValidator.recheckBeforeCommit(request.getSiteId());

        return EstimateResponse.from(estimate);
    }

    public List<EstimateResponse> findBySiteId(Long siteId) {
        return estimateRepository.findBySiteIdOrderByCreatedAtDesc(siteId)
                .stream()
                .map(EstimateResponse::from)
                .toList();
    }

    public List<EstimateResponse> findAll() {
        return estimateRepository.findAll()
                .stream()
                .map(EstimateResponse::from)
                .toList();
    }

    public EstimateResponse findById(Long id) {
        Estimate estimate = getEstimate(id);
        return EstimateResponse.from(estimate);
    }

    @Transactional
    public EstimateResponse update(Long id, EstimateUpdateRequest request) {
        Estimate estimate = getEstimateForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(estimate.getSiteId());
        requireDraft(estimate);

        BigDecimal totalAmount = calculateTotal(request.getItems());

        estimate.update(request.getTitle(), request.getEstimateDate(), totalAmount, request.getMemo());
        estimate.clearItems();
        addItems(estimate, request.getItems());
        estimateRepository.flush();
        // 첫 검사 직후 현장이 보관됐으면 변경을 커밋 전에 롤백한다 (ADR-021 보관 현장 문서 잠금)
        siteReferenceValidator.recheckNotArchivedBeforeCommit(estimate.getSiteId());

        return EstimateResponse.from(estimate);
    }

    @Transactional
    public void delete(Long id) {
        Estimate estimate = getEstimateForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(estimate.getSiteId());

        if (estimate.getStatus() == EstimateStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.CONFIRMED_ESTIMATE_DELETE_NOT_ALLOWED);
        }
        if (estimate.getStatus() == EstimateStatus.SUPERSEDED) {
            throw new BusinessException(ErrorCode.ESTIMATE_SUPERSEDED);
        }
        // 초안은 관계의 원 견적이 될 수 없으므로(resolveRelationSource) 초안 삭제가 체인을 끊지 않는다.
        estimateRepository.delete(estimate);
        estimateRepository.flush();
        siteReferenceValidator.recheckNotArchivedBeforeCommit(estimate.getSiteId());
    }

    /**
     * 수정 체인 단위로 잠근 뒤 확정한다. 같은 체인에서 손익에 반영되는 확정본은 최대 1개이며,
     * 기존 확정본은 SUPERSEDED로 남기고 estimate.deleted(reason=SUPERSEDED)로 손익에서 뺀다.
     * 잠금 순서는 항상 체인 루트 → 대상 → 체인의 확정본이라 같은 체인의 동시 확정이 직렬화된다.
     */
    @Transactional
    public EstimateResponse confirm(Long id) {
        Long chainRootId = estimateRepository.findChainRootId(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ESTIMATE_NOT_FOUND));
        Estimate root = getEstimateForUpdate(chainRootId);
        Estimate estimate = chainRootId.equals(id) ? root : getEstimateForUpdate(id);
        siteReferenceValidator.requireSiteNotArchived(estimate.getSiteId());
        requireDraft(estimate);

        List<Estimate> effective = new ArrayList<>();
        if (root.getStatus() == EstimateStatus.CONFIRMED) {
            effective.add(root);
        }
        effective.addAll(estimateRepository.findRevisionsByStatusForUpdate(chainRootId, EstimateStatus.CONFIRMED));
        for (Estimate previous : effective) {
            if (previous == estimate) {
                continue;
            }
            previous.supersede(estimate.getId());
            kafkaProducerService.sendEstimateDeleted(EstimateParsedPayload.builder()
                    .estimateId(previous.getId())
                    .siteId(previous.getSiteId())
                    .totalAmount(previous.getTotalAmount())
                    .reason(EstimateParsedPayload.REASON_SUPERSEDED)
                    .supersededById(estimate.getId())
                    .build());
        }

        estimate.confirm();

        kafkaProducerService.sendEstimateParsed(EstimateParsedPayload.builder()
                .estimateId(estimate.getId())
                .siteId(estimate.getSiteId())
                .totalAmount(estimate.getTotalAmount())
                .build());
        estimateRepository.flush();
        // 확정·기존 확정본 대체·손익 outbox가 모두 기록된 뒤 재확인해, 그 사이 보관됐으면 전부 롤백한다
        siteReferenceValidator.recheckNotArchivedBeforeCommit(estimate.getSiteId());

        return EstimateResponse.from(estimate);
    }

    private void requireDraft(Estimate estimate) {
        if (estimate.getStatus() == EstimateStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.ESTIMATE_ALREADY_CONFIRMED);
        }
        if (estimate.getStatus() == EstimateStatus.SUPERSEDED) {
            throw new BusinessException(ErrorCode.ESTIMATE_SUPERSEDED);
        }
    }

    /**
     * 관계 견적의 원 견적을 검증한다. 원 견적은 같은 현장의 확정 또는 대체된 견적이어야 한다.
     * 초안을 원 견적으로 허용하지 않아 초안 삭제·수정이 다른 견적의 관계를 깨지 않는다.
     */
    private Estimate resolveRelationSource(Long siteId, EstimateKind kind, Long revisionOfId) {
        if (kind == null) {
            throw new BusinessException(ErrorCode.INVALID_ESTIMATE_RELATION);
        }
        if (kind == EstimateKind.ORIGINAL) {
            if (revisionOfId != null) {
                throw new BusinessException(ErrorCode.INVALID_ESTIMATE_RELATION);
            }
            return null;
        }
        if (revisionOfId == null) {
            throw new BusinessException(ErrorCode.INVALID_ESTIMATE_RELATION);
        }
        Estimate source = estimateRepository.findById(revisionOfId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ESTIMATE_RELATION));
        if (!source.getSiteId().equals(siteId) || source.getStatus() == EstimateStatus.DRAFT) {
            throw new BusinessException(ErrorCode.INVALID_ESTIMATE_RELATION);
        }
        return source;
    }

    private Estimate getEstimate(Long id) {
        return estimateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ESTIMATE_NOT_FOUND));
    }

    private Estimate getEstimateForUpdate(Long id) {
        return estimateRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ESTIMATE_NOT_FOUND));
    }

    private BigDecimal calculateTotal(List<EstimateItemRequest> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (EstimateItemRequest item : items) {
            BigDecimal quantity = item.getQuantity();
            BigDecimal unitPrice = item.getUnitPrice();
            if (quantity == null || unitPrice == null || quantity.signum() <= 0
                    || unitPrice.signum() < 0 || quantity.compareTo(MAX_QUANTITY) > 0
                    || unitPrice.compareTo(MAX_AMOUNT) > 0) {
                throw new BusinessException(ErrorCode.INVALID_ESTIMATE_AMOUNT);
            }
            try {
                quantity.setScale(2, RoundingMode.UNNECESSARY);
                unitPrice.setScale(2, RoundingMode.UNNECESSARY);
                BigDecimal amount = quantity.multiply(unitPrice).setScale(2, RoundingMode.UNNECESSARY);
                total = total.add(amount);
                if (total.compareTo(MAX_AMOUNT) > 0) {
                    throw new BusinessException(ErrorCode.INVALID_ESTIMATE_AMOUNT);
                }
            } catch (ArithmeticException ignored) {
                throw new BusinessException(ErrorCode.INVALID_ESTIMATE_AMOUNT);
            }
        }
        return total;
    }

    private void addItems(Estimate estimate, List<EstimateItemRequest> itemRequests) {
        itemRequests.forEach(req -> {
            BigDecimal amount = req.getUnitPrice().multiply(req.getQuantity());
            EstimateItem item = EstimateItem.builder()
                    .estimate(estimate)
                    .itemName(req.getItemName())
                    .unit(req.getUnit())
                    .quantity(req.getQuantity())
                    .unitPrice(req.getUnitPrice())
                    .amount(amount)
                    .build();
            estimate.addItem(item);
        });
    }
}
