package com.buildflow.notification.domain.warranty.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 가짜 tesseract 셸 스크립트로 프로세스 실행·시간 제한·정리를 검증한다(Linux CI에서 실행). */
@EnabledOnOs({OS.LINUX, OS.MAC})
class TesseractCliOcrEngineTest {

    @TempDir Path tempDir;

    private final BufferedImage page = new BufferedImage(20, 20, BufferedImage.TYPE_BYTE_GRAY);

    private String fakeTesseract(String body) throws IOException {
        Path script = tempDir.resolve("fake-tesseract.sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script.toString();
    }

    @Test
    void 프로세스_출력을_읽고_임시_페이지_파일을_지운다() throws Exception {
        Path seen = tempDir.resolve("seen-args.txt");
        String exe = fakeTesseract("echo \"$@\" > " + seen + "\nprintf '보험회사: 서울보증보험'");
        TesseractCliOcrEngine engine = new TesseractCliOcrEngine(exe, "/data/tess", "kor+eng", 10, 1000);

        String text = engine.recognize(page);

        assertThat(text).isEqualTo("보험회사: 서울보증보험");
        String args = Files.readString(seen);
        assertThat(args).contains("stdout", "--tessdata-dir /data/tess", "-l kor+eng", "--psm 6");
        Path imagePath = Path.of(args.split(" ")[0]);
        assertThat(imagePath).doesNotExist();
        assertThat(imagePath.getParent()).doesNotExist();
    }

    @Test
    void 시간_제한을_넘기면_프로세스를_끊고_실패한다() throws Exception {
        String exe = fakeTesseract("sleep 30");
        TesseractCliOcrEngine engine = new TesseractCliOcrEngine(exe, "/data/tess", "kor", 1, 1000);

        long started = System.nanoTime();
        assertThatThrownBy(() -> engine.recognize(page))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timeout");
        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(10);
    }

    @Test
    void 실패_종료코드와_과도한_출력은_실패다() throws Exception {
        TesseractCliOcrEngine failing = new TesseractCliOcrEngine(fakeTesseract("exit 3"), "/data/tess", "kor", 10, 1000);
        assertThatThrownBy(() -> failing.recognize(page)).isInstanceOf(IOException.class)
                .hasMessageContaining("exitCode=3");

        TesseractCliOcrEngine noisy = new TesseractCliOcrEngine(
                fakeTesseract("head -c 5000 /dev/zero | tr '\\0' 'a'"), "/data/tess", "kor", 10, 100);
        assertThatThrownBy(() -> noisy.recognize(page)).isInstanceOf(IOException.class)
                .hasMessageContaining("limit");
    }
}
