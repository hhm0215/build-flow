package com.buildflow.tax.global.exception;

import com.buildflow.tax.domain.taxinvoice.controller.TaxInvoiceController;
import com.buildflow.tax.domain.taxinvoice.service.TaxInvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @Test
    void 입금_확정_세금계산서_삭제는_HTTP_409를_반환한다() throws Exception {
        TaxInvoiceService service = mock(TaxInvoiceService.class);
        doThrow(new BusinessException(ErrorCode.PAYMENT_CONFIRMED_TAX_INVOICE_IMMUTABLE))
                .when(service).delete(42L);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TaxInvoiceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(delete("/api/v1/taxes/{id}", 42L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                        .value("입금 확인된 세금계산서는 수정하거나 삭제할 수 없습니다."));
    }

    @Test
    void 요청_본문이_없거나_읽을_수_없으면_400을_반환한다() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleUnreadableRequest(
                mock(HttpMessageNotReadableException.class));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getError()).isEqualTo("요청 본문이 올바르지 않습니다.");
    }
}
