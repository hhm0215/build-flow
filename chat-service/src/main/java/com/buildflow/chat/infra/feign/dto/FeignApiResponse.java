package com.buildflow.chat.infra.feign.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 타 서비스의 {@code ApiResponse<T>} 래퍼를 역직렬화하기 위한 DTO.
 * 외부 응답 수신 전용이라 setter 허용 (엔티티 @Data 금지 규칙과 무관).
 */
@Getter
@Setter
@NoArgsConstructor
public class FeignApiResponse<T> {
    private boolean success;
    private T data;
    private String error;
}
