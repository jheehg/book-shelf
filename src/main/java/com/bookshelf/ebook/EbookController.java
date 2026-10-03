package com.bookshelf.ebook;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EbookController {
    private final EbookService ebooks;
    public EbookController(EbookService ebooks) { this.ebooks = ebooks; }
    @GetMapping("/api/books/{isbn}/ebook")
    public EbookView offer(@PathVariable String isbn) { return ebooks.offer(isbn); }
}
