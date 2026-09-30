package com.bookshelf.library.presentation;

import com.bookshelf.library.Region;
import com.bookshelf.library.application.LibrarySearch;
import com.bookshelf.library.application.LibraryView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/libraries")
public class LibraryController {

    public record RegionView(int code, String label) {
    }

    private final LibrarySearch search;

    public LibraryController(LibrarySearch search) {
        this.search = search;
    }

    @GetMapping("/regions")
    public List<RegionView> regions() {
        return Arrays.stream(Region.values()).map(r -> new RegionView(r.code(), r.label())).toList();
    }

    @GetMapping
    public List<LibraryView> search(@RequestParam int region, @RequestParam(required = false) String q) {
        return search.search(region, q);
    }
}
