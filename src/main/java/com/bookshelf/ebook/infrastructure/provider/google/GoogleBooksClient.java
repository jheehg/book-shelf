package com.bookshelf.ebook.infrastructure.provider.google;

import com.bookshelf.ebook.domain.EbookCandidate;
import com.bookshelf.ebook.application.EbookPricing;
import com.bookshelf.provider.infrastructure.persistence.CallBudget;
import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.domain.Provider;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Google Books 호출의 유일한 입구. HTTP 실패의 URL·cause에는 키가 실릴 수 있어 밖으로 내보내지 않는다. */
@Component
public class GoogleBooksClient implements EbookPricing {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient rest;
    private final CallBudget budget;
    private final GoogleBooksProperties properties;
    private final Clock clock;
    private volatile Instant coolDownUntil = Instant.MIN;

    public GoogleBooksClient(RestClient googleRestClient, CallBudget budget, GoogleBooksProperties properties, Clock clock) {
        this.rest = googleRestClient;
        this.budget = budget;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<EbookCandidate> candidates(String title, String author, CallPurpose purpose) {
        if (purpose == CallPurpose.BACKGROUND && clock.instant().isBefore(coolDownUntil)) {
            throw new ProviderUnavailableException(Provider.GOOGLE_BOOKS, "쿨다운 중");
        }
        budget.acquire(properties.policy(), purpose);
        String query = "intitle:" + searchTitle(title) + " inauthor:" + firstAuthor(author);
        try {
            byte[] body = rest.get().uri(builder -> builder.path("/books/v1/volumes")
                            .queryParam("q", "{q}").queryParam("country", "KR").queryParam("maxResults", 20)
                            .queryParam("key", "{key}").build(query, properties.apiKey()))
                    .retrieve().body(byte[].class);
            return translate(JSON.readValue(body, GoogleBooksResponse.class));
        } catch (RestClientException | JacksonException exception) {
            coolDownUntil = clock.instant().plus(properties.coolDown());
            throw new ProviderUnavailableException(Provider.GOOGLE_BOOKS, "호출 실패: " + exception.getClass().getSimpleName());
        }
    }

    private static List<EbookCandidate> translate(GoogleBooksResponse response) {
        List<EbookCandidate> result = new ArrayList<>();
        if (response.items() == null) return result;
        for (GoogleBooksResponse.Item item : response.items()) {
            var volume = item.volumeInfo();
            var sale = item.saleInfo();
            if (volume == null || volume.title() == null) continue;
            var price = sale == null ? null : sale.retailPrice();
            result.add(new EbookCandidate(item.id(), volume.title(), volume.authors(), volume.language(),
                    sale != null && Boolean.TRUE.equals(sale.isEbook()),
                    price == null || price.amount() == null ? null : price.amount().intValue(),
                    price == null ? null : price.currencyCode(), isbn13(volume.industryIdentifiers()), volume.infoLink()));
        }
        return result;
    }

    private static String isbn13(List<GoogleBooksResponse.Identifier> ids) {
        if (ids == null) return null;
        return ids.stream().filter(id -> "ISBN_13".equals(id.type())).map(GoogleBooksResponse.Identifier::identifier)
                .findFirst().orElse(null);
    }

    private static String searchTitle(String title) { return title.replaceAll("\\s*[(\\[][^)\\]]*[)\\]]", "").strip(); }
    private static String firstAuthor(String author) { return author.split(",")[0].strip(); }
}
