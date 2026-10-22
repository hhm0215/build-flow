package com.buildflow.notification.domain.warranty.controller;

import com.buildflow.notification.domain.warranty.service.DefectWarrantyService;
import com.buildflow.notification.domain.warranty.service.WarrantyOriginalFileService;
import com.buildflow.notification.domain.warranty.service.WarrantyOriginalFileService.OpenedPdf;
import com.buildflow.notification.domain.warranty.service.WarrantyFileAuthorizationService;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import com.buildflow.notification.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.io.InputStreamResource;
import java.io.ByteArrayInputStream;

import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WarrantyOriginalDownloadControllerTest {

    @Test
    void 업로드_사본은_첨부파일로_내려주고_캐시와_브라우저_추론을_막는다() throws Exception {
        WarrantyOriginalFileService files = mock(WarrantyOriginalFileService.class);
        WarrantyFileAuthorizationService authorization = mock(WarrantyFileAuthorizationService.class);
        byte[] bytes = "%PDF-1.4 synthetic".getBytes(StandardCharsets.US_ASCII);
        when(files.open(7L)).thenReturn(new OpenedPdf(
                new InputStreamResource(new ByteArrayInputStream(bytes)), bytes.length));
        var mvc = MockMvcBuilders.standaloneSetup(
                new DefectWarrantyController(mock(DefectWarrantyService.class), files, authorization)).build();

        mvc.perform(get("/api/v1/warranties/7/file").header("Authorization", "Bearer synthetic-test"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"warranty-7.pdf\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        verify(authorization).requireAdminAccess("Bearer synthetic-test");
    }

    @Test
    void 내부_직접_호출도_무인증이면_파일을_읽지_않고_401을_준다() throws Exception {
        WarrantyOriginalFileService files = mock(WarrantyOriginalFileService.class);
        WarrantyFileAuthorizationService authorization = mock(WarrantyFileAuthorizationService.class);
        doThrow(new BusinessException(ErrorCode.WARRANTY_FILE_UNAUTHORIZED))
                .when(authorization).requireAdminAccess(null);
        var mvc = MockMvcBuilders.standaloneSetup(
                new DefectWarrantyController(mock(DefectWarrantyService.class), files, authorization))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/warranties/7/file"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(files);
    }
}
