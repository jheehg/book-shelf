package com.bookshelf.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * /api/** 는 인증 필수, 그 밖(공개 웹·정적 파일·health)은 열어 둔다.
 * CSRF: API는 Authorization 헤더로만 인증한다(브라우저가 자동으로 붙이지 않는다) — 켜도 무의미하다.
 * 공개 웹은 GET만 있고 쿠키를 쓰지 않는다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ApiAuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o.jwt(j -> { }).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .build();
    }
}
