package com.buildflow.auth.domain.user.service;

import com.buildflow.auth.domain.user.entity.User;
import com.buildflow.auth.domain.user.repository.UserRepository;
import com.buildflow.auth.global.exception.BusinessException;
import com.buildflow.auth.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminBootstrapService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public void create(String loginId, String name, String password) {
        if (loginId == null || !loginId.matches("[A-Za-z0-9._-]{3,50}")) {
            throw new IllegalArgumentException("관리자 아이디는 영문, 숫자, 점, 밑줄, 하이픈 3~50자여야 합니다.");
        }
        if (name == null || name.isBlank() || name.length() > 50) {
            throw new IllegalArgumentException("관리자 표시 이름은 1~50자여야 합니다.");
        }
        if (password == null || password.length() < 9) {
            throw new IllegalArgumentException("관리자 비밀번호는 9자 이상이어야 합니다.");
        }
        if (userRepository.count() != 0) {
            throw new BusinessException(ErrorCode.ADMIN_ALREADY_EXISTS);
        }

        User user = User.builder()
                .loginId(loginId)
                .password(passwordEncoder.encode(password))
                .name(name)
                .build();
        // 고정 PK=1과 UNIQUE login_id가 동시 bootstrap 요청도 DB에서 차단한다.
        userRepository.saveAndFlush(user);
    }
}
