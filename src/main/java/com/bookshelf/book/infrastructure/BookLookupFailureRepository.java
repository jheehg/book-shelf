package com.bookshelf.book.infrastructure;

import com.bookshelf.book.domain.BookLookupFailure;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookLookupFailureRepository extends JpaRepository<BookLookupFailure, String> {
}
