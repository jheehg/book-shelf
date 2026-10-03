package com.bookshelf.links.domain;

import com.bookshelf.links.infrastructure.config.StoreLinksProperties;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.bookshelf.links.domain.LinkGroup.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreLinksTest {

    private static StoreLinks links(StoreLinksProperties.Entry... entries) {
        return new StoreLinks(new StoreLinksProperties(List.of(entries)));
    }

    @Test
    void 종이책_서점은_ISBN으로_만든다() {
        StoreLinks l = links(new StoreLinksProperties.Entry(NEW, "교보문고", "https://kyobo.example/search?keyword={isbn}"));

        assertThat(l.forBook("9788936434120", "소년이 온다", "한강").get(NEW))
                .containsExactly(new StoreLink("교보문고", "https://kyobo.example/search?keyword=9788936434120"));
    }

    @Test
    void 전자책_서점은_괄호_부제를_뺀_제목과_첫_저자로_만들고_인코딩한다() {
        StoreLinks l = links(new StoreLinksProperties.Entry(EBOOK, "리디", "https://ridi.example/search?q={title}+{author}"));

        String url = l.forBook("9788983927620", "해리 포터와 마법사의 돌 1(20주년 개정판)", "조앤 K. 롤링, 강동혁")
                .get(EBOOK).getFirst().url();

        assertThat(url).isEqualTo("https://ridi.example/search?q="
                + "%ED%95%B4%EB%A6%AC%20%ED%8F%AC%ED%84%B0%EC%99%80%20%EB%A7%88%EB%B2%95%EC%82%AC%EC%9D%98%20%EB%8F%8C%201"
                + "+%EC%A1%B0%EC%95%A4%20K.%20%EB%A1%A4%EB%A7%81");   // 제목·저자 안의 공백은 %20, 둘 사이의 +는 템플릿의 것
    }

    @Test
    void 저자가_없으면_제목만_쓴다() {
        StoreLinks l = links(new StoreLinksProperties.Entry(EBOOK, "리디", "https://ridi.example/search?q={title}+{author}"));

        assertThat(l.forBook("9788936434120", "소년이 온다", null).get(EBOOK).getFirst().url())
                .isEqualTo("https://ridi.example/search?q=%EC%86%8C%EB%85%84%EC%9D%B4%20%EC%98%A8%EB%8B%A4");
    }

    /** 밀리의서재는 검색어가 쿼리가 아니라 경로에 들어간다. 경로에서 +는 공백이 아니라 글자 그대로의 +다 */
    @Test
    void 검색어가_주소_경로에_들어가도_공백이_깨지지_않는다() {
        StoreLinks l = links(new StoreLinksProperties.Entry(EBOOK, "밀리의서재",
                "https://millie.example/v3/search/result/{title}?type=total"));

        assertThat(l.forBook("9788936434120", "소년이 온다", "한강").get(EBOOK).getFirst().url())
                .isEqualTo("https://millie.example/v3/search/result/%EC%86%8C%EB%85%84%EC%9D%B4%20%EC%98%A8%EB%8B%A4?type=total");
    }

    @Test
    void 그룹_순서와_설정_순서를_지킨다() {
        StoreLinks l = links(
                new StoreLinksProperties.Entry(EBOOK, "리디", "https://ridi.example/?q={title}"),
                new StoreLinksProperties.Entry(NEW, "예스24", "https://yes24.example/?q={isbn}"),
                new StoreLinksProperties.Entry(NEW, "교보문고", "https://kyobo.example/?q={isbn}"),
                new StoreLinksProperties.Entry(USED, "알라딘 중고", "https://aladin.example/?q={isbn}"));

        Map<LinkGroup, List<StoreLink>> m = l.forBook("9788936434120", "소년이 온다", "한강");

        assertThat(m.keySet()).containsExactly(NEW, USED, EBOOK);
        assertThat(m.get(NEW)).extracting(StoreLink::name).containsExactly("예스24", "교보문고");
    }

    @Test
    void 전자책_링크에_ISBN을_쓰면_기동이_실패한다() {
        assertThatThrownBy(() -> links(new StoreLinksProperties.Entry(EBOOK, "리디", "https://ridi.example/?q={isbn}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("리디");
    }

    @Test
    void https가_아니거나_자리표시가_없으면_기동이_실패한다() {
        assertThatThrownBy(() -> links(new StoreLinksProperties.Entry(NEW, "교보문고", "http://kyobo.example/?q={isbn}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> links(new StoreLinksProperties.Entry(NEW, "교보문고", "https://kyobo.example/")))
                .isInstanceOf(IllegalStateException.class);
    }
}
