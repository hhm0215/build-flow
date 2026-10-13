package com.buildflow.notification.domain.warranty.service;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WarrantyOcrLimitsTest {

    @Test
    void A4_300dpi는_허용하고_거대_페이지는_렌더_전에_거부한다() {
        assertThatCode(() -> WarrantyOcrService.validateRenderSize(PDRectangle.A4, 300)).doesNotThrowAnyException();
        assertThatThrownBy(() -> WarrantyOcrService.validateRenderSize(new PDRectangle(5000, 5000), 300))
                .isInstanceOf(IOException.class);
    }

    @Test
    void 텍스트_추출_누적량을_제한한다() throws Exception {
        WarrantyOcrService.LimitedTextWriter writer = new WarrantyOcrService.LimitedTextWriter();
        char[] chars = new char[WarrantyOcrService.MAX_TEXT_CHARS];
        writer.write(chars, 0, chars.length);
        assertThat(writer.text()).hasSize(WarrantyOcrService.MAX_TEXT_CHARS);
        assertThatThrownBy(() -> writer.write(new char[] {'x'}, 0, 1)).isInstanceOf(IOException.class);
    }
}
