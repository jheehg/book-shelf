package com.bookshelf.publicview;

import com.bookshelf.book.application.SaleService;
import com.bookshelf.book.application.SaleView;
import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.common.DomainException;
import com.bookshelf.common.Isbn13;
import com.bookshelf.links.LinkGroup;
import com.bookshelf.links.StoreLink;
import com.bookshelf.links.StoreLinks;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.ModelAndView;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 책 페이지 — 읽을 수단 한 화면의 웹판 (설계 문서 2-1). 제공자를 부르지 않는다 */
@Controller
public class PublicBookController {

    private static final Map<SaleStatus, String> SALE_LABELS = Map.of(
            SaleStatus.ON_SALE, "판매 중",
            SaleStatus.ORDER, "주문 후 발송",
            SaleStatus.UNKNOWN, "판매 정보 없음");

    private final PublicBookQuery query;
    private final SaleService sales;
    private final StoreLinks links;
    private final BookshelfProperties props;
    private final Clock clock;

    public PublicBookController(PublicBookQuery query, SaleService sales, StoreLinks links,
                                BookshelfProperties props, Clock clock) {
        this.query = query;
        this.sales = sales;
        this.links = links;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping("/book/{isbn}")
    public ModelAndView book(@PathVariable String isbn) {
        Isbn13 parsed;
        try {
            parsed = Isbn13.parse(isbn);
        } catch (DomainException e) {
            return PublicPages.notFound();
        }
        Optional<PublicBook> found = query.find(parsed);   // 없으면 제공자를 부르지 않고 끝난다
        if (found.isEmpty()) {
            return PublicPages.notFound();
        }
        PublicBook b = found.get();
        SaleView sale = sales.current(parsed);             // 캐시만. 오래됐으면 큐에 넣는다
        Map<LinkGroup, List<StoreLink>> byGroup = links.forBook(b.isbn13(), b.title(), b.author());

        ModelAndView mav = PublicPages.page("public/book",
                b.title(),
                description(b),
                b.coverUrl(),
                props.siteUrl() + "/book/" + b.isbn13());
        mav.addObject("book", b);
        mav.addObject("sale", sale);
        mav.addObject("saleLabel", sale.available() ? SALE_LABELS.get(sale.status()) : null);
        mav.addObject("saleAge", sale.available() ? AgeLabel.of(sale.fetchedAt(), clock.instant()) : null);
        // 템플릿에서 enum 키로 맵을 꺼내지 않도록 칸별 목록으로 나눠 넘긴다
        mav.addObject("newLinks", byGroup.getOrDefault(LinkGroup.NEW, List.of()));
        mav.addObject("usedLinks", byGroup.getOrDefault(LinkGroup.USED, List.of()));
        mav.addObject("ebookLinks", byGroup.getOrDefault(LinkGroup.EBOOK, List.of()));
        return mav;
    }

    private static String description(PublicBook b) {
        StringBuilder sb = new StringBuilder();
        if (b.author() != null) {
            sb.append(b.author());
        }
        if (b.publisher() != null) {
            sb.append(sb.isEmpty() ? "" : " · ").append(b.publisher());
        }
        return sb.isEmpty() ? b.title() : sb.toString();
    }
}
