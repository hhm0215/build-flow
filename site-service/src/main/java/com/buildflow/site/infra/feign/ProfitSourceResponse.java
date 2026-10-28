package com.buildflow.site.infra.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 원천 서비스 ApiResponse 래퍼({ success, data, error }) 중 재계산에 필요한 부분. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfitSourceResponse<T>(boolean success, T data) {
}
