package com.bookshelf.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * "지금"을 빈으로 둔다. Instant.now()를 직접 부르면 TTL·예산의 날짜 경계를 테스트할 수 없다.
 * 제공자별 날짜 경계는 이 시계의 존이 아니라 각 제공자의 초기화 시간대를 따른다 (Task 3).
 */
@Configuration
public class ClockConfig {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Bean
    Clock clock() {
        return Clock.system(SEOUL);
    }
}
