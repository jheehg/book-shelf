package com.bookshelf.links;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("store-links")
public record StoreLinksProperties(List<Entry> entries) {

    public record Entry(LinkGroup group, String name, String template) {
    }
}
