package com.bookshelf.support;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 통합 테스트마다 @BeforeEach에서 부른다. 테이블을 추가하면 여기도 추가한다 */
public final class DbCleaner {

    private DbCleaner() {
    }

    public static void clean(JdbcClient jdbc) {
        jdbc.sql("""
                TRUNCATE member, reading_record, book, sale_snapshot, book_lookup_failure,
                         refresh_request, search_cache, provider_call_counter, refresh_token, user_call_counter, library, library_sync_run, member_library, member_library_change, library_holding
                RESTART IDENTITY
                """).update();
    }
}
