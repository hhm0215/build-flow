package com.buildflow.notification.domain.warranty.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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

    @Value("${app.ocr.min-text-length:50}")
    private int minTextLength;

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
            String text = extractText(pdfPath);
            WarrantyOcrParser.Result result = WarrantyOcrParser.parse(text);

            boolean applied = resultService.apply(warrantyId,
                    result.getInsuranceCompany(),
                    result.getPolicyNumber(),
                    result.getStartDate(),
                    result.getEndDate());
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

    private String extractText(Path pdfPath) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            if (doc.getNumberOfPages() < 1 || doc.getNumberOfPages() > DefectWarrantyService.MAX_PDF_PAGES) {
                throw new IOException("PDF page limit exceeded");
            }
            LimitedTextWriter writer = new LimitedTextWriter();
            new PDFTextStripper().writeText(doc, writer);
            String text = writer.text();
            if (text != null && text.trim().length() >= minTextLength) {
                log.debug("PDFBox 텍스트 추출 성공 ({}자)", text.trim().length());
                return text;
            }
            log.debug("PDFBox 텍스트 부족 — Tess4J OCR fallback");
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder sb = new StringBuilder();
            // prototype scope — 이 호출 단위로 전용 인스턴스 (thread-safe 회피)
            Tesseract tesseract = tesseractProvider.getObject();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                validateRenderSize(doc.getPage(i).getCropBox(), scanDpi);
                BufferedImage img = renderer.renderImageWithDPI(i, scanDpi);
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
            return sb.toString();
        }
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
