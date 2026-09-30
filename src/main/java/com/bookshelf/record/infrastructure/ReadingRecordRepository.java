package com.bookshelf.record.infrastructure;

import com.bookshelf.record.domain.ReadingRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReadingRecordRepository extends JpaRepository<ReadingRecord, Long> {

    List<ReadingRecord> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId);

    /** 소유권은 쿼리 조건으로 건다 — 가져온 뒤 비교하지 않는다 */
    Optional<ReadingRecord> findByIdAndMemberId(Long id, Long memberId);

    boolean existsByMemberIdAndIsbn13(Long memberId, String isbn13);
}
