package com.bookshelf.library.infrastructure.provider.data4library;

import com.bookshelf.common.Isbn13;
import com.bookshelf.library.domain.HoldingState;
import com.bookshelf.library.domain.LibraryAvailability;
import com.bookshelf.library.domain.LibraryDirectory;
import com.bookshelf.library.domain.LibraryInfo;
import com.bookshelf.library.domain.LibraryPage;
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
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 정보나루 호출의 유일한 입구. 순서는 카카오와 같다: 쿨다운 → 예산 → HTTP → 번역.
 * 인증키가 URL에 있으므로 HTTP 예외의 메시지와 cause를 밖으로 내보내지 않는다.
 */
@Slf4j
@Component
public class Data4LibraryClient implements LibraryAvailability, LibraryDirectory {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient rest;
    private final CallBudget budget;
    private final LibraryProperties props;
    private final Clock clock;

    private volatile Instant coolDownUntil = Instant.MIN;

    public Data4LibraryClient(RestClient libraryRestClient, CallBudget budget, LibraryProperties props, Clock clock) {
        this.rest = libraryRestClient;
        this.budget = budget;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public HoldingState holding(String libCode, Isbn13 isbn, CallPurpose purpose) {
        Data4LibraryResponse.Exist r = call(purpose, Data4LibraryResponse.Exist.class, b -> b.path("/api/bookExist")
                .queryParam("libCode", "{lib}")
                .queryParam("isbn13", isbn.value())
                .queryParam("authKey", "{key}")
                .queryParam("format", "json")
                .build(libCode, props.apiKey()));
        if (r.response() == null || r.response().error() != null || r.response().result() == null) {
            throw failed("bookExist 오류 응답: " + (r.response() == null ? "본문 없음" : r.response().errCode()));
        }
        HoldingState state = HoldingState.from(r.response().result().hasBook(), r.response().result().loanAvailable());
        if (state == HoldingState.UNKNOWN) {
            log.info("처음 보는 bookExist 값: {}", r.response().result());
        }
        return state;
    }

    @Override
    public LibraryPage libraries(int region, int pageNo, int pageSize, CallPurpose purpose) {
        Data4LibraryResponse.LibSrch r = call(purpose, Data4LibraryResponse.LibSrch.class, b -> b.path("/api/libSrch")
                .queryParam("region", region)
                .queryParam("pageNo", pageNo)
                .queryParam("pageSize", pageSize)
                .queryParam("authKey", "{key}")
                .queryParam("format", "json")
                .build(props.apiKey()));
        if (r.response() == null || r.response().error() != null) {
            throw failed("libSrch 오류 응답: " + (r.response() == null ? "본문 없음" : r.response().errCode()));
        }
        List<LibraryInfo> libs = r.response().libs() == null ? List.of() : r.response().libs().stream()
                .map(Data4LibraryResponse.LibSrch.Item::lib)
                .filter(Objects::nonNull)
                .filter(l -> l.libCode() != null && !l.libCode().isBlank() && l.libName() != null)
                .map(l -> new LibraryInfo(l.libCode().strip(), l.libName().strip(), blankToNull(l.address()),
                        blankToNull(l.tel()), coordinate(l.latitude()), coordinate(l.longitude()), blankToNull(l.homepage())))
                .toList();
        int numFound = r.response().numFound() == null ? libs.size() : r.response().numFound();
        return new LibraryPage(libs, numFound);
    }

    private <T> T call(CallPurpose purpose, Class<T> type, Function<UriBuilder, URI> uri) {
        if (purpose == CallPurpose.BACKGROUND && clock.instant().isBefore(coolDownUntil)) {
            throw new ProviderUnavailableException(Provider.LIBRARY, "쿨다운 중 (" + coolDownUntil + "까지)");
        }
        budget.acquire(props.policy(), purpose);
        try {
            byte[] body = rest.get().uri(uri).retrieve().body(byte[].class);
            return JSON.readValue(body, type);
        } catch (RestClientException | JacksonException e) {
            // e.getMessage()에는 authKey가 든 URL이 있다 — 클래스 이름만 남기고 cause도 붙이지 않는다
            throw failed("호출 실패: " + e.getClass().getSimpleName());
        }
    }

    private ProviderUnavailableException failed(String reason) {
        coolDownUntil = clock.instant().plus(props.coolDown());
        return new ProviderUnavailableException(Provider.LIBRARY, reason);
    }

    private static Double coordinate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
