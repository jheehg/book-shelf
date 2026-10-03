package com.bookshelf.book.infrastructure.provider.kakao;

import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.book.application.catalog.BookCatalog;
import com.bookshelf.book.application.catalog.CatalogBook;
import com.bookshelf.book.application.catalog.CatalogPage;
import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.infrastructure.persistence.CallBudget;
import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.domain.Provider;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 카카오 호출의 유일한 입구.
 *
 * 순서가 의미를 가진다: 쿨다운 → 예산 → HTTP → 번역.
 * 쿨다운을 예산보다 먼저 보는 이유: 보내지도 않을 호출로 예산을 깎지 않는다.
 * 실패한 호출도 예산에서 빠진다: 제공자는 우리가 보낸 요청을 세지, 성공만 세지 않는다고 가정한다.
 */
@Slf4j
@Component
public class KakaoBookCatalog implements BookCatalog {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;
    private final CallBudget budget;
    private final KakaoProperties props;
    private final Clock clock;

    /** 마지막 실패 후 이 시각까지 백그라운드 호출을 보내지 않는다 — 카카오가 죽었을 때 매번 타임아웃을 기다리지 않게 */
    private volatile Instant coolDownUntil = Instant.MIN;

    public KakaoBookCatalog(RestClient kakaoRestClient, CallBudget budget, KakaoProperties props, Clock clock) {
        this.rest = kakaoRestClient;
        this.budget = budget;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Optional<CatalogBook> lookup(Isbn13 isbn, CallPurpose purpose) {
        KakaoResponse r = call(purpose, b -> b.path("/v3/search/book")
                .queryParam("target", "isbn")
                .queryParam("query", "{q}")
                .queryParam("size", 5)
                .build(Map.of("q", isbn.value())));
        // ISBN으로 물었어도 다른 책이 섞여 오면 버린다 — 요청한 ISBN과 같은 항목만
        return toBooks(r).stream().filter(b -> b.isbn().equals(isbn)).findFirst();
    }

    @Override
    public CatalogPage search(String query, int page, CallPurpose purpose) {
        // 검색어는 URI 변수로 넘긴다. 문자열로 붙이면 &, +, {} 가 파라미터 구조를 깨뜨린다
        KakaoResponse r = call(purpose, b -> b.path("/v3/search/book")
                .queryParam("query", "{q}")
                .queryParam("size", PAGE_SIZE)
                .queryParam("page", page)
                .queryParam("sort", "accuracy")
                .build(Map.of("q", query)));
        int total = r.meta() == null ? 0 : r.meta().pageableCount();
        boolean isEnd = r.meta() == null || r.meta().isEnd();
        return new CatalogPage(toBooks(r), total, isEnd);
    }

    private KakaoResponse call(CallPurpose purpose, Function<UriBuilder, URI> uri) {
        if (purpose == CallPurpose.BACKGROUND && clock.instant().isBefore(coolDownUntil)) {
            throw new ProviderUnavailableException(Provider.KAKAO, "쿨다운 중 (" + coolDownUntil + "까지)");
        }
        budget.acquire(props.policy(), purpose);   // 사용자 상한 → 제공자 상한. 넘으면 예외
        try {
            byte[] body = rest.get().uri(uri).retrieve().body(byte[].class);
            return JSON.readValue(body, KakaoResponse.class);
        } catch (RestClientException | JacksonException e) {
            coolDownUntil = clock.instant().plus(props.coolDown());
            throw new ProviderUnavailableException(Provider.KAKAO, "호출 실패: " + e.getMessage(), e);
        }
    }

    /** 같은 ISBN이 두 번 오면 먼저 온 것만 남긴다(같은 책이 두 번 저장되지 않게). 순서는 카카오의 정확도 순서 그대로 */
    private List<CatalogBook> toBooks(KakaoResponse r) {
        Map<Isbn13, CatalogBook> byIsbn = new LinkedHashMap<>();
        if (r.documents() == null) {
            return List.of();
        }
        for (KakaoResponse.Document d : r.documents()) {
            Optional<Isbn13> isbn = KakaoIsbn.parse(d.isbn());
            if (isbn.isEmpty() || d.title() == null || d.title().isBlank()) {
                log.debug("ISBN·제목이 없는 카카오 항목을 건너뛴다: {}", d.title());
                continue;
            }
            byIsbn.putIfAbsent(isbn.get(), new CatalogBook(
                    isbn.get(),
                    cut(d.title(), 500),
                    d.authors() == null || d.authors().isEmpty() ? null : cut(String.join(", ", d.authors()), 500),
                    cut(blankToNull(d.publisher()), 200),
                    parseDate(d.datetime()),
                    coverOrNull(d.thumbnail()),
                    null,                       // 카카오에는 쪽수가 없다
                    positiveOrNull(d.price()),
                    positiveOrNull(d.salePrice()),
                    saleStatus(d.status())));
        }
        return List.copyOf(byIsbn.values());
    }

    /** 실측 값은 "정상판매"·"주문판매"·빈 문자열뿐이었다. 처음 보는 값은 기록하고 "판매 정보 없음"으로 둔다 */
    private static SaleStatus saleStatus(String raw) {
        if ("정상판매".equals(raw)) {
            return SaleStatus.ON_SALE;
        }
        if ("주문판매".equals(raw)) {
            return SaleStatus.ORDER;
        }
        if (raw != null && !raw.isBlank()) {
            log.info("처음 보는 카카오 status: '{}' — 판매 정보 없음으로 둔다", raw);
        }
        return SaleStatus.UNKNOWN;
    }

    /** 카카오는 가격이 없을 때 price 0, sale_price -1을 준다 (실측 487건 중 154건) */
    private static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    /**
     * 컬럼 길이(VARCHAR는 문자 수)에 맞게 자른다. 표시용 남의 데이터라 잘려도 된다 —
     * 넘긴 채 저장하면 그 행이 저장에서 터지고, 갱신 큐 맨 앞에 남아 매분 예산을 태운다.
     */
    private static String cut(String s, int maxChars) {
        if (s == null || s.codePointCount(0, s.length()) <= maxChars) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, maxChars));
    }

    /** 잘린 URL은 깨진 이미지가 되므로 자르지 않고 버린다(회색 표지가 된다) */
    private static String coverOrNull(String url) {
        String u = blankToNull(url);
        return u == null || u.length() > 1000 ? null : u;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** "2014-05-19T00:00:00.000+09:00" → 2014-05-19. 표시용이다 — 형식이 달라도 책을 버리지 않는다 */
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(raw.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
