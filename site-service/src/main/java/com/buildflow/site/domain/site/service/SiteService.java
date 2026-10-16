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
        Client client = resolveClient(request.getClientId());

        Site site = Site.builder()
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
        Client client = resolveClient(request.getClientId());

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

    private Client resolveClient(Long clientId) {
        if (clientId == null) {
            return null;
        }
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND));
    }
}
