package com.buildflow.notification.domain.warranty.service;

import com.buildflow.notification.domain.warranty.entity.DefectWarranty;
import com.buildflow.notification.domain.warranty.repository.DefectWarrantyRepository;
import com.buildflow.notification.global.exception.BusinessException;
import com.buildflow.notification.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.core.io.InputStreamResource;

/** 업로드 사본만 제공한다. 인증은 컨트롤러에서 Gateway와 별도로 재검증한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WarrantyOriginalFileService {

    private static final Pattern STORED_NAME = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");

    private final DefectWarrantyRepository repository;
    private final Semaphore downloadSlots = new Semaphore(2);

    @Value("${app.upload-dir:./uploads/warranties}")
    private String uploadDir;

    public OpenedPdf open(Long id) {
        if (!downloadSlots.tryAcquire()) {
            throw new BusinessException(ErrorCode.WARRANTY_FILE_BUSY);
        }
        SeekableByteChannel channel = null;
        boolean handedOff = false;
        try {
            DefectWarranty warranty = repository.findById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.WARRANTY_NOT_FOUND));
            Path file = confinedPath(warranty.getFilePath());
            if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            channel = Files.newByteChannel(file,
                    Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            long size = channel.size();
            if (size < 5 || size > DefectWarrantyService.MAX_PDF_BYTES) throw unavailable();
            ByteBuffer magic = ByteBuffer.allocate(5);
            while (magic.hasRemaining() && channel.read(magic) != -1) { /* read header */ }
            byte[] header = magic.array();
            if (magic.hasRemaining() || header[0] != '%' || header[1] != 'P' || header[2] != 'D'
                    || header[3] != 'F' || header[4] != '-') throw unavailable();
            channel.position(0);
            InputStream stream = new FilterInputStream(Channels.newInputStream(channel)) {
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public void close() throws IOException {
                    if (closed.compareAndSet(false, true)) {
                        try { super.close(); } finally { downloadSlots.release(); }
                    }
                }
            };
            handedOff = true;
            return new OpenedPdf(new InputStreamResource(stream), size);
        } catch (IOException | SecurityException e) {
            throw unavailable();
        } finally {
            if (!handedOff) {
                if (channel != null) {
                    try { channel.close(); } catch (IOException ignored) { /* unavailable already */ }
                }
                downloadSlots.release();
            }
        }
    }

    public record OpenedPdf(InputStreamResource resource, long size) {}

    private Path confinedPath(String storedPath) {
        if (storedPath == null) return null;
        try {
            Path base = Path.of(uploadDir).toAbsolutePath().normalize();
            Path recorded = Path.of(storedPath);
            if (!recorded.isAbsolute() || !recorded.equals(recorded.normalize())
                    || hasSymlinkAncestor(base) || !base.equals(recorded.getParent())
                    || !STORED_NAME.matcher(recorded.getFileName().toString()).matches()) {
                return null;
            }
            return recorded;
        } catch (InvalidPathException | SecurityException e) {
            return null;
        }
    }

    private boolean hasSymlinkAncestor(Path base) {
        for (Path current = base; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.WARRANTY_FILE_UNAVAILABLE);
    }
}
