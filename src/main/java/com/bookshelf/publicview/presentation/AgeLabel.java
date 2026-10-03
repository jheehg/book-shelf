package com.bookshelf.publicview.presentation;

import java.time.Duration;
import java.time.Instant;

/** 설계 문서 2-1: 상태가 있는 칸은 "언제 기준인지"를 반드시 같이 보여준다 */
public final class AgeLabel {

    private AgeLabel() {
    }

    public static String of(Instant fetchedAt, Instant now) {
        Duration d = Duration.between(fetchedAt, now);
        if (d.toMinutes() < 1) {
            return "방금 기준";
        }
        if (d.toHours() < 1) {
            return d.toMinutes() + "분 전 기준";
        }
        if (d.toDays() < 1) {
            return d.toHours() + "시간 전 기준";
        }
        return d.toDays() + "일 전 기준";
    }
}
