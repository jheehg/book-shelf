package com.bookshelf.library.presentation;

import com.bookshelf.common.CurrentMember;
import com.bookshelf.library.application.LibraryView;
import com.bookshelf.library.application.MyLibraries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me/libraries")
public class MyLibrariesController {

    /** 등록 순서 그대로. 빈 목록이면 모두 해제 */
    public record ReplaceRequest(List<String> libCodes) {
    }

    private final MyLibraries libraries;

    public MyLibrariesController(MyLibraries libraries) {
        this.libraries = libraries;
    }

    @GetMapping
    public List<LibraryView> list() {
        return libraries.list(CurrentMember.require());
    }

    @PutMapping
    public List<LibraryView> replace(@RequestBody ReplaceRequest req) {
        return libraries.replace(CurrentMember.require(), req.libCodes());
    }
}
