package com.buildflow.estimate.domain.parse.service;

import com.buildflow.estimate.domain.parse.dto.ParseResult;
import com.buildflow.estimate.domain.parse.dto.ParsedItemResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ParseService {

    private final ExcelParserService excelParserService;
    private final OllamaService ollamaService;

    public ParseResult parse(MultipartFile file) {
        log.info("공내역서 파싱 시작: {}", file.getOriginalFilename());

        String excelText = excelParserService.extractText(file);
        List<ParsedItemResult> items = ollamaService.parseItems(excelText);

        log.info("공내역서 파싱 완료: {}개 항목 추출", items.size());

        return ParseResult.builder()
                .fileName(file.getOriginalFilename())
                .itemCount(items.size())
                .items(items)
                .build();
    }
}
