package com.buildflow.estimate.global.exception;

import com.buildflow.estimate.domain.estimate.controller.EstimateController;
import com.buildflow.estimate.domain.estimate.service.EstimateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @Test
    void 확정_견적_삭제는_HTTP_409를_반환한다() throws Exception {
        EstimateService service = mock(EstimateService.class);
        doThrow(new BusinessException(ErrorCode.CONFIRMED_ESTIMATE_DELETE_NOT_ALLOWED))
                .when(service).delete(42L);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new EstimateController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(delete("/api/v1/estimates/{id}", 42L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("확정된 견적서는 삭제할 수 없습니다."));
    }
}
