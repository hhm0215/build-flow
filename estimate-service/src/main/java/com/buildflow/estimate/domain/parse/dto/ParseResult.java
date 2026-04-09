package com.buildflow.estimate.domain.parse.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ParseResult {

    private String fileName;
    private int itemCount;
    private List<ParsedItemResult> items;
}
