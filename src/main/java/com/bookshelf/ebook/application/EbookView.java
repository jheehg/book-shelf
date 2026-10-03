package com.bookshelf.ebook.application;

import java.time.Instant;

public record EbookView(String status, Integer price, String link, Instant fetchedAt, String attribution) {
    public static EbookView matched(int price, String link, Instant at) { return new EbookView("MATCHED", price, link, at, "powered by Google"); }
    public static EbookView noMatch(Instant at) { return new EbookView("NO_MATCH", null, null, at, null); }
    public static EbookView unknown() { return new EbookView("UNKNOWN", null, null, null, null); }
}
