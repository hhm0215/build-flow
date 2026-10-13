package com.buildflow.notification.domain.warranty.service;

import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WarrantyOcrServiceTest {

    @TempDir Path tempDir;

    @Test
    void 긴_텍스트라도_보증_필드가_없으면_이미지_OCR로_재시도한다() throws Exception {
        Path pdf = tempDir.resolve("synthetic.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("This page has plenty of selectable text, but no supported warranty fields at all.");
                stream.endText();
            }
            document.save(pdf.toFile());
        }

        @SuppressWarnings("unchecked")
        ObjectProvider<Tesseract> provider = mock(ObjectProvider.class);
        Tesseract tesseract = mock(Tesseract.class);
        when(provider.getObject()).thenReturn(tesseract);
        when(tesseract.doOCR(any(BufferedImage.class))).thenReturn(
                "서울보증보험 증권번호 QA-20261013-0001 보증기간 2026-10-13 ~ 2027-10-12");
        WarrantyOcrService service = new WarrantyOcrService(mock(WarrantyOcrResultService.class), provider);
        ReflectionTestUtils.setField(service, "minTextLength", 50);
        ReflectionTestUtils.setField(service, "scanDpi", 72);

        WarrantyOcrParser.Result result = service.extractResult(pdf);

        assertThat(result.getInsuranceCompany()).isEqualTo("서울보증보험");
        assertThat(result.getPolicyNumber()).isEqualTo("QA-20261013-0001");
        assertThat(result.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(result.getEndDate()).isEqualTo(LocalDate.of(2027, 10, 12));
        verify(provider).getObject();
        verify(tesseract).doOCR(any(BufferedImage.class));
    }

    @Test
    void 유효한_텍스트_필드가_있으면_이미지_OCR을_호출하지_않는다() {
        String text = "[QA] 합성 증권입니다. 보험회사 서울보증보험, 증권번호 QA-20261013-0001, "
                + "보증기간 2026-10-13 ~ 2027-10-12. 실제 계약의 효력은 없습니다.";

        WarrantyOcrParser.Result result = WarrantyOcrService.parseUsableTextLayer(text, 50);

        assertThat(result).isNotNull();
        assertThat(result.extractedFieldCount()).isEqualTo(4);
    }

    @Test
    void 짧거나_깨진_텍스트는_텍스트_결과로_확정하지_않는다() {
        assertThat(WarrantyOcrService.parseUsableTextLayer("짧음", 50)).isNull();
        assertThat(WarrantyOcrService.parseUsableTextLayer(
                "This is a long but unreadable or unrelated page that has no recognized fields.", 50)).isNull();
    }

    @Test
    void 이미지_OCR도_필드를_찾지_못하면_결과는_빈_상태다() throws Exception {
        Path pdf = tempDir.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        @SuppressWarnings("unchecked")
        ObjectProvider<Tesseract> provider = mock(ObjectProvider.class);
        Tesseract tesseract = mock(Tesseract.class);
        when(provider.getObject()).thenReturn(tesseract);
        when(tesseract.doOCR(any(BufferedImage.class))).thenReturn("recognized but unrelated text");
        WarrantyOcrService service = new WarrantyOcrService(mock(WarrantyOcrResultService.class), provider);
        ReflectionTestUtils.setField(service, "minTextLength", 50);
        ReflectionTestUtils.setField(service, "scanDpi", 72);

        assertThat(service.extractResult(pdf).extractedFieldCount()).isZero();
    }

    @Test
    void 파서_예외는_PENDING_상태를_FAILED로_넘긴다() {
        WarrantyOcrResultService results = mock(WarrantyOcrResultService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<Tesseract> provider = mock(ObjectProvider.class);
        WarrantyOcrService service = new WarrantyOcrService(results, provider);

        service.processAsync(42L, tempDir.resolve("missing.pdf"));

        verify(results).markFailed(42L);
        verifyNoInteractions(provider);
    }
}
