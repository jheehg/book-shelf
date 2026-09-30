package com.bookshelf.auth.apple;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** HTTP/1.1 고정 이유는 KakaoRestClientConfig와 같다(WireMock이 HTTP/2 업그레이드를 받지 못한다) */
@Configuration
public class AppleRestClientConfig {

    @Bean
    RestClient appleRestClient(AppleProperties props, RestClient.Builder builder) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        return builder.baseUrl(props.authBaseUrl()).requestFactory(factory).build();
    }
}
