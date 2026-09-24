package com.buildflow.tax.global.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@ActiveProfiles("test")
class JacksonConfigurationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void productionConfigurationRejectsFractionalNumbersForIntegerFields() {
        assertFalse(objectMapper.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT));
    }
}
