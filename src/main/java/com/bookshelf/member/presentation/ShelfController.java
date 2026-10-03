package com.bookshelf.member.presentation;

import com.bookshelf.common.CurrentMember;
import com.bookshelf.member.application.ShelfService;
import com.bookshelf.member.application.ShelfView;
import com.bookshelf.record.domain.Visibility;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/shelf")
public class ShelfController {

    public record PublishRequest(Visibility mode) {
    }

    private final ShelfService shelves;

    public ShelfController(ShelfService shelves) {
        this.shelves = shelves;
    }

    @GetMapping
    public ShelfView view() {
        return shelves.view(CurrentMember.require());
    }

    @PostMapping("/publish")
    public ShelfView publish(@RequestBody PublishRequest request) {
        return shelves.publish(CurrentMember.require(), request.mode());
    }

    @PostMapping("/close")
    public ShelfView close() {
        return shelves.close(CurrentMember.require());
    }
}
