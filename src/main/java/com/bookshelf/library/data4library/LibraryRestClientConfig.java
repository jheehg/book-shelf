package com.bookshelf.library.data4library;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** 정보나루 전용 RestClient. HTTP/1.1 고정 이유는 KakaoRestClientConfig와 같다 */
@Configuration
public class LibraryRestClientConfig {

    @Bean
    RestClient libraryRestClient(LibraryProperties props, RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        return builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
    }
}
