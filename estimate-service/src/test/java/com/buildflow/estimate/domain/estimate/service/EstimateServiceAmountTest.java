package com.buildflow.estimate.domain.estimate.service;

import com.buildflow.estimate.domain.estimate.dto.EstimateCreateRequest;
import com.buildflow.estimate.domain.estimate.dto.EstimateItemRequest;
import com.buildflow.estimate.domain.estimate.repository.EstimateRepository;
import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.kafka.KafkaProducerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstimateServiceAmountTest {

    @Mock EstimateRepository estimateRepository;
    @Mock KafkaProducerService kafkaProducerService;
    @InjectMocks EstimateService estimateService;

    @ParameterizedTest
    @CsvSource({
            "0.004, 1000",
            "1000, 0.004",
            "0.01, 0.01",
            "2, 9999999999999.99",
            "100000000, 1"
    })
    void rejectsAmountsThatWouldRoundOrOverflow(String quantity, String price) {
        EstimateCreateRequest request = request(quantity, price);

        assertThrows(BusinessException.class, () -> estimateService.create(request));

        verify(estimateRepository, never()).save(any());
    }

    @Test
    void acceptsExactTwoDecimalAmounts() {
        EstimateCreateRequest request = request("1.25", "1000");

        var result = estimateService.create(request);

        assertEquals(0, new BigDecimal("1250.00").compareTo(result.getTotalAmount()));
        verify(estimateRepository).save(any());
    }

    private EstimateCreateRequest request(String quantity, String price) {
        EstimateCreateRequest request = mock(EstimateCreateRequest.class);
        EstimateItemRequest item = mock(EstimateItemRequest.class);
        when(request.getItems()).thenReturn(List.of(item));
        when(item.getQuantity()).thenReturn(new BigDecimal(quantity));
        when(item.getUnitPrice()).thenReturn(new BigDecimal(price));
        return request;
    }
}
