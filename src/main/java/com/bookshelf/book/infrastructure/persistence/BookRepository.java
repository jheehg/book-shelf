package com.bookshelf.book.infrastructure.persistence;

import com.bookshelf.book.domain.Book;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository extends JpaRepository<Book, String> {
}
