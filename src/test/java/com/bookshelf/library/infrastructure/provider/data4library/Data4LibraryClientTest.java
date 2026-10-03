package com.bookshelf.library.infrastructure.provider.data4library;

import com.bookshelf.common.Isbn13;
import com.bookshelf.library.domain.HoldingState;
import com.bookshelf.library.domain.LibraryInfo;
import com.bookshelf.library.domain.LibraryPage;
import com.bookshelf.provider.domain.CallPurpose;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.LibraryStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class Data4LibraryClientTest {

    private static final Isbn13 A = Isbn13.parse("9788936434120");

    @DynamicPropertySource
    static void library(DynamicPropertyRegistry r) {
        r.add("providers.library.base-url", LibraryStub::baseUrl);
    }

    @Autowired Data4LibraryClient client;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        LibraryStub.reset();
    }

    @Test
    void 실측_응답으로_대출_가능과_대출_중을_읽는다() {
        LibraryStub.holding("111456", A.value(), LibraryStub.fixture("bookExist-111456.json"));
        LibraryStub.holding("711618", A.value(), LibraryStub.fixture("bookExist-711618.json"));

        assertThat(client.holding("111456", A, CallPurpose.INTERACTIVE)).isEqualTo(HoldingState.AVAILABLE);
        assertThat(client.holding("711618", A, CallPurpose.INTERACTIVE)).isEqualTo(HoldingState.ON_LOAN);
    }

    @Test
    void 키와_형식을_쿼리로_보낸다() {
        LibraryStub.holding("111456", A.value(), LibraryStub.holdingBody("N", "N"));

        client.holding("111456", A, CallPurpose.INTERACTIVE);

        LibraryStub.server().verify(getRequestedFor(urlPathEqualTo("/api/bookExist"))
                .withQueryParam("authKey", equalTo("test-library-key"))
                .withQueryParam("format", equalTo("json")));
    }

    @Test
    void HTTP_200_오류_응답도_제공자_장애다() {
        LibraryStub.holding("111456", A.value(), LibraryStub.errorBody());

        assertThatThrownBy(() -> client.holding("111456", A, CallPurpose.INTERACTIVE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    /** ResourceAccessException 메시지에는 요청 URL(= authKey)이 들어간다. 그 메시지를 옮기지 않는다 */
    @Test
    void 장애_예외에_인증키가_들어가지_않는다() {
        LibraryStub.failsWith(500);
        assertThatThrownBy(() -> client.holding("111456", A, CallPurpose.INTERACTIVE))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("test-library-key");
                    assertThat(e.getCause()).isNull();   // cause의 메시지·스택에도 URL이 있다 — 붙이지 않는다
                });

        LibraryStub.reset();
        LibraryStub.server().stubFor(get(urlPathEqualTo("/api/bookExist"))
                .willReturn(aResponse().withFixedDelay(4_000).withBody("{}")));   // read-timeout 3s
        assertThatThrownBy(() -> client.holding("111456", A, CallPurpose.INTERACTIVE))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("test-library-key"));
    }

    @Test
    void 호출마다_정보나루_예산을_센다() {
        LibraryStub.holding("111456", A.value(), LibraryStub.holdingBody("Y", "Y"));

        client.holding("111456", A, CallPurpose.INTERACTIVE);
        client.holding("111456", A, CallPurpose.INTERACTIVE);

        assertThat(jdbc.sql("SELECT calls FROM provider_call_counter WHERE provider = 'LIBRARY'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void 실측_응답으로_도서관_목록을_읽는다() {
        LibraryStub.libraries(11, 1, LibraryStub.fixture("libSrch-seoul-3.json"));

        LibraryPage page = client.libraries(11, 1, 3, CallPurpose.BACKGROUND);

        assertThat(page.numFound()).isGreaterThan(300);   // 실측 서울 359곳
        assertThat(page.libraries()).hasSize(3);
        LibraryInfo first = page.libraries().getFirst();
        assertThat(first.libCode()).isNotBlank();
        assertThat(first.name()).isNotBlank();
        assertThat(first.address()).startsWith("서울");
        assertThat(first.latitude()).isBetween(37.0, 38.0);
    }

    @Test
    void 좌표가_숫자가_아니어도_도서관을_버리지_않는다() {
        LibraryStub.libraries(11, 1, """
                {"response":{"numFound":1,"libs":[{"lib":{"libCode":"999999","libName":"좌표 없는 도서관",
                 "address":"서울특별시 어딘가","tel":"","latitude":"","longitude":"-","homepage":""}}]}}
                """);

        LibraryInfo lib = client.libraries(11, 1, 500, CallPurpose.BACKGROUND).libraries().getFirst();

        assertThat(lib.name()).isEqualTo("좌표 없는 도서관");
        assertThat(lib.latitude()).isNull();
        assertThat(lib.homepage()).isNull();
    }
}
