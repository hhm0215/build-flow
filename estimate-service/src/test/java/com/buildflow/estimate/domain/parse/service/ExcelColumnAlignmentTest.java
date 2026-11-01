package com.buildflow.estimate.domain.parse.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelColumnAlignmentTest {

    private static MockMultipartFile workbook() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("합성");
            Row header = sheet.createRow(0);
            String[] titles = {"번호", "품명", "규격", "단위", "수량", "단가"};
            for (int i = 0; i < titles.length; i++) {
                header.createCell(i).setCellValue(titles[i]);
            }
            // 규격(C)·단가(F) 셀이 파일에 아예 없는 행 — 엑셀이 빈 칸을 저장하지 않는 형태
            Row item = sheet.createRow(1);
            item.createCell(0).setCellValue(1);
            item.createCell(1).setCellValue("합성 현장 정리");
            item.createCell(3).setCellValue("식");
            item.createCell(4).setCellValue(1);
            sheet.createRow(2); // 빈 행은 건너뛴다
            Row total = sheet.createRow(3);
            total.createCell(1).setCellValue("합계");
            wb.write(out);
            return new MockMultipartFile("file", "synthetic.xlsx", null, out.toByteArray());
        }
    }

    @Test
    void 빈_셀이_있어도_열_위치를_보존한다() throws IOException {
        String text = new ExcelParserService().extractText(workbook());

        String[] lines = text.split("\n");
        assertEquals(3, lines.length);
        assertEquals("번호\t품명\t규격\t단위\t수량\t단가", lines[0]);
        assertEquals("1\t합성 현장 정리\t\t식\t1", lines[1]);
        assertEquals("\t합계", lines[2]);
    }
}
