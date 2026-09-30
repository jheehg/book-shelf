package com.bookshelf.member.application;

import com.bookshelf.member.domain.Member;

public record MeView(String slug, String displayName, boolean shelfPublic, String defaultVisibility) {

    static MeView of(Member m) {
        return new MeView(m.getSlug(), m.getDisplayName(), m.isShelfPublic(), m.getDefaultVisibility().name());
    }
}
