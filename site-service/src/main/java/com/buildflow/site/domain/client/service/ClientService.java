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

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClientService {

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

    public List<ClientResponse> findAll() {
        return clientRepository.findAll().stream()
                .map(ClientResponse::from)
                .toList();
    }

    public ClientResponse findById(Long id) {
        return ClientResponse.from(getClient(id));
    }

    @Transactional
    public ClientResponse update(Long id, ClientUpdateRequest request) {
        Client client = getClient(id);
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

    private Client getClient(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLIENT_NOT_FOUND));
    }
}
