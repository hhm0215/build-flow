package com.buildflow.site.domain.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ClientUpdateRequest {

    @NotBlank(message = "업체명은 필수입니다.")
    @Size(max = 200, message = "업체명은 200자 이하여야 합니다.")
    private String companyName;

    @Size(max = 50, message = "대표자명은 50자 이하여야 합니다.")
    private String representative;
    @Size(max = 20, message = "사업자번호는 20자 이하여야 합니다.")
    private String businessNo;
    @Size(max = 20, message = "연락처는 20자 이하여야 합니다.")
    private String phone;
    @Size(max = 100, message = "이메일은 100자 이하여야 합니다.")
    private String email;
    @Size(max = 500, message = "주소는 500자 이하여야 합니다.")
    private String address;
    // Java UTF-16 16,000 code units는 utf8mb4 최악에도 64,000 bytes로 MySQL TEXT 한도 이내다.
    @Size(max = 16000, message = "메모는 16000자 이하여야 합니다.")
    private String memo;
}
