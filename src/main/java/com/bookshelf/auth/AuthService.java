package com.bookshelf.auth;

import com.bookshelf.auth.apple.AppleIdTokenVerifier;
import com.bookshelf.auth.apple.AppleIdentity;
import com.bookshelf.member.application.SlugGenerator;
import com.bookshelf.member.domain.Member;
import com.bookshelf.member.infrastructure.MemberRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Service
public class AuthService {

    static final String DEFAULT_NAME = "독자";

    private final AppleIdTokenVerifier apple;
    private final MemberRepository members;
    private final SlugGenerator slugs;
    private final TokenIssuer tokens;
    private final RefreshTokenStore refresh;
    private final Clock clock;

    public AuthService(AppleIdTokenVerifier apple, MemberRepository members, SlugGenerator slugs,
                       TokenIssuer tokens, RefreshTokenStore refresh, Clock clock) {
        this.apple = apple;
        this.members = members;
        this.slugs = slugs;
        this.tokens = tokens;
        this.refresh = refresh;
        this.clock = clock;
    }

    /**
     * Apple 토큰 검증은 JWKS를 부를 수 있다(캐시가 비었을 때). 그래서 검증을 먼저, 트랜잭션 밖에서 한다.
     * Apple은 이름을 처음 로그인 때만 준다 — 이미 있는 회원의 이름은 바꾸지 않는다.
     */
    public AuthTokens loginWithApple(String identityToken, String rawNonce, String displayName) {
        AppleIdentity id = apple.verify(identityToken, rawNonce);
        Instant now = clock.instant();
        Optional<Member> existing = members.findByAppleSub(id.sub());
        Member member = existing.orElseGet(() -> register(id, displayName, now));
        return issue(member.getId(), now, existing.isEmpty());
    }

    public AuthTokens refresh(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new UnauthorizedException("refresh 토큰 없음");
        }
        Instant now = clock.instant();
        RefreshTokenStore.Rotated r = refresh.rotate(raw, now);
        return new AuthTokens(tokens.access(r.memberId(), now), r.newRaw(), tokens.accessTtlSeconds(), false);
    }

    public void logout(String raw) {
        if (raw != null && !raw.isBlank()) {
            refresh.revoke(raw);
        }
    }

    /** save 한 번이라 리포지터리의 트랜잭션으로 충분하다. 같은 클래스에서 부르므로 @Transactional을 붙여도 적용되지 않는다 */
    private Member register(AppleIdentity id, String displayName, Instant now) {
        String name = displayName == null || displayName.isBlank() ? DEFAULT_NAME : displayName;
        return members.save(Member.registerWithApple(id.sub(), slugs.next(), name, now));
    }

    private AuthTokens issue(long memberId, Instant now, boolean newMember) {
        return new AuthTokens(tokens.access(memberId, now), refresh.issue(memberId, now),
                tokens.accessTtlSeconds(), newMember);
    }
}
