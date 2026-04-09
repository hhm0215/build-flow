package com.buildflow.estimate.domain.parse.service;

import com.buildflow.estimate.global.exception.BusinessException;
import com.buildflow.estimate.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ExcelParserService {

    private static final int MAX_ROWS = 300;

    public String extractText(MultipartFile file) {
        validateFile(file);

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            return buildTableText(sheet);
        } catch (IOException e) {
            log.error("엑셀 파일 파싱 실패: {}", file.getOriginalFilename(), e);
            throw new BusinessException(ErrorCode.EXCEL_PARSE_FAILED);
        }
    }

    private void validateFile(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".xlsx")) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
    }

    private String buildTableText(Sheet sheet) {
        List<String> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        int processedRows = 0;

        for (Row row : sheet) {
            if (processedRows >= MAX_ROWS) break;

            List<String> cells = new ArrayList<>();
            boolean hasData = false;

            for (Cell cell : row) {
                String value = formatter.formatCellValue(cell).trim();
                cells.add(value);
                if (!value.isEmpty()) hasData = true;
            }

            if (hasData) {
                rows.add(String.join("\t", cells));
                processedRows++;
            }
        }

        log.info("엑셀 파싱 완료: {}행 추출", rows.size());
        return String.join("\n", rows);
    }
}
