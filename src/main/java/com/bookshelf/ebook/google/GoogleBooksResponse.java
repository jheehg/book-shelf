package com.bookshelf.ebook.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record GoogleBooksResponse(List<Item> items) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String id, VolumeInfo volumeInfo, SaleInfo saleInfo) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record VolumeInfo(String title, List<String> authors, String language, List<Identifier> industryIdentifiers, String infoLink) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Identifier(String type, String identifier) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SaleInfo(Boolean isEbook, Price retailPrice) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Price(BigDecimal amount, String currencyCode) { }
}
