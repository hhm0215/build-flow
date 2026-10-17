package com.buildflow.notification.global.config;

import com.buildflow.notification.domain.warranty.service.OcrEngine;
import com.buildflow.notification.domain.warranty.service.TesseractCliOcrEngine;
import com.buildflow.notification.domain.warranty.service.WarrantyOcrService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class WarrantyOcrConfig {

    /** 640 MiB 파일럿 컨테이너에서 OCR을 직렬화하고 대기열 포화는 호출자에게 돌려준다. */
    @Bean("warrantyOcrExecutor")
    public Executor warrantyOcrExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("warranty-ocr-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Value("${app.ocr.tessdata-path:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessdataPath;

    @Value("${app.ocr.languages:kor+eng}")
    private String languages;

    @Value("${app.ocr.executable:tesseract}")
    private String executable;

    /** 정상 페이지는 약 2초(2026-10-17 실측). 멈춘 네이티브 OCR이 단일 작업자를 영구 점유하지 않도록 끊는다. */
    @Value("${app.ocr.page-timeout-seconds:60}")
    private long pageTimeoutSeconds;

    @Bean
    public OcrEngine ocrEngine() {
        return new TesseractCliOcrEngine(executable, tessdataPath, languages, pageTimeoutSeconds,
                WarrantyOcrService.MAX_TEXT_CHARS);
    }
}
