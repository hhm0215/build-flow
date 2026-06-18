package com.buildflow.notification.global.config;

import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class WarrantyOcrConfig {

    @Value("${app.ocr.tessdata-path:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessdataPath;

    @Value("${app.ocr.languages:kor+eng}")
    private String languages;

    /**
     * Tesseract native lib + traineddata 경로 바인딩.
     * 로컬에서 Tesseract 미설치라도 빈 생성은 성공 (실제 호출 시점에 native exception).
     */
    @Bean
    public Tesseract tesseract() {
        Tesseract t = new Tesseract();
        t.setDatapath(tessdataPath);
        t.setLanguage(languages);
        return t;
    }
}
