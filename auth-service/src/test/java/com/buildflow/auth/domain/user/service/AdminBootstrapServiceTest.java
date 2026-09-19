package com.buildflow.auth.domain.user.service;

import com.buildflow.auth.domain.user.entity.User;
import com.buildflow.auth.domain.user.repository.UserRepository;
import com.buildflow.auth.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapServiceTest {

    @Mock UserRepository userRepository;
    @Mock BCryptPasswordEncoder passwordEncoder;
    @InjectMocks AdminBootstrapService bootstrapService;

    @Test
    void createsOnlyFixedSlotWithEncodedPassword() {
        when(userRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("ninechars")).thenReturn("bcrypt-hash");

        bootstrapService.create("admin", "관리자", "ninechars");

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(user.capture());
        assertEquals(1L, user.getValue().getId());
        assertEquals("admin", user.getValue().getLoginId());
        assertEquals("bcrypt-hash", user.getValue().getPassword());
    }

    @Test
    void refusesSecondAdminWithoutChangingPasswordOrDatabase() {
        when(userRepository.count()).thenReturn(1L);

        assertThrows(BusinessException.class,
                () -> bootstrapService.create("admin", "관리자", "secure-password"));

        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void rejectsWeakOrMalformedCredentialsBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> bootstrapService.create("a", "관리자", "secure-password"));
        assertThrows(IllegalArgumentException.class,
                () -> bootstrapService.create("admin", "관리자", "eightchr"));
        assertThrows(IllegalArgumentException.class,
                () -> bootstrapService.create("admin", " ", "secure-password"));

        verifyNoInteractions(userRepository, passwordEncoder);
    }
}
