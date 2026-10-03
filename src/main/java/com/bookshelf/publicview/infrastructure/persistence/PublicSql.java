package com.bookshelf.publicview.infrastructure.persistence;

/**
 * 공개 조회가 함께 쓰는 SQL 조각. 두 곳에서 따로 쓰면 언젠가 어긋난다.
 *
 * 책 페이지·사이트맵 항목이 있다는 것 자체가 "누군가 이 책을 기록했다"를 알린다.
 * 백필은 공개 여부와 상관없이 모든 기록의 책을 채우므로, book 행이 있다는 것만으로 공개하면
 * 비공개로 둔 책이 드러난다(설계 문서 7-3: PRIVATE는 읽었다는 사실조차 숨긴다).
 * 그래서 열린 서재에 공개된 기록(PUBLIC 또는 RATING_ONLY)이 하나라도 있는 책만 공개한다.
 */
final class PublicSql {

    private PublicSql() {
    }

    /** 바깥 쿼리에서 book의 별칭이 b여야 한다 */
    static final String BOOK_IS_LISTED = """
            EXISTS (SELECT 1 FROM reading_record lr
                    JOIN member lm ON lm.id = lr.member_id
                    WHERE lr.isbn13 = b.isbn13
                      AND lr.visibility IN ('PUBLIC', 'RATING_ONLY')
                      AND lm.shelf_public = TRUE
                      AND lm.deleted_at IS NULL)
            """;
}
