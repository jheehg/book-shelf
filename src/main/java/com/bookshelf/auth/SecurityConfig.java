package com.bookshelf.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * /api/** 는 인증 필수, 그 밖(공개 웹·정적 파일·health)은 열어 둔다.
 * CSRF: API는 Authorization 헤더로만 인증한다(브라우저가 자동으로 붙이지 않는다) — 켜도 무의미하다.
 * 공개 웹은 GET만 있고 쿠키를 쓰지 않는다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ApiAuthenticationEntryPoint entryPoint,
                                    ActiveMemberJwtConverter activeMember) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(apiTokensOnly())
                        .jwt(j -> j.jwtAuthenticationConverter(activeMember))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .build();
    }

    /**
     * Bearer 헤더를 읽는 곳을 인증이 필요한 /api로 한정한다. 스프링은 permitAll 경로에서도 헤더가 있으면
     * 검증해 401을 낸다 — 앱은 저장된 access 토큰을 모든 요청에 붙이므로, 만료된 토큰 때문에
     * /api/auth/refresh 자체가 막혀 30분마다 로그아웃된다.
     */
    static BearerTokenResolver apiTokensOnly() {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return request -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (!path.startsWith("/api/") || path.startsWith("/api/auth/")) {
                return null;
            }
            return header.resolve(request);
        };
    }
}
