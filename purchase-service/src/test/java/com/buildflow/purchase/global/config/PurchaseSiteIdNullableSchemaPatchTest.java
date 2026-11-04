package com.buildflow.purchase.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseSiteIdNullableSchemaPatchTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PurchaseSiteIdNullableSchemaPatch patch = new PurchaseSiteIdNullableSchemaPatch(jdbc);

    @Test
    void relaxesNotNullOnlyWhenColumnIsStillNotNull() {
        when(jdbc.queryForList(PurchaseSiteIdNullableSchemaPatch.NULLABILITY_SQL, String.class))
                .thenReturn(List.of("NO"));

        patch.run(null);

        verify(jdbc).execute(PurchaseSiteIdNullableSchemaPatch.ALTER_SQL);
    }

    @Test
    void alreadyNullableOrMissingTableIsNoOp() {
        when(jdbc.queryForList(PurchaseSiteIdNullableSchemaPatch.NULLABILITY_SQL, String.class))
                .thenReturn(List.of("YES"), List.of());

        patch.run(null);
        patch.run(null);

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void failureDoesNotBlockStartup() {
        when(jdbc.queryForList(PurchaseSiteIdNullableSchemaPatch.NULLABILITY_SQL, String.class))
                .thenReturn(List.of("NO"));
        doThrow(new DataAccessResourceFailureException("denied"))
                .when(jdbc).execute(PurchaseSiteIdNullableSchemaPatch.ALTER_SQL);

        assertDoesNotThrow(() -> patch.run(null));
    }
}
