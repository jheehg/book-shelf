package com.bookshelf.ebook;

import com.bookshelf.common.Isbn13;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Component
public class EbookWriter {
    private final JdbcClient jdbc;
    public EbookWriter(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Optional<EbookView> findFresh(Isbn13 isbn, Instant notBefore) {
        return jdbc.sql("SELECT matched, price, link, fetched_at FROM ebook_offer WHERE isbn13 = :isbn AND fetched_at > :notBefore")
                .param("isbn", isbn.value()).param("notBefore", Timestamp.from(notBefore)).query((rs, n) -> {
                    Instant at = rs.getTimestamp("fetched_at").toInstant();
                    return rs.getBoolean("matched") ? EbookView.matched(rs.getInt("price"), rs.getString("link"), at) : EbookView.noMatch(at);
                }).optional();
    }
    public void save(Isbn13 isbn, EbookMatcher.Result result, Instant now) {
        EbookCandidate offer = result instanceof EbookMatcher.Matched matched ? matched.offer() : null;
        jdbc.sql("""
                INSERT INTO ebook_offer (isbn13, matched, price, link, fetched_at) VALUES (:isbn, :matched, :price, :link, :now)
                ON CONFLICT (isbn13) DO UPDATE SET matched = EXCLUDED.matched, price = EXCLUDED.price,
                    link = EXCLUDED.link, fetched_at = EXCLUDED.fetched_at
                """).param("isbn", isbn.value()).param("matched", offer != null)
                .param("price", offer == null ? null : offer.retailPrice()).param("link", offer == null ? null : offer.link())
                .param("now", Timestamp.from(now)).update();
    }
}
