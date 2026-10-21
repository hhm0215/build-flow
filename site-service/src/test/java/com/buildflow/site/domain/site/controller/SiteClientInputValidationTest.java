package com.buildflow.site.domain.site.controller;

import com.buildflow.site.domain.client.controller.ClientController;
import com.buildflow.site.domain.client.service.ClientService;
import com.buildflow.site.domain.site.service.SiteService;
import com.buildflow.site.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SiteClientInputValidationTest {

    private final SiteService siteService = mock(SiteService.class);
    private final ClientService clientService = mock(ClientService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(
                        new SiteController(siteService), new ClientController(clientService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void siteCreateAndUpdateRejectColumnOverflowBeforePersistence() throws Exception {
        for (boolean update : new boolean[]{false, true}) {
            assertBadRequest("/api/v1/sites", update,
                    Map.of("siteName", "가".repeat(201)), "현장명은 200자 이하여야 합니다.");
            assertBadRequest("/api/v1/sites", update,
                    Map.of("siteName", "현장", "address", "가".repeat(501)), "주소는 500자 이하여야 합니다.");
            assertBadRequest("/api/v1/sites", update,
                    Map.of("siteName", "현장", "memo", "가".repeat(16001)), "메모는 16000자 이하여야 합니다.");
        }
        verifyNoInteractions(siteService);
    }

    @Test
    void siteCreateAndUpdateRejectReversedDates() throws Exception {
        for (boolean update : new boolean[]{false, true}) {
            assertBadRequest("/api/v1/sites", update,
                    Map.of("siteName", "현장", "startDate", "2026-10-21", "endDate", "2026-10-20"),
                    "종료일은 시작일보다 빠를 수 없습니다.");
        }
        verifyNoInteractions(siteService);
    }

    @Test
    void siteAcceptsColumnBoundaryAndOpenEndedDates() throws Exception {
        mvc.perform(post("/api/v1/sites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "siteName", "가".repeat(200),
                                "address", "가".repeat(500),
                                "startDate", "2026-10-21"))))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/sites/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "siteName", "현장",
                                "endDate", "2026-10-21"))))
                .andExpect(status().isOk());
    }

    @Test
    void memoUtf16BoundaryKeepsSupplementaryCharactersWithinTextCapacity() throws Exception {
        String withinLimit = "😀".repeat(8000);
        String overLimit = withinLimit + "😀";

        mvc.perform(post("/api/v1/sites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "siteName", "현장", "memo", withinLimit))))
                .andExpect(status().isCreated());

        assertBadRequest("/api/v1/sites", false,
                Map.of("siteName", "현장", "memo", overLimit),
                "메모는 16000자 이하여야 합니다.");
    }

    @Test
    void clientCreateAndUpdateRejectEveryBoundedColumnOverflow() throws Exception {
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("companyName", 200);
        limits.put("representative", 50);
        limits.put("businessNo", 20);
        limits.put("phone", 20);
        limits.put("email", 100);
        limits.put("address", 500);
        limits.put("memo", 16000);

        for (boolean update : new boolean[]{false, true}) {
            for (Map.Entry<String, Integer> limit : limits.entrySet()) {
                Map<String, String> body = new LinkedHashMap<>();
                body.put("companyName", "거래처");
                body.put(limit.getKey(), "가".repeat(limit.getValue() + 1));
                assertBadRequest("/api/v1/clients", update, body, null);
            }
        }
        verifyNoInteractions(clientService);
    }

    private void assertBadRequest(String basePath, boolean update, Map<String, String> body,
                                  String expectedError) throws Exception {
        MockHttpServletRequestBuilder request = update
                ? put(basePath + "/7")
                : post(basePath);
        request.contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));

        var result = mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        if (expectedError != null) {
            result.andExpect(jsonPath("$.error").value(expectedError));
        }
    }
}
