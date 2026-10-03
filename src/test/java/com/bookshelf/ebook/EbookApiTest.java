package com.bookshelf.ebook;

import com.bookshelf.book.domain.Book;
import com.bookshelf.book.infrastructure.persistence.BookRepository;
import com.bookshelf.common.Isbn13;
import com.bookshelf.support.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(Fixtures.class)
class EbookApiTest {
    @DynamicPropertySource static void google(DynamicPropertyRegistry registry) { registry.add("providers.google.base-url", GoogleStub::baseUrl); }
    @Autowired MockMvc mvc; @Autowired Fixtures fixtures; @Autowired JwtEncoder encoder; @Autowired BookRepository books; @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private String auth;
    @BeforeEach void setUp() { DbCleaner.clean(jdbc); GoogleStub.reset(); auth = TestJwt.bearer(encoder, fixtures.member("mine", false).getId()); }
    private void book(String isbn, String title, String author) { books.save(Book.of(Isbn13.parse(isbn), title, author, "출판사", LocalDate.of(2020, 1, 1), null, null, Instant.now())); }
    private org.springframework.test.web.servlet.ResultActions ebook(String isbn) throws Exception { return mvc.perform(get("/api/books/" + isbn + "/ebook").header("Authorization", auth)); }
    @Test void 최신_fixture의_일치한_전자책만_가격을_준다() throws Exception {
        book("9791162540640", "아주 작은 습관의 힘(50만 부 기념 스페셜 에디션)", "제임스 클리어"); GoogleStub.returns(GoogleStub.fixture("9791162540640"));
        ebook("9791162540640").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MATCHED")).andExpect(jsonPath("$.price").value(11340)).andExpect(jsonPath("$.attribution").value("powered by Google"));
    }
    @Test void 빈_결과도_캐시한다() throws Exception {
        book("9788936434120", "소년이 온다", "한강"); GoogleStub.returns(GoogleStub.fixture("9788936434120"));
        ebook("9788936434120").andExpect(jsonPath("$.status").value("NO_MATCH")); ebook("9788936434120").andExpect(jsonPath("$.status").value("NO_MATCH")); assertThat(GoogleStub.calls()).isEqualTo(1);
    }
    @Test void 장애는_캐시하지_않고_확인불가다() throws Exception {
        book("9788936434120", "소년이 온다", "한강"); GoogleStub.failsWith(503);
        ebook("9788936434120").andExpect(jsonPath("$.status").value("UNKNOWN")); assertThat(jdbc.sql("SELECT count(*) FROM ebook_offer").query(Integer.class).single()).isZero();
    }
    @Test void 로컬에_없는_책은_Google을_부르지_않는다() throws Exception { ebook("9788936434120").andExpect(status().isNotFound()); assertThat(GoogleStub.calls()).isZero(); }
}
