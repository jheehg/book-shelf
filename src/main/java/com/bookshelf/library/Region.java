package com.bookshelf.library;

import com.bookshelf.common.DomainException;

/** 정보나루 시도 코드(매뉴얼). 순서는 화면의 지역 목록 순서다 */
public enum Region {
    SEOUL(11, "서울"), BUSAN(21, "부산"), DAEGU(22, "대구"), INCHEON(23, "인천"), GWANGJU(24, "광주"),
    DAEJEON(25, "대전"), ULSAN(26, "울산"), SEJONG(29, "세종"), GYEONGGI(31, "경기"), GANGWON(32, "강원"),
    CHUNGBUK(33, "충북"), CHUNGNAM(34, "충남"), JEONBUK(35, "전북"), JEONNAM(36, "전남"),
    GYEONGBUK(37, "경북"), GYEONGNAM(38, "경남"), JEJU(39, "제주");

    private final int code;
    private final String label;

    Region(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static Region of(int code) {
        for (Region r : values()) {
            if (r.code == code) {
                return r;
            }
        }
        throw new DomainException("모르는 지역 코드: " + code);
    }
}
