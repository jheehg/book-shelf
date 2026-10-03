package com.bookshelf.ebook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EbookMatcherTest {

    private static EbookCandidate ebook(String id, String title, String author, String language, Integer price) {
        return new EbookCandidate(id, title, List.of(author), language, true, price, "KRW", "979" + id,
                "https://books.google.com/" + id);
    }

    private static EbookCandidate print(String id, String title, String author) {
        return new EbookCandidate(id, title, List.of(author), "ko", false, null, null, null,
                "https://books.google.com/" + id);
    }

    private static Integer price(EbookMatcher.Result result) {
        return result instanceof EbookMatcher.Matched matched ? matched.offer().retailPrice() : null;
    }

    @Test
    void 같은_책이_하나면_그_가격을_준다() {
        var result = EbookMatcher.match("소년이 온다", "한강", "ko",
                List.of(ebook("1", "소년이 온다", "한강", "ko", 10800)));

        assertThat(price(result)).isEqualTo(10800);
    }

    @Test
    void 종이책_항목은_후보로_세지_않는다() {
        var result = EbookMatcher.match("소년이 온다", "한강", "ko", List.of(
                print("p1", "소년이 온다", "한강"),
                ebook("1", "소년이 온다", "한강", "ko", 10800),
                print("p2", "소년이 온다(특별한정판)(양장본 HardCover)", "한강")));

        assertThat(price(result)).isEqualTo(10800);
    }

    @Test
    void 언어가_다르면_탈락이다() {
        assertThat(price(EbookMatcher.match("Atomic Habits", "James Clear", "en",
                List.of(ebook("1", "Atomic Habits (Tamil)", "James Clear", "ta", 11443))))).isNull();
        assertThat(price(EbookMatcher.match("Atomic Habits", "James Clear", "en",
                List.of(ebook("1", "Atomic Habits", "James Clear", "ta", 11443))))).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "채식주의자 | 채식주의자(개정판)",
            "아주 작은 습관의 힘(50만 부 기념 스페셜 에디션) | 아주 작은 습관의 힘(50만 부 기념 스페셜 에디션)",
            "소년이 온다 | 소년이 온다: 한강 장편소설",
            "Atomic Habits | ATOMIC HABITS!",
            "불편한 편의점(벚꽃 에디션) | 불편 한 편의점"
    })
    void 괄호_부제_대소문자_문장부호_공백은_무시한다(String ours, String theirs) {
        assertThat(price(EbookMatcher.match(ours, "작가", "ko",
                List.of(ebook("1", theirs, "작가", "ko", 9000))))).isEqualTo(9000);
    }

    @Test
    void 제목이_다르면_탈락이다() {
        assertThat(price(EbookMatcher.match("아주 작은 습관의 힘", "제임스 클리어", "ko",
                List.of(ebook("1", "하루 10분 가장 짧은 습관 수업", "제임스 클리어", "ko", 8694))))).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "토지 1(1부 1권) | 토지 18권",
            "토지 1(1부 1권) | 토지 1권",
            "불편한 편의점 | 불편한 편의점 2",
            "총균쇠 상 | 총균쇠 하",
            "해리 포터 3 | 해리 포터 4"
    })
    void 권_표시가_다르거나_한쪽에만_있으면_탈락이다(String ours, String theirs) {
        assertThat(price(EbookMatcher.match(ours, "작가", "ko",
                List.of(ebook("1", theirs, "작가", "ko", 9000))))).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "총균쇠 상 | 총균쇠(상)",
            "토지 2(1부 2권) | 토지 2(1부 2권)",
            "해리 포터 3 | 해리 포터 3권",
            "1984 | 1984"
    })
    void 권_표시가_같으면_통과한다(String ours, String theirs) {
        assertThat(price(EbookMatcher.match(ours, "작가", "ko",
                List.of(ebook("1", theirs, "작가", "ko", 9000))))).isEqualTo(9000);
    }

    @Test
    void 저자는_우리_쪽_첫_이름을_공백_대소문자_외를_무시하고_완전히_같아야_한다() {
        assertThat(price(EbookMatcher.match("소년이 온다", "한강, 다른 사람", "ko",
                List.of(ebook("1", "소년이 온다", "한 강", "ko", 10800))))).isEqualTo(10800);
        assertThat(price(EbookMatcher.match("소년이 온다", "한강 외", "ko",
                List.of(ebook("1", "소년이 온다", "한강", "ko", 10800))))).isEqualTo(10800);
        assertThat(price(EbookMatcher.match("소년이 온다", "한강", "ko",
                List.of(ebook("1", "소년이 온다", "Han Kang", "ko", 10800))))).isNull();
        assertThat(price(EbookMatcher.match("토지 1", "박경리", "ko",
                List.of(ebook("1", "토지 1", "박경리 朴景利", "ko", 10710))))).isNull();
    }

    @Test
    void 같은_책_후보가_둘이면_보여주지_않는다() {
        var result = EbookMatcher.match("채식주의자", "한강", "ko", List.of(
                ebook("1", "채식주의자", "한강", "ko", 12240),
                ebook("2", "채식주의자(개정판)", "한강", "ko", 11000)));

        assertThat(price(result)).isNull();
        assertThat(result).isInstanceOfSatisfying(EbookMatcher.NoMatch.class,
                noMatch -> assertThat(noMatch.reason()).contains("2"));
    }

    @Test
    void 같은_전자책이_두_번_오면_하나로_센다() {
        var ebook = ebook("1", "소년이 온다", "한강", "ko", 10800);

        assertThat(price(EbookMatcher.match("소년이 온다", "한강", "ko", List.of(ebook, ebook)))).isEqualTo(10800);
    }

    @Test
    void 영원이거나_원화가_아니거나_가격이_없으면_보여주지_않는다() {
        for (EbookCandidate candidate : List.of(
                ebook("1", "세이노의 가르침", "세이노", "ko", 0),
                new EbookCandidate("1", "세이노의 가르침", List.of("세이노"), "ko", true, 9, "USD", null, "l"),
                ebook("1", "세이노의 가르침", "세이노", "ko", null))) {
            assertThat(price(EbookMatcher.match("세이노의 가르침", "세이노", "ko", List.of(candidate)))).isNull();
        }
    }

    @Test
    void 저자나_제목이_없거나_후보가_없으면_없음이다() {
        assertThat(price(EbookMatcher.match("소년이 온다", null, "ko",
                List.of(ebook("1", "소년이 온다", "한강", "ko", 1))))).isNull();
        assertThat(price(EbookMatcher.match("소년이 온다", "한강", "ko", List.of()))).isNull();
        assertThat(price(EbookMatcher.match("소년이 온다", "한강", "ko", List.of(
                new EbookCandidate("1", "소년이 온다", null, "ko", true, 10800, "KRW", null, "l"))))).isNull();
    }

    @Test
    void 권_표시를_뽑는다() {
        assertThat(EbookMatcher.Volume.of("토지 1(1부 1권)")).isEqualTo(new EbookMatcher.Volume("1", "1"));
        assertThat(EbookMatcher.Volume.of("토지 18권")).isEqualTo(new EbookMatcher.Volume("18", null));
        assertThat(EbookMatcher.Volume.of("아주 작은 습관의 힘(50만 부 기념 스페셜 에디션)"))
                .isEqualTo(new EbookMatcher.Volume(null, null));
        assertThat(EbookMatcher.Volume.of("해리 포터와 마법사의 돌 1(해리포터 20주년 개정판)"))
                .isEqualTo(new EbookMatcher.Volume("1", null));
        assertThat(EbookMatcher.Volume.of("총균쇠(하)")).isEqualTo(new EbookMatcher.Volume("하", null));
    }
}
