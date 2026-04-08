package com.buildflow.auth.domain.user.service;

import com.buildflow.auth.domain.user.dto.*;
import com.buildflow.auth.domain.user.entity.User;
import com.buildflow.auth.domain.user.repository.UserRepository;
import com.buildflow.auth.global.exception.BusinessException;
import com.buildflow.auth.global.exception.ErrorCode;
import com.buildflow.auth.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final String REFRESH_TOKEN_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public void signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .name(request.name())
                .role(request.role())
                .build();

        userRepository.save(user);
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + user.getId(),
                refreshToken,
                jwtTokenProvider.getRefreshTokenExpiration(),
                TimeUnit.MILLISECONDS
        );

        return TokenResponse.of(accessToken, refreshToken, jwtTokenProvider.getAccessTokenExpiration());
    }

    public TokenResponse refresh(RefreshRequest request) {
        String refreshToken = request.refreshToken();
        Long userId = jwtTokenProvider.getUserId(refreshToken);

        String storedToken = redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String newAccessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + userId,
                newRefreshToken,
                jwtTokenProvider.getRefreshTokenExpiration(),
                TimeUnit.MILLISECONDS
        );

        return TokenResponse.of(newAccessToken, newRefreshToken, jwtTokenProvider.getAccessTokenExpiration());
    }

    public void logout(String accessToken) {
        long remaining = jwtTokenProvider.getRemainingExpiration(accessToken);
        if (remaining > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + accessToken,
                    "logout",
                    remaining,
                    TimeUnit.MILLISECONDS
            );
        }

        Long userId = jwtTokenProvider.getUserId(accessToken);
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
    }
}
