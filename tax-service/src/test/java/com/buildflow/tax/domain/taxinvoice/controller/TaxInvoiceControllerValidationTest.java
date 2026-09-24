package com.buildflow.tax.domain.taxinvoice.controller;

import com.buildflow.tax.domain.taxinvoice.service.TaxInvoiceService;
import com.buildflow.tax.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaxInvoiceControllerValidationTest {

    private final TaxInvoiceService service = mock(TaxInvoiceService.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new TaxInvoiceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void negativeSupplyAmountIsRejectedWithHttp400() throws Exception {
        mockMvc.perform(post("/api/v1/taxes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("-0.01", "0.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                        .value("supplyAmount: 공급가액은 0 이상이어야 합니다."));

        verifyNoInteractions(service);
    }

    @Test
    void excessiveTaxAmountPrecisionIsRejectedWithHttp400() throws Exception {
        mockMvc.perform(put("/api/v1/taxes/{id}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("100.00", "1.001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error")
                        .value("taxAmount: 세액은 정수 13자리·소수 2자리 이하여야 합니다."));

        verifyNoInteractions(service);
    }

    @Test
    void malformedAmountIsRejectedWithHttp400() throws Exception {
        mockMvc.perform(post("/api/v1/taxes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("{}", "0.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("요청 본문이 올바르지 않습니다."));

        verifyNoInteractions(service);
    }

    private String createBody(String supplyAmount, String taxAmount) {
        return """
                {"siteId":3,"type":"SALES","supplyAmount":%s,"taxAmount":%s}
                """.formatted(supplyAmount, taxAmount);
    }

    private String updateBody(String supplyAmount, String taxAmount) {
        return """
                {"type":"SALES","supplyAmount":%s,"taxAmount":%s}
                """.formatted(supplyAmount, taxAmount);
    }
}
