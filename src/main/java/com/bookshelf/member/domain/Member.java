package com.bookshelf.member.domain;

import com.bookshelf.common.DomainException;
import com.bookshelf.record.domain.Visibility;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.regex.Pattern;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

    /** 3~30자, 소문자·숫자·하이픈, 하이픈으로 시작·끝나지 않는다 */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,28}[a-z0-9]");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String slug;

    private String displayName;

    private boolean shelfPublic;

    @Enumerated(EnumType.STRING)
    private Visibility defaultVisibility;

    private Instant createdAt;

    private Instant deletedAt;

    private String appleSub;

    public static Member register(String slug, String displayName, Instant now) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new DomainException("slug 규칙 위반: " + slug);
        }
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 30) {
            throw new DomainException("표시 이름은 1~30자다");
        }
        Member m = new Member();
        m.slug = slug;
        m.displayName = name;
        m.shelfPublic = false;
        m.defaultVisibility = Visibility.PRIVATE;
        m.createdAt = now;
        return m;
    }

    /** Apple 로그인으로 처음 들어온 사람. 서재는 닫혀 있고 기본 공개 범위는 비공개다(register와 같다) */
    public static Member registerWithApple(String appleSub, String slug, String displayName, Instant now) {
        if (appleSub == null || appleSub.isBlank()) {
            throw new DomainException("Apple 사용자 식별자가 없다");
        }
        Member m = register(slug, displayName, now);
        m.appleSub = appleSub;
        return m;
    }

    public void changeSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new DomainException("slug 규칙 위반: " + slug);
        }
        this.slug = slug;
    }

    public void rename(String displayName) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 30) {
            throw new DomainException("표시 이름은 1~30자다");
        }
        this.displayName = name;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void openShelf() {
        this.shelfPublic = true;
    }

    public void closeShelf() {
        this.shelfPublic = false;
    }

    /** 공개 조회는 deleted_at으로도 거르지만, 스위치까지 내려 두 겹으로 닫는다 */
    public void withdraw(Instant now) {
        this.shelfPublic = false;
        this.deletedAt = now;
    }
}
