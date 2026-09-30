package com.bookshelf.catalog.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 카카오 응답 원형. 이 패키지 밖에서 쓰지 않는다. 안 쓰는 필드(contents, url, translators)는 두지 않는다 */
@JsonIgnoreProperties(ignoreUnknown = true)
record KakaoResponse(Meta meta, List<Document> documents) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(@JsonProperty("total_count") int totalCount,
                @JsonProperty("pageable_count") int pageableCount,
                @JsonProperty("is_end") boolean isEnd) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(String title,
                    String isbn,
                    List<String> authors,
                    String publisher,
                    String datetime,
                    Integer price,
                    @JsonProperty("sale_price") Integer salePrice,
                    String status,
                    String thumbnail) {
    }
}
