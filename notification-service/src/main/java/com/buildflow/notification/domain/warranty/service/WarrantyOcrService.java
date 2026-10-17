package com.buildflow.notification.domain.warranty.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarrantyOcrService {

    static final int MAX_TEXT_CHARS = 200_000;
    static final double MAX_RENDER_PIXELS = 12_000_000;
    static final double MAX_RENDER_SIDE = 5_000;

    private final WarrantyOcrResultService resultService;
    /**
     * Tesseract는 thread-safe하지 않아 prototype scope로 등록됨 (WarrantyOcrConfig).
     * @Async 동시 호출 시 ObjectProvider로 호출마다 새 인스턴스를 가져옴.
     */
    private final ObjectProvider<Tesseract> tesseractProvider;

    @Value("${app.ocr.scan-dpi:300}")
    private int scanDpi;

    /**
     * 업로드된 PDF를 비동기로 OCR 처리 → entity에 추출 결과 반영.
     * 1차: PDFBox 텍스트 추출 / 부족 시 2차: Tess4J (스캔 PDF 가정).
     */
    @Async("warrantyOcrExecutor")
    public void processAsync(Long warrantyId, Path pdfPath) {
        log.info("OCR 시작 warrantyId={}", warrantyId);
        try {
            WarrantyOcrParser.Result result = extractResult(pdfPath);

            boolean applied = resultService.apply(warrantyId,
                    result.getInsuranceCompany(),
                    result.getPolicyNumber(),
                    result.getStartDate(),
                    result.getEndDate(),
                    result.isConflicting());
            log.info("OCR 완료 warrantyId={} applied={} 추출={}/4", warrantyId, applied, result.extractedFieldCount());
        } catch (Exception e) {
            log.error("OCR 실패 warrantyId={} causeType={}", warrantyId, e.getClass().getSimpleName());
            try {
                resultService.markFailed(warrantyId);
            } catch (Exception statusError) {
                log.error("OCR 실패 상태 저장 실패 warrantyId={} causeType={}",
                        warrantyId, statusError.getClass().getSimpleName());
            }
        }
    }

    WarrantyOcrParser.Result extractResult(Path pdfPath) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            if (doc.getNumberOfPages() < 1 || doc.getNumberOfPages() > DefectWarrantyService.MAX_PDF_PAGES) {
                throw new IOException("PDF page limit exceeded");
            }
            LimitedTextWriter writer = new LimitedTextWriter();
            new PDFTextStripper().writeText(doc, writer);
            String text = writer.text();
            WarrantyOcrParser.Result textResult = parseUsableTextLayer(text);
            if (hasRequiredFields(textResult)) {
                log.debug("PDFBox 텍스트 추출 성공 ({}자)", text.trim().length());
                return textResult;
            }
            log.debug("PDFBox 텍스트의 필수 보증 필드 부족 — Tess4J OCR fallback");
            return extractWithFallback(doc, textResult);
        }
    }

    WarrantyOcrParser.Result extractWithFallback(PDDocument doc, WarrantyOcrParser.Result textResult)
            throws Exception {
        try {
            return mergeResults(textResult, extractImageResult(doc));
        } catch (Exception e) {
            if (textResult == null) throw e;
            log.warn("이미지 OCR 실패, 부분 텍스트 보존 causeType={}", e.getClass().getSimpleName());
            return textResult;
        }
    }

    private WarrantyOcrParser.Result extractImageResult(PDDocument doc) throws Exception {
        PDFRenderer renderer = new PDFRenderer(doc);
        StringBuilder sb = new StringBuilder();
        // prototype scope — 이 호출 단위로 전용 인스턴스 (thread-safe 회피)
        Tesseract tesseract = tesseractProvider.getObject();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            validateRenderSize(doc.getPage(i).getCropBox(), scanDpi);
            // Tesseract는 내부에서 흑백으로 변환하므로 처음부터 GRAY로 렌더링해 픽셀당 4바이트→1바이트로 줄인다
            // (640 MiB 컨테이너에서 10페이지 스캔 PDF가 한도에 닿던 문제, 2026-10-17 실측)
            BufferedImage img = renderer.renderImageWithDPI(i, scanDpi, ImageType.GRAY);
            try {
                String pageText = tesseract.doOCR(img);
                if ((long) sb.length() + pageText.length() + 1 > MAX_TEXT_CHARS) {
                    throw new IOException("OCR text limit exceeded");
                }
                sb.append(pageText).append('\n');
            } finally {
                img.flush();
            }
        }
        return WarrantyOcrParser.parse(sb.toString());
    }

    static boolean hasRequiredFields(WarrantyOcrParser.Result result) {
        return result != null && result.getInsuranceCompany() != null
                && result.getStartDate() != null && result.getEndDate() != null
                && !result.getEndDate().isBefore(result.getStartDate());
    }

    /** 같은 필드의 상충은 자동 확정하지 않고 수동 검토 상태로 전달한다. */
    static WarrantyOcrParser.Result mergeResults(WarrantyOcrParser.Result text,
                                                  WarrantyOcrParser.Result image) {
        if (text == null) return image;
        boolean conflicting = text.isConflicting() || image.isConflicting()
                || conflicts(text.getInsuranceCompany(), image.getInsuranceCompany())
                || policyNumbersConflict(text.getPolicyNumber(), image.getPolicyNumber())
                || conflicts(text.getStartDate(), image.getStartDate())
                || conflicts(text.getEndDate(), image.getEndDate());
        return WarrantyOcrParser.Result.builder()
                .insuranceCompany(firstPresent(text.getInsuranceCompany(), image.getInsuranceCompany()))
                .policyNumber(firstPresent(text.getPolicyNumber(), image.getPolicyNumber()))
                .startDate(firstPresent(text.getStartDate(), image.getStartDate()))
                .endDate(firstPresent(text.getEndDate(), image.getEndDate()))
                .conflicting(conflicting)
                .build();
    }

    private static <T> T firstPresent(T first, T second) {
        return first != null ? first : second;
    }

    /**
     * 페이지 전체 OCR은 텍스트 층에 있는 증권번호도 다시 읽는데, 영숫자 코드에서 0/O·1/I/L 같은 글자를
     * 자주 혼동한다(실측: 텍스트 층 "QA-2026-2001"을 OCR이 "04-2026-2001"로 읽음). 그대로 비교하면 같은 번호가 충돌로 판정돼 정상 혼합 PDF가 매번 수동 확인으로 떨어지므로
     * (2026-10-17 합성 혼합 PDF 실측) 혼동 글자와 구분자를 정규화해 비교한다. 실제로 다른 번호는 계속 충돌이다.
     */
    static boolean policyNumbersConflict(String text, String image) {
        if (text == null || image == null) return false;
        return !normalizePolicyNumber(text).equals(normalizePolicyNumber(image));
    }

    private static String normalizePolicyNumber(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        for (char c : value.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            switch (c) {
                case 'O', 'Q', 'D' -> normalized.append('0');
                case 'I', 'L' -> normalized.append('1');
                case 'Z' -> normalized.append('2');
                case 'A' -> normalized.append('4');
                case 'S' -> normalized.append('5');
                case 'G' -> normalized.append('6');
                case 'B' -> normalized.append('8');
                case '-', ' ', '_', '.' -> { }
                default -> normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private static <T> boolean conflicts(T first, T second) {
        return first != null && second != null && !Objects.equals(first, second);
    }

    static WarrantyOcrParser.Result parseUsableTextLayer(String text) {
        if (text == null || text.isBlank()) return null;
        WarrantyOcrParser.Result result = WarrantyOcrParser.parse(text);
        return result.extractedFieldCount() > 0 ? result : null;
    }

    static void validateRenderSize(PDRectangle cropBox, int dpi) throws IOException {
        if (cropBox == null || dpi < 72 || dpi > 300) {
            throw new IOException("Invalid render geometry");
        }
        double width = cropBox.getWidth() * dpi / 72.0;
        double height = cropBox.getHeight() * dpi / 72.0;
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
                || width > MAX_RENDER_SIDE || height > MAX_RENDER_SIDE
                || width * height > MAX_RENDER_PIXELS) {
            throw new IOException("PDF render limit exceeded");
        }
    }

    static final class LimitedTextWriter extends Writer {
        private final StringBuilder value = new StringBuilder();

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            if ((long) value.length() + length > MAX_TEXT_CHARS) {
                throw new IOException("PDF text limit exceeded");
            }
            value.append(chars, offset, length);
        }

        String text() {
            return value.toString();
        }

        @Override public void flush() { }
        @Override public void close() { }
    }
}
