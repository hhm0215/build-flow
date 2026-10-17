package com.buildflow.notification.domain.warranty.service;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tesseract를 별도 프로세스로 실행한다. JVM 안(JNA)에서 부르면 멈춘 호출을 끊을 수 없고 네이티브 메모리가
 * 반환되지 않아 640 MiB 컨테이너가 상시 한도에 닿았다(2026-10-17 실측). 프로세스로 분리하면 페이지마다
 * 시간 제한 후 강제 종료할 수 있고, 끝나면 메모리가 운영체제에 반환된다.
 */
@Slf4j
public class TesseractCliOcrEngine implements OcrEngine {

    private final String executable;
    private final String tessdataPath;
    private final String languages;
    private final long pageTimeoutSeconds;
    private final int maxOutputChars;

    public TesseractCliOcrEngine(String executable, String tessdataPath, String languages,
                                 long pageTimeoutSeconds, int maxOutputChars) {
        this.executable = executable;
        this.tessdataPath = tessdataPath;
        this.languages = languages;
        this.pageTimeoutSeconds = pageTimeoutSeconds;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String recognize(BufferedImage page) throws IOException {
        Path workDir = Files.createTempDirectory("warranty-ocr-");
        Path image = workDir.resolve("page.png");
        Path output = workDir.resolve("page.txt");
        Process process = null;
        try {
            if (!ImageIO.write(page, "png", image.toFile())) {
                throw new IOException("OCR page image could not be written");
            }
            process = new ProcessBuilder(command(image))
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(pageTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                log.warn("OCR 페이지 시간 초과로 프로세스 종료 timeoutSeconds={}", pageTimeoutSeconds);
                throw new IOException("OCR page timeout");
            }
            if (process.exitValue() != 0) {
                throw new IOException("OCR process failed exitCode=" + process.exitValue());
            }
            return readLimited(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OCR interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(image);
            Files.deleteIfExists(output);
            Files.deleteIfExists(workDir);
        }
    }

    /**
     * 페이지를 하나의 글 블록으로 읽는다(--psm 6). 기본 자동 분할(PSM 3)은 글이 빽빽한 합성 스캔 페이지에서
     * 해상도를 잘못 추정해 "보험회사·보험기간" 줄을 통째로 놓쳤고(2026-10-17 컨테이너 비교, --dpi 70/150/300 모두 0줄),
     * PSM 6은 1·10페이지 모두 읽었다. 보증서는 "라벨: 값" 줄 단위라 파서와도 맞는다.
     */
    List<String> command(Path image) {
        return List.of(executable, image.toString(), "stdout", "--tessdata-dir", tessdataPath, "-l", languages,
                "--psm", "6");
    }

    private String readLimited(Path output) throws IOException {
        try (InputStream in = Files.newInputStream(output)) {
            byte[] bytes = in.readNBytes(maxOutputChars * 4 + 1);
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.length() > maxOutputChars) {
                throw new IOException("OCR text limit exceeded");
            }
            return text;
        }
    }
}
