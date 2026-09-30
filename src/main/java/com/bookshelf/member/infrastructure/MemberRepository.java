package com.bookshelf.member.infrastructure;

import com.bookshelf.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
