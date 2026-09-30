package com.bookshelf.publicview;

import java.util.List;

/** member id를 담지 않는다. 공개 쪽에서 식별자는 slug뿐이다 */
public record PublicShelf(String slug, String displayName, int publicCount, List<PublicEntry> entries) {
}
