package com.bookshelf.common;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 책의 식별자. 생성되었다면 올바른 ISBN-13이다 — 검증을 여기 한 곳에서만 한다.
 * 엔티티는 String으로 저장하고, 경계(스캔·URL·외부 응답)에서 이 타입을 거친다.
 */
public record Isbn13(String value) {

    private static final Pattern ISBN13 = Pattern.compile("97[89]\\d{10}");
    private static final Pattern ISBN10 = Pattern.compile("\\d{9}[\\dX]");

    public Isbn13 {
        if (value == null || !ISBN13.matcher(value).matches() || !checksum13Ok(value)) {
            throw new DomainException("올바른 ISBN이 아니다: " + value);
        }
    }

    public static Isbn13 parse(String raw) {
        if (raw == null) {
            throw new DomainException("ISBN이 없다");
        }
        String cleaned = raw.replaceAll("[\\s-]", "").toUpperCase();
        if (cleaned.length() == 10) {
            return fromIsbn10(cleaned);
        }
        return new Isbn13(cleaned);
    }

    /** 외부 응답처럼 틀린 값이 섞여 오는 곳에서 쓴다. 틀리면 버린다 */
    public static Optional<Isbn13> tryParse(String raw) {
        try {
            return Optional.of(parse(raw));
        } catch (DomainException e) {
            return Optional.empty();
        }
    }

    /**
     * ISBN 국가 번호로 책의 언어를 짐작한다 (설계 문서 2-1 같은 책 확인).
     * 978-89·979-11은 한국, 978-0·978-1은 영어권. 그 밖은 판단하지 않는다.
     */
    public Optional<String> languageHint() {
        if (value.startsWith("97889") || value.startsWith("97911")) {
            return Optional.of("ko");
        }
        if (value.startsWith("9780") || value.startsWith("9781")) {
            return Optional.of("en");
        }
        return Optional.empty();
    }

    private static Isbn13 fromIsbn10(String isbn10) {
        if (!ISBN10.matcher(isbn10).matches() || !checksum10Ok(isbn10)) {
            throw new DomainException("올바른 ISBN-10이 아니다: " + isbn10);
        }
        String body = "978" + isbn10.substring(0, 9);
        return new Isbn13(body + check13(body));
    }

    private static boolean checksum13Ok(String d) {
        return check13(d.substring(0, 12)) == d.charAt(12) - '0';
    }

    /** 앞 12자리에 1,3,1,3... 가중치를 곱해 더한 합으로 체크 숫자를 구한다 */
    private static int check13(String first12) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = first12.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10;
    }

    /** 10,9,8...1 가중치. 마지막 자리 X는 10이다. 합이 11의 배수여야 한다 */
    private static boolean checksum10Ok(String d) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char c = d.charAt(i);
            int digit = (c == 'X') ? 10 : c - '0';
            sum += digit * (10 - i);
        }
        return sum % 11 == 0;
    }
}
