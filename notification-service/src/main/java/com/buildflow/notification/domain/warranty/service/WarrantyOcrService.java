package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class WarrantyOcrService {

    private final DefectWarrantyRepository warrantyRepository;
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
    @Async
    @Transactional
    public void processAsync(Long warrantyId, Path pdfPath) {
        log.info("OCR 시작 warrantyId={} path={}", warrantyId, pdfPath);
        try {
            String text = extractText(pdfPath);
            WarrantyOcrParser.Result result = WarrantyOcrParser.parse(text);

            DefectWarranty warranty = warrantyRepository.findById(warrantyId)
                    .orElseThrow(() -> new IllegalStateException(
                            "warranty not found during OCR callback: " + warrantyId));
            warranty.applyOcrResult(
                    result.getInsuranceCompany(),
                    result.getPolicyNumber(),
                    result.getStartDate(),
                    result.getEndDate());

            log.info("OCR 완료 warrantyId={} 추출={}/4 insurer={}",
                    warrantyId, result.extractedFieldCount(), result.getInsuranceCompany());
        } catch (Exception e) {
            log.error("OCR 실패 warrantyId={}: {}", warrantyId, e.getMessage(), e);
            warrantyRepository.findById(warrantyId).ifPresent(DefectWarranty::markOcrFailed);
        }
    }

    private String extractText(Path pdfPath) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            String text = new PDFTextStripper().getText(doc);
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
                BufferedImage img = renderer.renderImageWithDPI(i, scanDpi);
                sb.append(tesseract.doOCR(img)).append('\n');
            }
            return sb.toString();
        }
    }
}
