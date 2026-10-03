package com.bookshelf.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/** Google Books용 WireMock. */
public final class GoogleStub {
    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());
    private static final String PATH = "/books/v1/volumes";
    static { SERVER.start(); }
    private GoogleStub() { }
    public static String baseUrl() { return SERVER.baseUrl(); }
    public static void reset() { SERVER.resetAll(); }
    public static void returns(String body) { SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body))); }
    public static void failsWith(int status) { SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status))); }
    public static int calls() { return SERVER.findAll(getRequestedFor(urlPathEqualTo(PATH))).size(); }
    public static String fixture(String isbn) {
        try (InputStream input = GoogleStub.class.getResourceAsStream("/google/title-" + isbn + ".json")) {
            if (input == null) throw new IllegalArgumentException("fixture 없음: " + isbn);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
}
