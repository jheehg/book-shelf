package com.bookshelf.library.infrastructure.provider.data4library;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 정보나루는 모든 응답을 {"response": {...}}로 감싼다. 오류면 errCode·error가 있고 나머지가 없다 */
final class Data4LibraryResponse {

    private Data4LibraryResponse() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Exist(Body response) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Body(String errCode, String error, Result result) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Result(String hasBook, String loanAvailable) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LibSrch(Body response) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Body(String errCode, String error, Integer numFound, List<Item> libs) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Item(Lib lib) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Lib(String libCode, String libName, String address, String tel,
                   String latitude, String longitude, String homepage) {
        }
    }
}
