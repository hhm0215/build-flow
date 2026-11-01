package com.buildflow.auth.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Auth
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    TOKEN_BLACKLISTED(HttpStatus.UNAUTHORIZED, "로그아웃된 토큰입니다."),
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 존재하지 않습니다."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "이미 사용된 리프레시 토큰입니다. 다시 로그인해 주세요."),
    // 직전 refresh가 유예 시간 안에 다시 들어옴(여러 탭 동시 재발급). 세션은 유지되며 다른 탭이 받은 새 토큰을 쓰면 된다.
    REFRESH_IN_PROGRESS(HttpStatus.CONFLICT, "다른 창에서 로그인 연장이 막 처리되었습니다. 잠시 후 다시 시도해 주세요."),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인 유지 기간이 끝났습니다. 다시 로그인해 주세요."),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    ADMIN_ALREADY_EXISTS(HttpStatus.CONFLICT, "관리자 계정이 이미 존재합니다.");

    private final HttpStatus status;
    private final String message;
}
