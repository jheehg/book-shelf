package com.bookshelf.member.infrastructure.persistence;

import com.bookshelf.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByAppleSub(String appleSub);

    boolean existsBySlug(String slug);

    Optional<Member> findBySlug(String slug);
}
