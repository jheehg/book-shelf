package com.bookshelf.member.presentation;

import com.bookshelf.common.CurrentMember;
import com.bookshelf.member.application.MeView;
import com.bookshelf.member.application.MemberService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class MeController {

    /** 바꿀 것만 보낸다. null은 그대로 둔다 */
    public record UpdateRequest(String slug, String displayName) {
    }

    private final MemberService members;

    public MeController(MemberService members) {
        this.members = members;
    }

    @GetMapping
    public MeView me() {
        return members.me(CurrentMember.require());
    }

    @PatchMapping
    public MeView update(@RequestBody UpdateRequest req) {
        return members.update(CurrentMember.require(), req.slug(), req.displayName());
    }
}
