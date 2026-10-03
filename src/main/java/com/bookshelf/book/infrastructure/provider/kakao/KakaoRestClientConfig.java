package com.bookshelf.book.infrastructure.provider.kakao;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * 카카오 전용 RestClient. 타임아웃을 전역 빌더에 걸지 않는다(제공자마다 값이 다르다 — 설계 문서 6-2).
 *
 * HTTP/1.1 고정: JDK HttpClient는 HTTP/2를 먼저 시도하는데 WireMock이 받지 못해
 * 요청이 서버에 닿지도 않는다. 카카오도 HTTP/1.1로 충분하다.
 */
@Configuration
public class KakaoRestClientConfig {

    @Bean
    RestClient kakaoRestClient(KakaoProperties props, RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        return builder
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "KakaoAK " + props.restApiKey())
                .requestFactory(factory)
                .build();
    }
}
