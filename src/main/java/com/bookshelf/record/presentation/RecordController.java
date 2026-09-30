package com.bookshelf.record.presentation;

import com.bookshelf.common.CurrentMember;
import com.bookshelf.record.application.RecordService;
import com.bookshelf.record.application.RecordView;
import com.bookshelf.record.application.RecordWriter;
import com.bookshelf.record.domain.ReadingStatus;
import com.bookshelf.record.domain.Visibility;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 주체(회원)는 토큰에서만 온다. 요청 DTO에 회원 id 필드를 두지 않는다 — 보내도 버려진다 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    public record CreateRequest(String isbn, ReadingStatus status) {
    }

    public record UpdateRequest(ReadingStatus status, Integer rating, String review, Visibility visibility,
                                LocalDate startedAt, LocalDate finishedAt) {
    }

    private final RecordService records;

    public RecordController(RecordService records) {
        this.records = records;
    }

    @PostMapping
    public ResponseEntity<RecordView> create(@RequestBody CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(records.create(CurrentMember.require(), req.isbn(), req.status()));
    }

    @GetMapping
    public List<RecordView> list() {
        return records.list(CurrentMember.require());
    }

    @GetMapping("/{id}")
    public RecordView get(@PathVariable long id) {
        return records.get(CurrentMember.require(), id);
    }

    @PutMapping("/{id}")
    public RecordView update(@PathVariable long id, @RequestBody UpdateRequest req) {
        return records.update(CurrentMember.require(), id, new RecordWriter.Update(req.status(), req.rating(),
                req.review(), req.visibility(), req.startedAt(), req.finishedAt()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        records.delete(CurrentMember.require(), id);
        return ResponseEntity.noContent().build();
    }
}
