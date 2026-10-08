package com.buildflow.estimate.domain.parse.service;

import com.buildflow.estimate.global.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ParseLoggingTest {

    @Test
    void failedExcelReadDoesNotLogFilenameOrExceptionDetails(CapturedOutput output) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("PRIVATE_ESTIMATE.xlsx");
        when(file.getInputStream()).thenThrow(new IOException("PRIVATE_FILE_CONTENT"));

        assertThrows(BusinessException.class, () -> new ExcelParserService().extractText(file));

        assertFalse(output.toString().contains("PRIVATE_ESTIMATE"));
        assertFalse(output.toString().contains("PRIVATE_FILE_CONTENT"));
    }

    @Test
    void parseStartDoesNotLogFilename(CapturedOutput output) {
        MultipartFile file = mock(MultipartFile.class);
        ExcelParserService excel = mock(ExcelParserService.class);
        OllamaService ollama = mock(OllamaService.class);
        when(file.getOriginalFilename()).thenReturn("PRIVATE_ESTIMATE.xlsx");
        when(excel.extractText(file)).thenReturn("one row");
        when(ollama.parseItems("one row")).thenReturn(List.of());

        new ParseService(excel, ollama).parse(file);

        assertFalse(output.toString().contains("PRIVATE_ESTIMATE"));
    }

    @Test
    void malformedWorkbookDoesNotLogFilenameOrExceptionDetails(CapturedOutput output) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("PRIVATE_ESTIMATE.xlsx");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));

        assertThrows(BusinessException.class, () -> new ExcelParserService().extractText(file));

        assertFalse(output.toString().contains("PRIVATE_ESTIMATE"));
        assertFalse(output.toString().contains("EmptyFileException"));
    }

    @Test
    void invalidModelResponseDoesNotLogExtractedContent(CapturedOutput output) {
        OllamaService service = new OllamaService(null, new ObjectMapper());

        assertThrows(BusinessException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "parseJsonResponse", "[PRIVATE_CONTENT]"));
        assertThrows(BusinessException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "parseJsonResponse", "PRIVATE_NO_ARRAY"));

        assertFalse(output.toString().contains("PRIVATE_CONTENT"));
        assertFalse(output.toString().contains("PRIVATE_NO_ARRAY"));
    }

    @Test
    void modelConnectionFailureDoesNotLogExceptionDetail(CapturedOutput output) {
        WebClientRequestException failure = new WebClientRequestException(
                new IOException("PRIVATE_NETWORK_SECRET"), HttpMethod.POST,
                URI.create("http://localhost/api/chat"), new HttpHeaders());
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.error(failure))
                .build();
        OllamaService service = new OllamaService(client, new ObjectMapper());
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1);

        assertThrows(BusinessException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "callOllama", Map.of("model", "test")));

        assertFalse(output.toString().contains("PRIVATE_NETWORK_SECRET"));
    }

    @Test
    void unexpectedModelFailureDoesNotLogExceptionDetail(CapturedOutput output) {
        WebClient client = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("PRIVATE_MODEL_SECRET")))
                .build();
        OllamaService service = new OllamaService(client, new ObjectMapper());
        ReflectionTestUtils.setField(service, "timeoutSeconds", 1);

        assertThrows(BusinessException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "callOllama", Map.of("model", "test")));

        assertFalse(output.toString().contains("PRIVATE_MODEL_SECRET"));
    }
}
