package com.bookshelf.library.presentation;

import com.bookshelf.common.CurrentMember;
import com.bookshelf.library.application.BorrowService;
import com.bookshelf.library.application.HoldingView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 도서관 코드를 파라미터로 받지 않는다 — 받을 곳 자체가 없다(설계 6-3) */
@RestController
public class BorrowController {

    private final BorrowService borrow;

    public BorrowController(BorrowService borrow) {
        this.borrow = borrow;
    }

    @GetMapping("/api/books/{isbn}/libraries")
    public List<HoldingView> availability(@PathVariable String isbn) {
        return borrow.availability(CurrentMember.require(), isbn);
    }
}
