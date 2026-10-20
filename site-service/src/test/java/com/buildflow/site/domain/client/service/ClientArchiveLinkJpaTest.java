package com.buildflow.site.domain.client.service;

import com.buildflow.site.domain.client.entity.Client;
import com.buildflow.site.domain.client.repository.ClientRepository;
import com.buildflow.site.domain.site.dto.SiteCreateRequest;
import com.buildflow.site.domain.site.repository.SiteRepository;
import com.buildflow.site.domain.site.service.SiteService;
import com.buildflow.site.global.exception.BusinessException;
import com.buildflow.site.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
@Import({ClientService.class, SiteService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ClientArchiveLinkJpaTest {

    private final ClientService clientService;
    private final SiteService siteService;
    private final ClientRepository clientRepository;
    private final SiteRepository siteRepository;
    private final TransactionTemplate transactionTemplate;

    ClientArchiveLinkJpaTest(ClientService clientService, SiteService siteService,
                             ClientRepository clientRepository, SiteRepository siteRepository,
                             PlatformTransactionManager transactionManager) {
        this.clientService = clientService;
        this.siteService = siteService;
        this.clientRepository = clientRepository;
        this.siteRepository = siteRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void linkingWaitsForConcurrentArchiveCommitThenRejectsArchivedClient() throws Exception {
        Long clientId = clientRepository.saveAndFlush(Client.builder().companyName("[QA] 경합 거래처").build()).getId();
        SiteCreateRequest request = new SiteCreateRequest();
        ReflectionTestUtils.setField(request, "siteName", "[QA] 경합 현장");
        ReflectionTestUtils.setField(request, "clientId", clientId);

        CountDownLatch archivedButUncommitted = new CountDownLatch(1);
        CountDownLatch allowArchiveCommit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> archive = workers.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                clientService.archive(clientId);
                archivedButUncommitted.countDown();
                await(allowArchiveCommit);
            }));
            assertTrue(archivedButUncommitted.await(5, TimeUnit.SECONDS));

            CountDownLatch linkStarted = new CountDownLatch(1);
            Future<?> link = workers.submit(() -> {
                linkStarted.countDown();
                siteService.create(request);
            });
            assertTrue(linkStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> link.get(200, TimeUnit.MILLISECONDS));

            allowArchiveCommit.countDown();
            archive.get(5, TimeUnit.SECONDS);
            ExecutionException rejection = assertThrows(ExecutionException.class, () -> link.get(5, TimeUnit.SECONDS));
            assertEquals(ErrorCode.CLIENT_ARCHIVED_NOT_ASSIGNABLE,
                    ((BusinessException) rejection.getCause()).getErrorCode());
            assertEquals(0, siteRepository.count());
        } finally {
            allowArchiveCommit.countDown();
            workers.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Archive commit was not released in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Archive worker was interrupted", e);
        }
    }
}
