package com.bookshelf.auth.apple;

/** Apple이 코드를 거부했다(만료·이미 씀). 앱이 Apple 인증을 다시 받아 오면 된다 */
public class AppleRejectedException extends RuntimeException {
    public AppleRejectedException(String message) {
        super(message);
    }
}
