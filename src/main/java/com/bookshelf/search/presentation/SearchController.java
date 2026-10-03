package com.bookshelf.search.presentation;

import com.bookshelf.search.application.SearchResult;
import com.bookshelf.search.application.SearchService;

import com.bookshelf.book.application.SaleService;
import com.bookshelf.common.Isbn13;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService search;
    private final SaleService sales;

    public SearchController(SearchService search, SaleService sales) {
        this.search = search;
        this.sales = sales;
    }

    /** 판매 상태는 검색이 방금 저장했거나(제공자 호출) 캐시에 있다. 여기서 제공자를 더 부르지 않는다 */
    @GetMapping("/api/search")
    public SearchResponse search(@RequestParam("q") String q, @RequestParam(defaultValue = "1") int page) {
        SearchResult r = search.search(q, page);
        return new SearchResponse(
                r.books().stream().map(b -> new SearchItem(b, sales.current(Isbn13.parse(b.isbn13())))).toList(),
                r.total(), r.isEnd(), r.fetchedAt());
    }
}
