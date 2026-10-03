package com.bookshelf.auth.infrastructure.provider.apple;

/** Apple이 보증한 사용자 식별자. 앱마다 다르고 바뀌지 않는다. 이메일은 받지 않는다(개인정보를 들이지 않는다) */
public record AppleIdentity(String sub) {
}
