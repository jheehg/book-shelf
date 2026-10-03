package com.bookshelf.auth.infrastructure.persistence;

import com.bookshelf.auth.infrastructure.security.JwtProperties;
import com.bookshelf.member.infrastructure.persistence.MemberDeletion;
import com.bookshelf.auth.application.UnauthorizedException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * refresh 토큰: 32바이트 난수. DB에는 SHA-256 해시만.
 * 회전: 쓰면 used_at이 찍히고 새 토큰이 나간다. 찍힌 토큰이 다시 오면 도난으로 보고 전부 폐기한다.
 */
@Slf4j
@Component
public class RefreshTokenStore {

    public record Rotated(long memberId, String newRaw) {
    }

    private record Row(long id, long memberId, Instant expiresAt, Instant usedAt) {
    }

    private final JdbcClient jdbc;
    private final JwtProperties props;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(JdbcClient jdbc, JwtProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public String issue(long memberId, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                INSERT INTO refresh_token (member_id, token_hash, expires_at, created_at)
                VALUES (:m, :h, :exp, :now)
                """)
                .param("m", memberId)
                .param("h", hash(raw))
                .param("exp", Timestamp.from(now.plus(props.refreshTokenTtl())))
                .param("now", Timestamp.from(now))
                .update();
        return raw;
    }

    /**
     * 한 트랜잭션 안에서 행을 잠그고 판정한다 — 같은 토큰의 동시 요청 둘이 모두 통과하지 않게.
     * noRollbackFor: 재사용을 감지해 지운 행은 UnauthorizedException과 함께 롤백되면 안 된다(폐기가 남아야 한다)
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotated rotate(String raw, Instant now) {
        // 회원 행도 함께 잠근다(FOR UPDATE는 조인한 두 행을 모두 잠근다) — 탈퇴(MemberDeletion)와 줄을 세워
        // 탈퇴가 끝난 뒤 새 refresh가 남지 않게. 탈퇴한 회원의 토큰은 모르는 토큰과 같다
        Row row = jdbc.sql("""
                        SELECT t.id, t.member_id, t.expires_at, t.used_at
                        FROM refresh_token t JOIN member m ON m.id = t.member_id
                        WHERE t.token_hash = :h AND m.deleted_at IS NULL
                        FOR UPDATE
                        """)
                .param("h", hash(raw))
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getLong("member_id"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("used_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("used_at", OffsetDateTime.class).toInstant()))
                .optional()
                .orElseThrow(() -> new UnauthorizedException("모르는 refresh 토큰"));

        if (row.usedAt() != null) {
            revokeAll(row.memberId());
            log.warn("refresh 토큰 재사용 감지 — 회원 {}의 refresh 토큰을 모두 폐기했다", row.memberId());
            throw new UnauthorizedException("이미 쓴 refresh 토큰");
        }
        if (!row.expiresAt().isAfter(now)) {
            throw new UnauthorizedException("만료된 refresh 토큰");
        }
        jdbc.sql("UPDATE refresh_token SET used_at = :now WHERE id = :id")
                .param("now", Timestamp.from(now)).param("id", row.id()).update();
        return new Rotated(row.memberId(), issue(row.memberId(), now));
    }

    /** 만료된 토큰은 어차피 401이다. 회전된(used_at) 토큰도 만료 전까지는 재사용 감지에 필요하므로 만료 후에만 지운다 */
    public int purgeExpired(Instant now) {
        return jdbc.sql("DELETE FROM refresh_token WHERE expires_at < :now")
                .param("now", Timestamp.from(now))
                .update();
    }

    public void revoke(String raw) {
        jdbc.sql("DELETE FROM refresh_token WHERE token_hash = :h").param("h", hash(raw)).update();
    }

    public void revokeAll(long memberId) {
        jdbc.sql("DELETE FROM refresh_token WHERE member_id = :m").param("m", memberId).update();
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
