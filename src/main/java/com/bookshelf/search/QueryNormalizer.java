package com.bookshelf.search;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** 규칙과 이유: docs/notes/search-normalization.md (Task 7 Step 0) */
public final class QueryNormalizer {

    private static final int MIN = 2;
    private static final int MAX = 100;
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** 한글 호환 자모(ㄱ~ㅣ)와 공백만으로 된 입력 */
    private static final Pattern JAMO_ONLY = Pattern.compile("[\\u3131-\\u318E ]+");

    private QueryNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw new InvalidQueryException("검색어가 없다");
        }
        String q = Normalizer.normalize(raw, Normalizer.Form.NFC);
        q = SPACES.matcher(q.strip()).replaceAll(" ");
        q = q.toLowerCase(Locale.ROOT);

        int length = q.codePointCount(0, q.length());
        if (length < MIN || length > MAX) {
            throw new InvalidQueryException("검색어는 " + MIN + "~" + MAX + "자다");
        }
        if (JAMO_ONLY.matcher(q).matches()) {
            throw new InvalidQueryException("자모만으로는 검색하지 않는다");
        }
        return q;
    }
}
