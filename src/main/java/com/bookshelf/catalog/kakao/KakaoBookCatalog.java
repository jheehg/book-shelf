package com.bookshelf.catalog.kakao;

import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.catalog.BookCatalog;
import com.bookshelf.catalog.CatalogBook;
import com.bookshelf.catalog.CatalogPage;
import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.BudgetExhaustedException;
import com.bookshelf.provider.CallBudget;
import com.bookshelf.provider.CallPurpose;
import com.bookshelf.provider.Provider;
import com.bookshelf.provider.ProviderUnavailableException;
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

    /** 마지막 실패 후 이 시각까지 백그라운드 호출을 보내지 않는다 (Review Focus 3) */
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
        if (!budget.tryAcquire(props.policy(), purpose)) {
            throw new BudgetExhaustedException(Provider.KAKAO, purpose);
        }
        try {
            byte[] body = rest.get().uri(uri).retrieve().body(byte[].class);
            return JSON.readValue(body, KakaoResponse.class);
        } catch (RestClientException | JacksonException e) {
            coolDownUntil = clock.instant().plus(props.coolDown());
            throw new ProviderUnavailableException(Provider.KAKAO, "호출 실패: " + e.getMessage(), e);
        }
    }

    /** 같은 ISBN이 두 번 오면 먼저 온 것만 남긴다 (Review Focus 5). 순서는 카카오의 정확도 순서 그대로 */
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
                    d.title(),
                    d.authors() == null || d.authors().isEmpty() ? null : String.join(", ", d.authors()),
                    blankToNull(d.publisher()),
                    parseDate(d.datetime()),
                    blankToNull(d.thumbnail()),
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
