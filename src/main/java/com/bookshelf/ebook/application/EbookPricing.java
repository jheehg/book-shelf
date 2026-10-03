package com.bookshelf.ebook.application;

import com.bookshelf.ebook.domain.EbookCandidate;

import com.bookshelf.provider.domain.CallPurpose;

import java.util.List;

public interface EbookPricing {
    List<EbookCandidate> candidates(String title, String author, CallPurpose purpose);
}
