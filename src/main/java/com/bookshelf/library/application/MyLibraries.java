package com.bookshelf.library.application;

import com.bookshelf.common.ClockConfig;
import com.bookshelf.common.DomainException;
import com.bookshelf.member.application.MemberService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

@Service
public class MyLibraries {

    public static final int MAX_LIBRARIES = 3;

    private final MemberService members;
    private final MyLibrariesWriter writer;
    private final LibraryWriter libraries;
    private final LibrarySearch search;
    private final Clock clock;

    public MyLibraries(MemberService members, MyLibrariesWriter writer, LibraryWriter libraries,
                       LibrarySearch search, Clock clock) {
        this.members = members;
        this.writer = writer;
        this.libraries = libraries;
        this.search = search;
        this.clock = clock;
    }

    public List<LibraryView> list(long memberId) {
        members.active(memberId);
        return writer.current(memberId).stream().map(code -> search.find(code).orElseThrow()).toList();
    }

    public List<LibraryView> replace(long memberId, List<String> libCodes) {
        members.active(memberId);
        if (libCodes == null) {
            throw new DomainException("도서관 목록이 없다");
        }
        if (libCodes.size() > MAX_LIBRARIES) {
            throw new DomainException("도서관은 " + MAX_LIBRARIES + "곳까지 등록할 수 있다");
        }
        if (new HashSet<>(libCodes).size() != libCodes.size()) {
            throw new DomainException("같은 도서관이 두 번 있다");
        }
        for (String code : libCodes) {
            if (code == null || !libraries.exists(code)) {
                throw new DomainException("모르는 도서관: " + code);
            }
        }
        writer.replace(memberId, List.copyOf(libCodes), LocalDate.ofInstant(clock.instant(), ClockConfig.SEOUL));
        return list(memberId);
    }
}
