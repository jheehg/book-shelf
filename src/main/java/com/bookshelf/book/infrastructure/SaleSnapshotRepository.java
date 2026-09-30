package com.bookshelf.book.infrastructure;

import com.bookshelf.book.domain.SaleSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleSnapshotRepository extends JpaRepository<SaleSnapshot, String> {
}
