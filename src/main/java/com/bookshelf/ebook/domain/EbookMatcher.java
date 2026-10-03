package com.bookshelf.ebook.domain;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 같은 책 확인. 여섯 조건을 모두 통과하고 후보가 정확히 하나일 때만 Matched다.
 * 애매하면 NoMatch — 틀린 숫자보다 숫자 없음이 낫다.
 */
public final class EbookMatcher {

    public sealed interface Result permits Matched, NoMatch {
    }

    public record Matched(EbookCandidate offer) implements Result {
    }

    /** reason은 로그용이다. */
    public record NoMatch(String reason) implements Result {
    }

    private static final Pattern PARENS = Pattern.compile("[(\\[]([^)\\]]*)[)\\]]");
    private static final Pattern VOLUME = Pattern.compile("(\\d+)권");
    private static final Pattern PART = Pattern.compile("(\\d+)부");
    private static final Pattern EPISODE = Pattern.compile("(\\d+)편");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)\\s*$");
    private static final Pattern TRAILING_SANGHA = Pattern.compile("\\s(상|중|하)\\s*$");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");

    private EbookMatcher() {
    }

    public static Result match(String bookTitle, String bookAuthors, String language,
                               List<EbookCandidate> candidates) {
        String title = normalizeTitle(bookTitle);
        String author = normalizeAuthor(firstAuthor(bookAuthors));
        if (title.isEmpty() || author.isEmpty() || language == null || candidates == null) {
            return new NoMatch("우리 책의 제목·저자·언어 또는 후보가 없다");
        }
        Volume volume = Volume.of(bookTitle);

        Map<String, EbookCandidate> passed = new LinkedHashMap<>();
        for (EbookCandidate candidate : candidates) {
            if (candidate == null || !candidate.ebook()) {
                continue;
            }
            if (!language.equals(candidate.language())) {
                continue;
            }
            if (!title.equals(normalizeTitle(candidate.title()))) {
                continue;
            }
            if (!volume.equals(Volume.of(candidate.title()))) {
                continue;
            }
            if (candidate.authors() == null || candidate.authors().stream()
                    .map(EbookMatcher::normalizeAuthor).noneMatch(author::equals)) {
                continue;
            }
            String identity = candidate.ebookIsbn13() != null ? candidate.ebookIsbn13() : candidate.volumeId();
            passed.putIfAbsent(identity, candidate);
        }
        if (passed.size() != 1) {
            return new NoMatch("같은 책 후보 " + passed.size() + "개");
        }
        EbookCandidate only = passed.values().iterator().next();
        if (!"KRW".equals(only.currency()) || only.retailPrice() == null || only.retailPrice() <= 0) {
            return new NoMatch("원화 가격 없음");
        }
        return new Matched(only);
    }

    /** NFC → 소문자 → ':' 뒤 부제 제거 → 괄호와 안 내용 제거 → 권 표시 제거 → 문장부호·공백 제거 */
    static String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String title = Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        int colon = title.indexOf(':');
        if (colon >= 0) {
            title = title.substring(0, colon);
        }
        title = PARENS.matcher(title).replaceAll(" ");
        title = VOLUME.matcher(title).replaceAll(" ");
        title = PART.matcher(title).replaceAll(" ");
        title = EPISODE.matcher(title).replaceAll(" ");
        String withoutTrailing = TRAILING_SANGHA.matcher(TRAILING_NUMBER.matcher(title.strip()).replaceAll(""))
                .replaceAll("");
        if (!NOT_LETTER_OR_DIGIT.matcher(withoutTrailing).replaceAll("").isEmpty()) {
            title = withoutTrailing;
        }
        return NOT_LETTER_OR_DIGIT.matcher(title).replaceAll("");
    }

    /** 공백 제거 → 소문자 → 끝의 "외" 제거 */
    static String normalizeAuthor(String raw) {
        if (raw == null) {
            return "";
        }
        String author = Normalizer.normalize(raw, Normalizer.Form.NFC).replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        return author.endsWith("외") ? author.substring(0, author.length() - 1) : author;
    }

    private static String firstAuthor(String authors) {
        return authors == null ? null : authors.split(",")[0].strip();
    }

    /** 권(N권 → N편 → 괄호 밖 제목 끝 숫자 → 상·중·하)과 부(N부). 둘 다 같아야 같은 권이다. */
    record Volume(String volume, String part) {

        static Volume of(String raw) {
            String title = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC);
            String volume = first(VOLUME, title);
            if (volume == null) {
                volume = first(EPISODE, title);
            }
            if (volume == null) {
                String main = PARENS.matcher(title).replaceAll(" ").strip();
                volume = first(TRAILING_NUMBER, main);
                if (volume == null) {
                    volume = first(TRAILING_SANGHA, main);
                }
            }
            if (volume == null) {
                Matcher paren = PARENS.matcher(title);
                while (paren.find()) {
                    String inside = paren.group(1).strip();
                    if (inside.equals("상") || inside.equals("중") || inside.equals("하")) {
                        volume = inside;
                        break;
                    }
                }
            }
            return new Volume(volume == null ? null : stripZeros(volume), stripZeros(first(PART, title)));
        }

        private static String first(Pattern pattern, String source) {
            Matcher matcher = pattern.matcher(source);
            return matcher.find() ? matcher.group(1) : null;
        }

        private static String stripZeros(String number) {
            return number == null || !number.chars().allMatch(Character::isDigit)
                    ? number : String.valueOf(Integer.parseInt(number));
        }
    }
}
