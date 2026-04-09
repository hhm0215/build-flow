package com.buildflow.estimate.domain.parse.controller;

import com.buildflow.estimate.domain.parse.dto.ParseResult;
import com.buildflow.estimate.domain.parse.service.ParseService;
import com.buildflow.estimate.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/estimates")
@RequiredArgsConstructor
public class ParseController {

    private final ParseService parseService;

    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ParseResult>> parse(
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.success(parseService.parse(file)));
    }
}
