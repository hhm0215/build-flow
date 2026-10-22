package com.buildflow.site.domain.site.service;

import com.buildflow.site.domain.client.entity.Client;
import com.buildflow.site.domain.client.repository.ClientRepository;
import com.buildflow.site.domain.site.dto.SiteCreateRequest;
import com.buildflow.site.domain.site.dto.SiteResponse;
import com.buildflow.site.domain.site.dto.SiteStatusUpdateRequest;
import com.buildflow.site.domain.site.dto.SiteUpdateRequest;
import com.buildflow.site.domain.site.entity.Site;
import com.buildflow.site.domain.site.entity.SiteStatus;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SiteService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final SiteRepository siteRepository;
    private final ClientRepository clientRepository;

    @Transactional
    public SiteResponse create(SiteCreateRequest request) {
        Client client = resolveAssignableClient(request.getClientId(), null);
        if (request.getStatus() != null && !request.getStatus().isInitial()) {
            throw new BusinessException(ErrorCode.SITE_INITIAL_STATUS_NOT_ALLOWED);
        }

        Site site = Site.builder()
                .status(request.getStatus())
                .siteName(request.getSiteName())
                .client(client)
                .address(request.getAddress())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .memo(request.getMemo())
                .build();

        return SiteResponse.from(siteRepository.save(site));
    }

    /** archived가 null이면 보관 여부와 무관하게 전체를 반환한다(기존 계약). */
    public List<SiteResponse> findAll(SiteStatus status, Boolean archived) {
        List<Site> sites = (status != null)
                ? siteRepository.findByStatusOrderByCreatedAtDesc(status)
                : siteRepository.findAllByOrderByCreatedAtDesc();

        return sites.stream()
                .filter(site -> archived == null || site.isArchived() == archived)
                .map(SiteResponse::from)
                .toList();
    }

    public SiteResponse findById(Long id) {
        return SiteResponse.from(getSite(id));
    }

    @Transactional
    public SiteResponse update(Long id, SiteUpdateRequest request) {
        Site site = getSiteForUpdate(id);
        requireNotArchived(site);
        Client client = resolveAssignableClient(request.getClientId(), site.getClient());

        site.update(
                request.getSiteName(),
                client,
                request.getAddress(),
                request.getStartDate(),
                request.getEndDate(),
                request.getMemo()
        );

        return SiteResponse.from(site);
    }

    @Transactional
    public SiteResponse changeStatus(Long id, SiteStatusUpdateRequest request) {
        Site site = getSiteForUpdate(id);
        requireNotArchived(site);
        if (!site.getStatus().canTransitionTo(request.getStatus())) {
            throw new BusinessException(ErrorCode.SITE_STATUS_TRANSITION_NOT_ALLOWED);
        }
        site.changeStatus(request.getStatus());
        return SiteResponse.from(site);
    }

    @Transactional
    public SiteResponse archive(Long id) {
        Site site = getSiteForUpdate(id);
        // 응답과 DB 재조회 값이 정밀도 차이로 달라지지 않도록 초 단위로 저장
        site.archive(LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS));
        return SiteResponse.from(site);
    }

    @Transactional
    public SiteResponse restore(Long id) {
        Site site = getSiteForUpdate(id);
        site.restore();
        return SiteResponse.from(site);
    }

    public void delete(Long id) {
        getSite(id);
        throw new BusinessException(ErrorCode.SITE_DELETION_DISABLED);
    }

    private Site getSite(Long id) {
        return siteRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
    }

    private Site getSiteForUpdate(Long id) {
        return siteRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
    }

    private void requireNotArchived(Site site) {
        if (site.isArchived()) {
            throw new BusinessException(ErrorCode.SITE_ARCHIVED);
        }
    }

    /** 보관된 거래처는 새로 연결할 수 없다. 이미 연결된 거래처를 유지하는 수정은 허용한다. */
    private Client resolveAssignableClient(Long clientId, Client current) {
        Client client = resolveClient(clientId);
        boolean unchanged = client != null && current != null && client.getId().equals(current.getId());
        if (client != null && client.isArchived() && !unchanged) {
            throw new BusinessException(ErrorCode.CLIENT_ARCHIVED_NOT_ASSIGNABLE);
        }
        return client;
    }

    private Client resolveClient(Long clientId) {
        if (clientId == null) {
            return null;
        }
        // 거래처 보관과 새 현장 연결이 같은 행 잠금을 공유해야 검사 후 보관되는 경합을 막는다.
        return clientRepository.findByIdForUpdate(clientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND));
    }
}
