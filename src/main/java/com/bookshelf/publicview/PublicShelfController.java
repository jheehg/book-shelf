package com.bookshelf.publicview;

import com.bookshelf.common.BookshelfProperties;
import com.bookshelf.record.domain.ReadingStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Controller
public class PublicShelfController {

    private static final Map<ReadingStatus, String> STATUS_LABELS = Map.of(
            ReadingStatus.WANT, "읽고 싶어요",
            ReadingStatus.READING, "읽는 중",
            ReadingStatus.DONE, "읽었어요");

    private final PublicShelfQuery query;
    private final BookshelfProperties props;

    public PublicShelfController(PublicShelfQuery query, BookshelfProperties props) {
        this.query = query;
        this.props = props;
    }

    @GetMapping("/u/{slug}")
    public ModelAndView shelf(@PathVariable String slug) {
        return query.find(slug).map(this::render).orElseGet(PublicPages::notFound);
    }

    private ModelAndView render(PublicShelf shelf) {
        String image = shelf.entries().stream()
                .map(PublicEntry::coverUrl)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        ModelAndView mav = PublicPages.page("public/shelf",
                shelf.displayName() + "의 서재",
                "공개한 책 " + shelf.publicCount() + "권",
                image,
                props.siteUrl() + "/u/" + shelf.slug());
        mav.addObject("shelf", shelf);
        mav.addObject("statusLabels", STATUS_LABELS);
        mav.addObject("reviews", reviewsByIsbn(shelf));
        return mav;
    }

    /**
     * 템플릿은 타입을 모른다. 한줄평은 ReviewedEntry에서만 꺼내 따로 넘긴다 —
     * 공통 뷰 모델에 review 칸을 만들면 RatedEntry가 필드를 갖지 않게 한 의미가 사라진다.
     */
    private static Map<String, String> reviewsByIsbn(PublicShelf shelf) {
        Map<String, String> reviews = new HashMap<>();
        for (PublicEntry e : shelf.entries()) {
            if (e instanceof ReviewedEntry r) {
                reviews.put(r.isbn13(), r.review());
            }
        }
        return reviews;
    }
}
