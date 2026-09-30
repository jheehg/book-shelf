package com.bookshelf.auth;

import com.bookshelf.common.CurrentMember;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** GET·PATCH /api/me는 MeController(member), DELETE만 여기 — 탈퇴는 Apple 연결 해제가 먼저라 auth에 둔다 */
@RestController
public class AccountController {

    public record DeleteRequest(String authorizationCode) {
    }

    private final AccountDeletionService deletion;

    public AccountController(AccountDeletionService deletion) {
        this.deletion = deletion;
    }

    @DeleteMapping("/api/me")
    public ResponseEntity<Void> delete(@RequestBody DeleteRequest req) {
        deletion.delete(CurrentMember.require(), req.authorizationCode());
        return ResponseEntity.noContent().build();
    }
}
