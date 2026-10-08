package com.buildflow.site.domain.site.controller;

import com.buildflow.site.domain.site.service.SiteService;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import com.buildflow.site.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SiteDeleteControllerTest {

    private final SiteService siteService = mock(SiteService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SiteController(siteService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void existingSiteReturnsConflict() throws Exception {
        doThrow(new BusinessException(ErrorCode.SITE_DELETION_DISABLED)).when(siteService).delete(42L);

        mvc.perform(delete("/api/v1/sites/42"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(ErrorCode.SITE_DELETION_DISABLED.getMessage()));
    }

    @Test
    void missingSiteReturnsNotFound() throws Exception {
        doThrow(new BusinessException(ErrorCode.SITE_NOT_FOUND)).when(siteService).delete(99L);

        mvc.perform(delete("/api/v1/sites/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
