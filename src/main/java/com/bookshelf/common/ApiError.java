package com.bookshelf.common;

/** 모든 API 오류의 모양 하나 */
public record ApiError(String code, String message) {
}
