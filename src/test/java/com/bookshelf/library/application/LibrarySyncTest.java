package com.bookshelf.library.application;

import com.bookshelf.library.LibraryDirectory;
import com.bookshelf.support.DbCleaner;
import com.bookshelf.support.LibraryStub;
import com.bookshelf.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LibrarySyncTest {

    private static final Instant T0 = Instant.parse("2026-10-05T20:00:00Z");   // 월요일 05:00 KST

    @DynamicPropertySource
    static void library(DynamicPropertyRegistry r) {
        r.add("providers.library.base-url", LibraryStub::baseUrl);
    }

    @Autowired JdbcClient jdbc;
    @Autowired LibraryDirectory directory;
    @Autowired LibraryWriter writer;

    private MutableClock clock;
    private LibrarySync sync;

    @BeforeEach
    void setUp() {
        DbCleaner.clean(jdbc);
        LibraryStub.reset();
        LibraryStub.librariesDefault(LibraryStub.emptyLibraries());
        clock = new MutableClock(T0);
        sync = new LibrarySync(directory, writer, clock);
    }

    private int libraries() {
        return jdbc.sql("SELECT count(*) FROM library").query(Integer.class).single();
    }

    private int runs() {
        return jdbc.sql("SELECT count(*) FROM library_sync_run").query(Integer.class).single();
    }

    @Test
    void 비어_있으면_17개_지역을_모두_받는다() {
        LibraryStub.libraries(11, 1, LibraryStub.fixture("libSrch-seoul-3.json"));

        assertThat(sync.runOnce()).isEqualTo(3);

        assertThat(LibraryStub.calls()).isEqualTo(17);
        assertThat(jdbc.sql("SELECT DISTINCT region_code FROM library").query(Integer.class).list()).containsExactly(11);
        assertThat(runs()).isEqualTo(1);
    }

    @Test
    void 한_쪽이_꽉_차면_다음_쪽을_받는다() {
        LibraryStub.libraries(31, 1, LibraryStub.librariesBody("G", 500));
        LibraryStub.libraries(31, 2, LibraryStub.librariesBody("H", 2));

        sync.runOnce();

        assertThat(libraries()).isEqualTo(502);
        LibraryStub.server().verify(0, getRequestedFor(urlPathEqualTo("/api/libSrch"))
                .withQueryParam("region", equalTo("31")).withQueryParam("pageNo", equalTo("3")));
    }

    @Test
    void 일주일_안에_끝난_동기화가_있으면_부르지_않는다() {
        sync.runOnce();
        int calls = LibraryStub.calls();

        clock.advance(Duration.ofDays(6));
        assertThat(sync.runOnce()).isZero();
        assertThat(LibraryStub.calls()).isEqualTo(calls);

        clock.advance(Duration.ofDays(1));
        sync.runOnce();
        assertThat(LibraryStub.calls()).isEqualTo(calls + 17);
    }

    @Test
    void 중간에_실패하면_받은_곳은_남기고_완료로_치지_않는다() {
        LibraryStub.libraries(11, 1, LibraryStub.fixture("libSrch-seoul-3.json"));
        LibraryStub.server().stubFor(get(urlPathEqualTo("/api/libSrch")).withQueryParam("region", equalTo("21"))
                .willReturn(aResponse().withStatus(500)));

        sync.runOnce();

        assertThat(libraries()).isEqualTo(3);
        assertThat(runs()).isZero();
        assertThat(LibraryStub.calls()).isEqualTo(2);   // 실패한 뒤 나머지 지역을 부르지 않는다 — 예산을 아낀다

        // 다음 날 다시 시도한다
        LibraryStub.reset();
        LibraryStub.librariesDefault(LibraryStub.emptyLibraries());
        clock.advance(Duration.ofDays(1));
        sync.runOnce();
        assertThat(runs()).isEqualTo(1);
    }

    @Test
    void 다시_받으면_바뀐_정보를_고친다() {
        LibraryStub.libraries(11, 1, LibraryStub.librariesBody("S", 1));
        sync.runOnce();

        LibraryStub.reset();
        LibraryStub.librariesDefault(LibraryStub.emptyLibraries());
        LibraryStub.libraries(11, 1, LibraryStub.librariesBody("S", 1).replace("S 도서관 0", "새 이름 도서관"));
        clock.advance(Duration.ofDays(8));
        sync.runOnce();

        assertThat(jdbc.sql("SELECT name FROM library WHERE lib_code = 'S0'").query(String.class).single())
                .isEqualTo("새 이름 도서관");
    }

    @Test
    void 목록에서_사라진_도서관은_지우지_않는다() {
        LibraryStub.libraries(11, 1, LibraryStub.librariesBody("S", 2));
        sync.runOnce();

        LibraryStub.reset();
        LibraryStub.librariesDefault(LibraryStub.emptyLibraries());
        LibraryStub.libraries(11, 1, LibraryStub.librariesBody("S", 1));
        clock.advance(Duration.ofDays(8));
        sync.runOnce();

        assertThat(libraries()).isEqualTo(2);
    }
}
