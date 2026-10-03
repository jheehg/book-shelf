package com.bookshelf.ebook.application;

import com.bookshelf.ebook.domain.EbookMatcher;
import com.bookshelf.ebook.infrastructure.persistence.EbookWriter;

import com.bookshelf.book.application.BookLookupService;
import com.bookshelf.book.application.BookView;
import com.bookshelf.common.Isbn13;
import com.bookshelf.common.NotFoundException;
import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import com.bookshelf.provider.domain.UserQuotaExceededException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;

@Service
public class EbookService {
    private static final Duration TTL = Duration.ofHours(24);
    private final BookLookupService books; private final EbookPricing pricing; private final EbookWriter writer; private final Clock clock;
    public EbookService(BookLookupService books, EbookPricing pricing, EbookWriter writer, Clock clock) { this.books = books; this.pricing = pricing; this.writer = writer; this.clock = clock; }
    public EbookView offer(String rawIsbn) {
        Isbn13 isbn = Isbn13.parse(rawIsbn);
        BookView book = books.findLocal(isbn).orElseThrow(() -> new NotFoundException("책 " + isbn.value()));
        var language = isbn.languageHint();
        if (language.isEmpty() || book.title() == null || book.author() == null) return EbookView.noMatch(null);
        var now = clock.instant();
        var cached = writer.findFresh(isbn, now.minus(TTL));
        if (cached.isPresent()) return cached.get();
        try {
            EbookMatcher.Result result = EbookMatcher.match(book.title(), book.author(), language.get(), pricing.candidates(book.title(), book.author(), CallPurpose.INTERACTIVE));
            writer.save(isbn, result, now);
            return result instanceof EbookMatcher.Matched matched ? EbookView.matched(matched.offer().retailPrice(), matched.offer().link(), now) : EbookView.noMatch(now);
        } catch (ProviderUnavailableException | UserQuotaExceededException exception) { return EbookView.unknown(); }
    }
}
