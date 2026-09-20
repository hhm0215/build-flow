package com.buildflow.tax.global.exception;

import com.buildflow.tax.global.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GlobalExceptionHandlerTest {

    @Test
    void 요청_본문이_없거나_읽을_수_없으면_400을_반환한다() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableRequest(
                mock(HttpMessageNotReadableException.class));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getError()).isEqualTo("요청 본문이 올바르지 않습니다.");
    }
}
