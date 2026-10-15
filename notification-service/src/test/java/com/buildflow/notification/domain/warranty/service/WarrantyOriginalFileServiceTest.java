package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarrantyOriginalFileServiceTest {

    private static final String NAME = "123e4567-e89b-12d3-a456-426614174000.pdf";

    @TempDir Path tempDir;

    private final DefectWarrantyRepository repository = mock(DefectWarrantyRepository.class);
    private final WarrantyOriginalFileService service = new WarrantyOriginalFileService(repository);

    private void setup(Long id, String storedPath) {
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        when(repository.findById(id)).thenReturn(Optional.of(
                DefectWarranty.createPending(1L, storedPath)));
    }

    @Test
    void 업로드_폴더의_UUID_PDF_사본만_그대로_내려준다() throws Exception {
        Path file = tempDir.resolve(NAME);
        byte[] content = "%PDF-1.4 synthetic test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(file, content);
        setup(7L, file.toString());

        try (var stream = service.open(7L).resource().getInputStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void 수동_등록_또는_없는_파일은_경로를_노출하지_않고_거부한다() {
        setup(7L, null);
        assertUnavailable(() -> service.open(7L));
        setup(8L, tempDir.resolve(NAME).toString());
        assertUnavailable(() -> service.open(8L));
    }

    @Test
    void 폴더_탈출_또는_UUID가_아닌_기록은_거부한다() throws Exception {
        Path outside = tempDir.resolve(NAME);
        Files.writeString(outside, "%PDF-1.4 synthetic test");
        Path allowed = Files.createDirectory(tempDir.resolve("allowed"));
        setup(7L, outside.toString());
        ReflectionTestUtils.setField(service, "uploadDir", allowed.toString());
        assertUnavailable(() -> service.open(7L));
        setup(8L, allowed.resolve(".." + java.io.File.separator + NAME).toString());
        ReflectionTestUtils.setField(service, "uploadDir", allowed.toString());
        assertUnavailable(() -> service.open(8L));
        setup(9L, allowed.resolve("contract.pdf").toString());
        ReflectionTestUtils.setField(service, "uploadDir", allowed.toString());
        assertUnavailable(() -> service.open(9L));
    }

    @Test
    void PDF가_아닌_파일은_거부한다() throws Exception {
        Path file = tempDir.resolve(NAME);
        Files.writeString(file, "not a pdf");
        setup(7L, file.toString());
        assertUnavailable(() -> service.open(7L));
    }

    @Test
    void 심볼릭_링크는_원본_폴더_안에_있어도_거부한다() throws Exception {
        Path target = tempDir.resolve("real.pdf");
        Files.writeString(target, "%PDF-1.4 synthetic test");
        Path link = tempDir.resolve(NAME);
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable in test environment");
        }
        setup(7L, link.toString());
        assertUnavailable(() -> service.open(7L));
    }

    @Test
    void 다운로드_슬롯은_응답_스트림이_닫힐_때까지_유지된다() throws Exception {
        Path file = tempDir.resolve(NAME);
        Files.writeString(file, "%PDF-1.4 synthetic test");
        setup(7L, file.toString());
        var first = service.open(7L);
        var second = service.open(7L);
        try {
            assertThatThrownBy(() -> service.open(7L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                            .isEqualTo(ErrorCode.WARRANTY_FILE_BUSY));
        } finally {
            first.resource().getInputStream().close();
            second.resource().getInputStream().close();
        }
        try (var reopened = service.open(7L).resource().getInputStream()) {
            assertThat(reopened.readAllBytes()).startsWith("%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }

    private void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.WARRANTY_FILE_UNAVAILABLE));
    }
}
