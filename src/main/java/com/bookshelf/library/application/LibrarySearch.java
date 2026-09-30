package com.bookshelf.library.application;

import com.bookshelf.library.Region;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** 로컬 테이블만 읽는다 — 사용자의 도서관 찾기는 정보나루를 부르지 않는다 */
@Component
public class LibrarySearch {

    static final int LIMIT = 50;
    private static final String COLUMNS = "lib_code, name, address, tel, homepage, region_code";
    private static final RowMapper<LibraryView> ROW = (rs, n) -> new LibraryView(rs.getString("lib_code"),
            rs.getString("name"), rs.getString("address"), rs.getString("tel"), rs.getString("homepage"),
            rs.getInt("region_code"));

    private final JdbcClient jdbc;

    public LibrarySearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 정렬은 COLLATE "C"(코드포인트 순). 한글 음절은 코드포인트가 가나다순이다.
     * DB 기본 collation(en_US.utf8)은 "나"를 "가"보다 앞에 두고, 서버마다 결과가 달라진다(실측).
     */
    public List<LibraryView> search(int regionCode, String q) {
        Region region = Region.of(regionCode);
        String pattern = q == null || q.isBlank() ? null : "%" + escapeLike(q.strip()) + "%";
        return jdbc.sql("SELECT " + COLUMNS + " FROM library WHERE region_code = :r"
                        + (pattern == null ? "" : " AND (name ILIKE :p ESCAPE '\\' OR address ILIKE :p ESCAPE '\\')")
                        + " ORDER BY name COLLATE \"C\" LIMIT " + LIMIT)
                .param("r", region.code())
                .param("p", pattern)          // 검색어가 없으면 SQL에 :p가 없다 — 남는 파라미터는 무시된다
                .query(ROW)
                .list();
    }

    public Optional<LibraryView> find(String libCode) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM library WHERE lib_code = :c")
                .param("c", libCode).query(ROW).optional();
    }

    /** ILIKE의 %, _ 와 이스케이프 문자 자체를 글자 그대로로 */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
