package com.bookshelf.library.domain;

import com.bookshelf.common.DomainException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionTest {

    @Test
    void 정보나루_시도_코드_17개다() {
        assertThat(Arrays.stream(Region.values()).map(Region::code))
                .containsExactly(11, 21, 22, 23, 24, 25, 26, 29, 31, 32, 33, 34, 35, 36, 37, 38, 39);
        assertThat(Region.of(11).label()).isEqualTo("서울");
        assertThat(Region.of(39).label()).isEqualTo("제주");
    }

    @Test
    void 모르는_코드는_거부한다() {
        assertThatThrownBy(() -> Region.of(27)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Region.of(11010)).isInstanceOf(DomainException.class);   // 시군구 코드는 받지 않는다
    }
}
