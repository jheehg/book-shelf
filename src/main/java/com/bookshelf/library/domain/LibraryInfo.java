package com.bookshelf.library.domain;

/** 좌표는 정보나루가 문자열로 준다. 읽지 못하면 null — 목록에서 빼지 않는다 */
public record LibraryInfo(String libCode, String name, String address, String tel,
                          Double latitude, Double longitude, String homepage) {
}
