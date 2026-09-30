package com.bookshelf.record.infrastructure;

import com.bookshelf.record.domain.ReadingRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReadingRecordRepository extends JpaRepository<ReadingRecord, Long> {
}
