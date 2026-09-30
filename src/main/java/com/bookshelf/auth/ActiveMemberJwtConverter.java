package com.bookshelf.auth;

import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 서명이 맞아도 회원이 없거나 탈퇴했으면 인증하지 않는다. access 토큰은 30분 살아 있으므로
 * 탈퇴 직후의 토큰이 검색·책·도서관 API를 계속 쓰지 못하게 모든 /api 요청에서 한 번 본다(PK 조회 한 번).
 * 실패는 필터의 다른 인증 실패와 같은 401 본문이다(ApiAuthenticationEntryPoint).
 */
@Component
public class ActiveMemberJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JdbcClient jdbc;

    public ActiveMemberJwtConverter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long memberId;
        try {
            memberId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("sub가 회원 id가 아니다");
        }
        boolean active = jdbc.sql("SELECT EXISTS (SELECT 1 FROM member WHERE id = :id AND deleted_at IS NULL)")
                .param("id", memberId).query(Boolean.class).single();
        if (!active) {
            throw new InvalidBearerTokenException("없거나 탈퇴한 회원의 토큰: " + memberId);
        }
        // 권한 목록을 받는 생성자만 "인증됨"으로 표시한다(한 인자 생성자는 인증되지 않은 토큰이라 403이 된다)
        return new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES, jwt.getSubject());
    }
}
