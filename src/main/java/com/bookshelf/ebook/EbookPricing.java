package com.bookshelf.ebook;

import com.bookshelf.provider.CallPurpose;

import java.util.List;

public interface EbookPricing {
    List<EbookCandidate> candidates(String title, String author, CallPurpose purpose);
}
