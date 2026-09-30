package com.bookshelf.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인증 실패는 모두 같은 401. 없음·만료·위조를 구분하면 공격자에게 힌트가 된다. 이유는 로그에만 남긴다.
 * 필터에서 거부된 요청은 @RestControllerAdvice에 닿지 않으므로 여기서 직접 쓴다.
 */
@Slf4j
@Component
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String BODY = "{\"code\":\"UNAUTHORIZED\",\"message\":\"다시 로그인해 주세요\"}";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException e) throws IOException {
        log.debug("인증 실패 path={} reason={}", request.getRequestURI(), e.getMessage());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}
