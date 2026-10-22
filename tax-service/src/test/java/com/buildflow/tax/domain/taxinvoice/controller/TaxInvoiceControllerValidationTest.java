package com.buildflow.tax.domain.taxinvoice.controller;

import com.buildflow.tax.domain.taxinvoice.service.TaxInvoiceService;
import com.buildflow.tax.domain.taxinvoice.service.TaxPaymentService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaxInvoiceControllerValidationTest {

    private final TaxInvoiceService service = mock(TaxInvoiceService.class);
    private final TaxPaymentService paymentService = mock(TaxPaymentService.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new TaxInvoiceController(service, paymentService))
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "0|amount: 입금액은 0보다 커야 합니다.",
            "-0.01|amount: 입금액은 0보다 커야 합니다.",
            "1.001|amount: 입금액은 정수 13자리·소수 2자리 이하여야 합니다.",
            "10000000000000.00|amount: 입금액은 정수 13자리·소수 2자리 이하여야 합니다."
    })
    void invalidPaymentAmountIsRejectedWithHttp400(String amount, String message) throws Exception {
        mockMvc.perform(post("/api/v1/taxes/{id}/payments", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%s,"paymentDate":"2026-10-23"}
                                """.formatted(amount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(message));

        verifyNoInteractions(paymentService);
    }

    @Test
    void paymentWithoutDateOrWithLongMemoIsRejectedWithHttp400() throws Exception {
        mockMvc.perform(post("/api/v1/taxes/{id}/payments", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("paymentDate: 입금일은 필수입니다."));
        mockMvc.perform(post("/api/v1/taxes/{id}/payments", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"paymentDate\":\"2026-10-23\",\"memo\":\"%s\"}"
                                .formatted("가".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("memo: 입금 메모는 500자 이하여야 합니다."));
        mockMvc.perform(patch("/api/v1/taxes/{id}/payments/{paymentId}/cancel", 7L, 3L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"%s\"}".formatted("가".repeat(501))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("reason: 취소 사유는 500자 이하여야 합니다."));

        verifyNoInteractions(paymentService);
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
