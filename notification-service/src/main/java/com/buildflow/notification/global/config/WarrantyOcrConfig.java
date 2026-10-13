package com.buildflow.notification.global.config;

import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
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

    /**
     * Tesseract native lib + traineddata 경로 바인딩.
     *
     * <p>Scope=prototype: Tesseract(tess4j wrapper)는 native libtesseract를 감싸며
     * thread-safe하지 않음. @Async OCR 동시 호출 시 race / SIGSEGV 위험.
     * 호출 측은 {@code ObjectProvider<Tesseract>.getObject()}로 매 호출마다 신규 인스턴스를 가져와야 함.
     */
    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    public Tesseract tesseract() {
        Tesseract t = new Tesseract();
        t.setDatapath(tessdataPath);
        t.setLanguage(languages);
        return t;
    }
}
