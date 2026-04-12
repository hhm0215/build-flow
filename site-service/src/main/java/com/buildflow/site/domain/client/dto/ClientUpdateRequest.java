package com.buildflow.site.domain.client.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ClientUpdateRequest {

    @NotBlank(message = "업체명은 필수입니다.")
    private String companyName;

    private String representative;
    private String businessNo;
    private String phone;
    private String email;
    private String address;
    private String memo;
}
