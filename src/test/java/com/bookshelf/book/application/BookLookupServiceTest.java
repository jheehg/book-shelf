package com.bookshelf.book.application;

import com.bookshelf.provider.domain.CallPurpose;

import com.bookshelf.book.domain.BookLookupFailure;
import com.bookshelf.book.domain.SaleStatus;
import com.bookshelf.book.infrastructure.persistence.BookLookupFailureRepository;
import com.bookshelf.book.infrastructure.persistence.BookRepository;
import com.bookshelf.book.infrastructure.persistence.SaleSnapshotRepository;
import com.bookshelf.book.application.catalog.CatalogBook;
import com.bookshelf.common.Isbn13;
import com.bookshelf.provider.domain.ProviderUnavailableException;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.Fixtures;
import com.bookshelf.support.KakaoStub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;

import static com.bookshelf.provider.domain.CallPurpose.INTERACTIVE;
import static com.bookshelf.support.KakaoStub.doc;
import static com.bookshelf.support.KakaoStub.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(Fixtures.class)
class BookLookupServiceTest {

    private static final Isbn13 ISBN = Isbn13.parse("9788936434120");

    @DynamicPropertySource
    static void kakao(DynamicPropertyRegistry r) {
        r.add("providers.kakao.base-url", KakaoStub::baseUrl);
    }

    @Autowired JdbcClient jdbc;
    @Autowired BookLookupService service;
    @Autowired BookWriter writer;
    @Autowired BookRepository books;
    @Autowired SaleSnapshotRepository sales;
    @Autowired BookLookupFailureRepository failures;
    @Autowired Fixtures fx;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        KakaoStub.reset();
    }

    private void kakaoKnows() {
        KakaoStub.returns(ISBN.value(), response(1, true, doc(ISBN.value(), "소년이 온다", 15000, 13500, "정상판매")));
    }

    @Test
    void 처음_보는_책은_제공자에서_가져와_책과_판매_상태를_저장한다() {
        kakaoKnows();

        BookView view = service.findOrFetch(ISBN, INTERACTIVE).orElseThrow();

        assertThat(view.title()).isEqualTo("소년이 온다");
        assertThat(books.findById(ISBN.value())).isPresent();
        assertThat(sales.findById(ISBN.value()).orElseThrow().getSalePrice()).isEqualTo(13500);
        assertThat(sales.findById(ISBN.value()).orElseThrow().getStatus()).isEqualTo(SaleStatus.ON_SALE);
    }

    @Test
    void 두_번째부터는_제공자를_부르지_않는다() {
        kakaoKnows();

        service.findOrFetch(ISBN, INTERACTIVE);
        service.findOrFetch(ISBN, INTERACTIVE);
        service.findOrFetch(ISBN, INTERACTIVE);

        assertThat(KakaoStub.calls()).isEqualTo(1);
    }

    @Test
    void 제공자가_모르는_책이면_empty이고_아무것도_저장하지_않는다() {
        KakaoStub.returns(ISBN.value(), response(0, true));

        assertThat(service.findOrFetch(ISBN, INTERACTIVE)).isEmpty();
        assertThat(books.count()).isZero();
    }

    @Test
    void 제공자가_죽었으면_그대로_알린다() {
        KakaoStub.failsWith(503);

        assertThatThrownBy(() -> service.findOrFetch(ISBN, INTERACTIVE))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void 제공자가_죽어도_이미_아는_책은_나온다() {
        kakaoKnows();
        service.findOrFetch(ISBN, INTERACTIVE);
        KakaoStub.reset();
        KakaoStub.failsWith(503);

        assertThat(service.findOrFetch(ISBN, INTERACTIVE)).map(BookView::title).contains("소년이 온다");
    }

    @Test
    void 찾으면_실패_표시를_지운다() {
        failures.save(BookLookupFailure.first(ISBN, Instant.parse("2026-01-01T00:00:00Z")));
        kakaoKnows();

        service.findOrFetch(ISBN, INTERACTIVE);

        assertThat(failures.findById(ISBN.value())).isEmpty();
    }

    @Test
    void 갱신해도_이미_아는_쪽수를_지우지_않는다() {
        fx.book(ISBN.value(), "소년이 온다");   // 쪽수 216
        CatalogBook noPages = new CatalogBook(ISBN, "소년이 온다", "한강", "창비", null, null,
                null, 15000, 13500, SaleStatus.ON_SALE);

        writer.saveLookup(noPages);

        assertThat(books.findById(ISBN.value()).orElseThrow().getPageCount()).isEqualTo(216);
    }
}
