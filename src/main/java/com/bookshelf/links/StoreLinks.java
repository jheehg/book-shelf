package com.bookshelf.links;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 서점 검색 링크. API를 부르지 않는다 — 제공자가 모두 죽어도 이 칸은 동작한다.
 * 잘못된 설정은 기동에서 실패시킨다: 조용히 깨진 링크가 공개 페이지에 쌓이는 것보다 낫다.
 */
@Component
public class StoreLinks {

    private final List<StoreLinksProperties.Entry> entries;

    public StoreLinks(StoreLinksProperties props) {
        for (StoreLinksProperties.Entry e : props.entries()) {
            validate(e);
        }
        this.entries = List.copyOf(props.entries());
    }

    public Map<LinkGroup, List<StoreLink>> forBook(String isbn13, String title, String author) {
        Map<LinkGroup, List<StoreLink>> byGroup = new EnumMap<>(LinkGroup.class);
        for (StoreLinksProperties.Entry e : entries) {
            String url = e.template()
                    .replace("{isbn}", isbn13)
                    .replace("{title}", encode(searchTitle(title)))
                    .replace("+{author}", author == null || author.isBlank() ? "" : "+" + encode(firstAuthor(author)))
                    .replace("{author}", author == null ? "" : encode(firstAuthor(author)));
            byGroup.computeIfAbsent(e.group(), g -> new ArrayList<>()).add(new StoreLink(e.name(), url));
        }
        Map<LinkGroup, List<StoreLink>> ordered = new LinkedHashMap<>();
        for (LinkGroup g : LinkGroup.values()) {
            if (byGroup.containsKey(g)) {
                ordered.put(g, List.copyOf(byGroup.get(g)));
            }
        }
        return ordered;
    }

    private static void validate(StoreLinksProperties.Entry e) {
        String t = e.template();
        if (t == null || !t.startsWith("https://")) {
            throw new IllegalStateException("서점 링크는 https여야 한다: " + e.name());
        }
        if (!t.contains("{isbn}") && !t.contains("{title}")) {
            throw new IllegalStateException("서점 링크에 {isbn}이나 {title}이 없다: " + e.name());
        }
        if (e.group() == LinkGroup.EBOOK && t.contains("{isbn}")) {
            throw new IllegalStateException("전자책 서점은 종이책 ISBN으로 검색되지 않는다 — {title}을 써라: " + e.name());
        }
    }

    /** "해리 포터와 마법사의 돌 1(20주년 개정판)" → "해리 포터와 마법사의 돌 1". 판 표시는 검색을 좁히기만 한다 */
    private static String searchTitle(String title) {
        return title == null ? "" : title.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "").strip();
    }

    /** "조앤 K. 롤링, 강동혁" → "조앤 K. 롤링". 저자가 여럿이면 KakaoBookCatalog가 쉼표로 잇는다 — 첫 사람만 쓴다 */
    private static String firstAuthor(String author) {
        return author.split(",")[0].strip();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
