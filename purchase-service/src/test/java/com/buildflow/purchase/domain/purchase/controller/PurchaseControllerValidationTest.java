package com.buildflow.purchase.domain.purchase.controller;

import com.buildflow.purchase.domain.purchase.service.PurchaseService;
import com.buildflow.purchase.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

class PurchaseControllerValidationTest {

    private final PurchaseService service = mock(PurchaseService.class);
    private final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mockMvc = MockMvcBuilders.standaloneSetup(new PurchaseController(service))
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
    void negativeUnitPriceIsRejectedWithHttp400() throws Exception {
        mockMvc.perform(post("/api/v1/purchases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("2", "-0.01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("unitPrice: 단가는 0 이상이어야 합니다."));

        verifyNoInteractions(service);
    }

    @Test
    void excessiveUnitPricePrecisionIsRejectedWithHttp400() throws Exception {
        mockMvc.perform(put("/api/v1/purchases/{id}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("2", "1.001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("unitPrice: 단가는 정수 10자리·소수 2자리 이하여야 합니다."));

        verifyNoInteractions(service);
    }

    @Test
    void fractionalIntegerQuantityIsRejectedWithHttp400() throws Exception {
        mockMvc.perform(post("/api/v1/purchases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("1.5", "1000.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("요청 본문이 올바르지 않습니다."));

        verifyNoInteractions(service);
    }

    private String createBody(String quantity, String unitPrice) {
        return """
                {"siteId":3,"itemName":"자재","quantity":%s,"unitPrice":%s}
                """.formatted(quantity, unitPrice);
    }

    private String updateBody(String quantity, String unitPrice) {
        return """
                {"itemName":"자재","quantity":%s,"unitPrice":%s}
                """.formatted(quantity, unitPrice);
    }
}
