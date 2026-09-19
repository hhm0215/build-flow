package com.buildflow.auth.domain.user.service;

import com.buildflow.auth.domain.user.dto.LoginRequest;
import com.buildflow.auth.domain.user.dto.RefreshRequest;
import com.buildflow.auth.domain.user.dto.TokenResponse;
import com.buildflow.auth.domain.user.entity.User;
import com.buildflow.auth.domain.user.repository.UserRepository;
import com.buildflow.auth.global.exception.BusinessException;
import com.buildflow.auth.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock JwtTokenProvider tokens;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock BCryptPasswordEncoder encoder;
    @InjectMocks AuthService authService;

    @Test
    void logsInOnlyFixedAdminByLoginId() {
        User admin = User.builder().loginId("admin").password("hash").name("관리자").build();
        when(userRepository.findByLoginId("admin")).thenReturn(Optional.of(admin));
        when(encoder.matches("password", "hash")).thenReturn(true);
        when(tokens.generateAccessToken(1L, "admin")).thenReturn("access");
        when(tokens.generateRefreshToken(1L)).thenReturn("refresh");
        when(tokens.getRefreshTokenExpiration()).thenReturn(1000L);
        when(tokens.getAccessTokenExpiration()).thenReturn(500L);
        when(redis.opsForValue()).thenReturn(values);

        TokenResponse response = authService.login(new LoginRequest("admin", "password"));

        assertEquals("access", response.accessToken());
        assertEquals("refresh", response.refreshToken());
        verify(values).set("auth:refresh:1", "refresh", 1000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void rejectsWrongPasswordWithoutIssuingTokens() {
        User admin = User.builder().loginId("admin").password("hash").name("관리자").build();
        when(userRepository.findByLoginId("admin")).thenReturn(Optional.of(admin));
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        assertThrows(BusinessException.class, () -> authService.login(new LoginRequest("admin", "wrong")));
        verifyNoInteractions(tokens, redis);
    }

    @Test
    void refreshUsesOnlyCurrentRefreshTokenAndRotatesIt() {
        User admin = User.builder().loginId("admin").password("hash").name("관리자").build();
        when(tokens.getUserId("old-refresh", "refresh")).thenReturn(1L);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("auth:refresh:1")).thenReturn("old-refresh");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(tokens.generateAccessToken(1L, "admin")).thenReturn("new-access");
        when(tokens.generateRefreshToken(1L)).thenReturn("new-refresh");
        when(tokens.getRefreshTokenExpiration()).thenReturn(1000L);

        TokenResponse response = authService.refresh(new RefreshRequest("old-refresh"));

        assertEquals("new-access", response.accessToken());
        verify(values).set("auth:refresh:1", "new-refresh", 1000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void logoutValidatesAccessTypeThenRevokesTokens() {
        when(tokens.getUserId("access", "access")).thenReturn(1L);
        when(tokens.getRemainingExpiration("access")).thenReturn(1000L);
        when(redis.opsForValue()).thenReturn(values);

        authService.logout("access");

        verify(values).set("auth:blacklist:access", "logout", 1000L, TimeUnit.MILLISECONDS);
        verify(redis).delete("auth:refresh:1");
    }
}
