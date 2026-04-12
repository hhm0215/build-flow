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

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SiteService {

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

    public List<SiteResponse> findAll(SiteStatus status) {
        List<Site> sites = (status != null)
                ? siteRepository.findByStatusOrderByCreatedAtDesc(status)
                : siteRepository.findAllByOrderByCreatedAtDesc();

        return sites.stream()
                .map(SiteResponse::from)
                .toList();
    }

    public SiteResponse findById(Long id) {
        return SiteResponse.from(getSite(id));
    }

    @Transactional
    public SiteResponse update(Long id, SiteUpdateRequest request) {
        Site site = getSite(id);
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
        Site site = getSite(id);
        site.changeStatus(request.getStatus());
        return SiteResponse.from(site);
    }

    @Transactional
    public void delete(Long id) {
        Site site = getSite(id);
        siteRepository.delete(site);
    }

    private Site getSite(Long id) {
        return siteRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SITE_NOT_FOUND));
    }

    private Client resolveClient(Long clientId) {
        if (clientId == null) {
            return null;
        }
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND));
    }
}
