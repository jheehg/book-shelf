package com.bookshelf.publicview.presentation;

import com.bookshelf.member.domain.Member;
import com.bookshelf.publicview.infrastructure.persistence.SitemapQuery;

import com.bookshelf.common.BookshelfProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeoController {

    private final SitemapQuery query;
    private final BookshelfProperties props;

    public SeoController(SitemapQuery query, BookshelfProperties props) {
        this.query = query;
        this.props = props;
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public String robots() {
        return """
                User-agent: *
                Allow: /
                Disallow: /actuator/
                Sitemap: %s/sitemap.xml
                """.formatted(props.siteUrl());
    }

    /** slug는 [a-z0-9-], ISBN은 숫자뿐이라 XML 이스케이프가 필요 없다 (Member·Isbn13이 보장한다) */
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public String sitemap() {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """);
        for (String slug : query.publicSlugs()) {
            xml.append("<url><loc>").append(props.siteUrl()).append("/u/").append(slug).append("</loc></url>\n");
        }
        for (String isbn : query.bookIsbns()) {
            xml.append("<url><loc>").append(props.siteUrl()).append("/book/").append(isbn).append("</loc></url>\n");
        }
        return xml.append("</urlset>\n").toString();
    }
}
