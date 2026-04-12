package com.buildflow.site.domain.client.dto;

import com.buildflow.site.domain.client.entity.Client;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ClientResponse {

    private Long id;
    private String companyName;
    private String representative;
    private String businessNo;
    private String phone;
    private String email;
    private String address;
    private String memo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ClientResponse from(Client client) {
        return ClientResponse.builder()
                .id(client.getId())
                .companyName(client.getCompanyName())
                .representative(client.getRepresentative())
                .businessNo(client.getBusinessNo())
                .phone(client.getPhone())
                .email(client.getEmail())
                .address(client.getAddress())
                .memo(client.getMemo())
                .createdAt(client.getCreatedAt())
                .updatedAt(client.getUpdatedAt())
                .build();
    }
}
