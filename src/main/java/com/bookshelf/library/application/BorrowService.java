package com.bookshelf.library.application;

import com.bookshelf.common.Isbn13;
import com.bookshelf.library.HoldingState;
import com.bookshelf.library.LibraryAvailability;
import com.bookshelf.member.application.MemberService;
import com.bookshelf.provider.CallPurpose;
import com.bookshelf.provider.ProviderUnavailableException;
import com.bookshelf.provider.UserQuotaExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 빌리기 칸. 도서관은 member_library에서만 꺼낸다 — 요청으로 받지 않는다(설계 6-3).
 * @Transactional 없음 — 도서관마다 정보나루를 부른다. 저장(HoldingCache.save)은 한 문장이라 따로 트랜잭션이 필요 없다.
 */
@Slf4j
@Service
public class BorrowService {

    private final MemberService members;
    private final MyLibraries myLibraries;
    private final HoldingCache cache;
    private final LibraryAvailability availability;
    private final Clock clock;

    public BorrowService(MemberService members, MyLibraries myLibraries, HoldingCache cache,
                         LibraryAvailability availability, Clock clock) {
        this.members = members;
        this.myLibraries = myLibraries;
        this.cache = cache;
        this.availability = availability;
        this.clock = clock;
    }

    public List<HoldingView> availability(long memberId, String rawIsbn) {
        Isbn13 isbn = Isbn13.parse(rawIsbn);
        members.active(memberId);
        List<HoldingView> out = new ArrayList<>();
        for (LibraryView lib : myLibraries.list(memberId)) {
            out.add(holding(lib, isbn));
        }
        return out;
    }

    /** 한 도서관의 실패는 그 도서관만 UNKNOWN이다. 다음 도서관은 따로 부른다 */
    private HoldingView holding(LibraryView lib, Isbn13 isbn) {
        Instant now = clock.instant();
        Optional<HoldingCache.Cached> cached = cache.find(lib.libCode(), isbn, now);
        if (cached.isPresent()) {
            return new HoldingView(lib.libCode(), lib.name(), cached.get().state(), cached.get().fetchedAt());
        }
        try {
            HoldingState state = availability.holding(lib.libCode(), isbn, CallPurpose.INTERACTIVE);
            cache.save(lib.libCode(), isbn, state, now);
            return new HoldingView(lib.libCode(), lib.name(), state, state == HoldingState.UNKNOWN ? null : now);
        } catch (ProviderUnavailableException | UserQuotaExceededException e) {
            log.info("빌리기 확인 불가: lib={} isbn={} reason={}", lib.libCode(), isbn.value(), e.getMessage());
            return new HoldingView(lib.libCode(), lib.name(), HoldingState.UNKNOWN, null);
        }
    }
}
