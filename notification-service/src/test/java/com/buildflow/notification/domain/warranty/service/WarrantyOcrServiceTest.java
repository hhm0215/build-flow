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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        WarrantyOcrParser.Result result = WarrantyOcrService.parseUsableTextLayer(text);

        assertThat(result).isNotNull();
        assertThat(result.extractedFieldCount()).isEqualTo(4);
        assertThat(WarrantyOcrService.hasRequiredFields(result)).isTrue();
    }

    @Test
    void 텍스트의_일부_필드와_이미지의_누락_필드를_병합한다() {
        WarrantyOcrParser.Result text = WarrantyOcrService.parseUsableTextLayer(
                "[QA] 합성 증권번호 QA-20261013-0001. 그 밖의 보증기간과 보험사는 이미지에 있습니다.");
        WarrantyOcrParser.Result image = WarrantyOcrParser.parse(
                "서울보증보험 보증기간 2026-10-13 ~ 2027-10-12");

        assertThat(WarrantyOcrService.hasRequiredFields(text)).isFalse();
        WarrantyOcrParser.Result merged = WarrantyOcrService.mergeResults(text, image);
        assertThat(merged.getPolicyNumber()).isEqualTo("QA-20261013-0001");
        assertThat(merged.getInsuranceCompany()).isEqualTo("서울보증보험");
        assertThat(merged.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(merged.getEndDate()).isEqualTo(LocalDate.of(2027, 10, 12));
        assertThat(merged.isConflicting()).isFalse();
    }

    @Test
    void OCR이_텍스트층_증권번호를_혼동_글자로_다시_읽어도_충돌이_아니다() {
        WarrantyOcrParser.Result text = WarrantyOcrParser.Result.builder()
                .insuranceCompany("서울보증보험")
                .policyNumber("QA-2026-2001")
                .build();
        WarrantyOcrParser.Result image = WarrantyOcrParser.Result.builder()
                .policyNumber("04-2026-2001") // 실측: Tess4J가 Q→0, A→4로 읽음
                .startDate(LocalDate.of(2026, 10, 17))
                .endDate(LocalDate.of(2028, 10, 16))
                .build();

        WarrantyOcrParser.Result merged = WarrantyOcrService.mergeResults(text, image);

        assertThat(merged.isConflicting()).isFalse();
        assertThat(merged.getPolicyNumber()).isEqualTo("QA-2026-2001");
        assertThat(WarrantyOcrService.policyNumbersConflict("QA-2026-2001", "QA-2026-2002")).isTrue();
        assertThat(WarrantyOcrService.policyNumbersConflict("QA-2O26-2OO1", "QA-2026-2001")).isFalse();
        assertThat(WarrantyOcrService.policyNumbersConflict("AB-1I0O", "48 110 0")).isFalse();
    }

    @Test
    void 상충하는_두_추출값은_텍스트값을_보존하고_수동_확인으로_넘긴다() {
        WarrantyOcrParser.Result text = WarrantyOcrParser.Result.builder()
                .policyNumber("QA-20261013-0001")
                .startDate(LocalDate.of(2026, 10, 13))
                .build();
        WarrantyOcrParser.Result image = WarrantyOcrParser.Result.builder()
                .insuranceCompany("서울보증보험")
                .policyNumber("QA-20261013-0002")
                .startDate(LocalDate.of(2026, 10, 14))
                .endDate(LocalDate.of(2027, 10, 12))
                .build();

        WarrantyOcrParser.Result merged = WarrantyOcrService.mergeResults(text, image);

        assertThat(merged.isConflicting()).isTrue();
        assertThat(merged.getPolicyNumber()).isEqualTo("QA-20261013-0001");
        assertThat(merged.getStartDate()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(merged.getEndDate()).isEqualTo(LocalDate.of(2027, 10, 12));
    }

    @Test
    void 이미지_OCR_실패_시_기존_부분_텍스트는_보정용으로_남긴다() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<Tesseract> provider = mock(ObjectProvider.class);
        Tesseract tesseract = mock(Tesseract.class);
        when(provider.getObject()).thenReturn(tesseract);
        when(tesseract.doOCR(any(BufferedImage.class))).thenThrow(new IllegalStateException("synthetic failure"));
        WarrantyOcrService service = new WarrantyOcrService(mock(WarrantyOcrResultService.class), provider);
        ReflectionTestUtils.setField(service, "scanDpi", 72);
        WarrantyOcrParser.Result partial = WarrantyOcrParser.parse("증권번호 QA-20261013-0001");

        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            assertThat(service.extractWithFallback(document, partial)).isSameAs(partial);
            assertThatThrownBy(() -> service.extractWithFallback(document, null))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 짧거나_깨진_텍스트는_텍스트_결과로_확정하지_않는다() {
        assertThat(WarrantyOcrService.parseUsableTextLayer("짧음")).isNull();
        assertThat(WarrantyOcrService.parseUsableTextLayer(
                "This is a long but unreadable or unrelated page that has no recognized fields.")).isNull();
        assertThat(WarrantyOcrService.parseUsableTextLayer("증권번호 QA-12345678").getPolicyNumber())
                .isEqualTo("QA-12345678");
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
