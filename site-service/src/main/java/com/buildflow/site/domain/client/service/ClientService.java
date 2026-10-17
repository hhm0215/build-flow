package com.buildflow.site.domain.client.service;

import com.buildflow.site.domain.client.dto.ClientCreateRequest;
import com.buildflow.site.domain.client.dto.ClientResponse;
import com.buildflow.site.domain.client.dto.ClientUpdateRequest;
import com.buildflow.site.domain.client.entity.Client;
import com.buildflow.site.domain.client.repository.ClientRepository;
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
public class ClientService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final ClientRepository clientRepository;

    @Transactional
    public ClientResponse create(ClientCreateRequest request) {
        Client client = Client.builder()
                .companyName(request.getCompanyName())
                .representative(request.getRepresentative())
                .businessNo(request.getBusinessNo())
                .phone(request.getPhone())
                .email(request.getEmail())
                .address(request.getAddress())
                .memo(request.getMemo())
                .build();

        return ClientResponse.from(clientRepository.save(client));
    }

    /** archived가 null이면 보관 여부와 무관하게 전체를 반환한다(기존 계약). */
    public List<ClientResponse> findAll(Boolean archived) {
        return clientRepository.findAll().stream()
                .filter(client -> archived == null || client.isArchived() == archived)
                .map(ClientResponse::from)
                .toList();
    }

    public ClientResponse findById(Long id) {
        return ClientResponse.from(getClient(id));
    }

    @Transactional
    public ClientResponse update(Long id, ClientUpdateRequest request) {
        Client client = getClientForUpdate(id);
        if (client.isArchived()) {
            throw new BusinessException(ErrorCode.CLIENT_ARCHIVED);
        }
        client.update(
                request.getCompanyName(),
                request.getRepresentative(),
                request.getBusinessNo(),
                request.getPhone(),
                request.getEmail(),
                request.getAddress(),
                request.getMemo()
        );
        return ClientResponse.from(client);
    }

    @Transactional
    public ClientResponse archive(Long id) {
        Client client = getClientForUpdate(id);
        client.archive(LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS));
        return ClientResponse.from(client);
    }

    @Transactional
    public ClientResponse restore(Long id) {
        Client client = getClientForUpdate(id);
        client.restore();
        return ClientResponse.from(client);
    }

    private Client getClientForUpdate(Long id) {
        return clientRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND));
    }

    private Client getClient(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND));
    }
}
