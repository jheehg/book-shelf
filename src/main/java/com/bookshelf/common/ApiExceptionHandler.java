package com.bookshelf.common;

import com.bookshelf.auth.ApiAuthenticationEntryPoint;
import com.bookshelf.auth.UnauthorizedException;
import com.bookshelf.provider.ProviderUnavailableException;
import com.bookshelf.provider.UserQuotaExceededException;
import com.bookshelf.search.SearchUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * /api 의 예외 → 응답. Exception.class를 잡지 않는다 — 예상 못 한 예외는 500으로 드러나야 한다.
 * 4xx는 warn이 아니라 debug: 사용자의 잘못된 입력은 운영 경보가 아니다. 503은 warn(제공자 장애 신호).
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.bookshelf")
public class ApiExceptionHandler {

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> badRequest(DomainException e) {
        log.debug("400 {}", e.getMessage());
        return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST", e.getMessage()));
    }

    /** 필터에서 거부된 401과 본문이 같아야 한다 — 어느 단계에서 실패했는지 드러내지 않는다 */
    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<String> unauthorized(UnauthorizedException e) {
        log.debug("401 {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body(ApiAuthenticationEntryPoint.BODY);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e) {
        log.debug("404 {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "찾을 수 없어요"));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("CONFLICT", e.getMessage()));
    }

    /** 검사를 통과한 동시 요청이 UNIQUE 제약에 걸린 경우. 제약 이름을 응답에 내보내지 않는다 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        log.debug("409 {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("CONFLICT", "이미 있어요"));
    }

    @ExceptionHandler(UserQuotaExceededException.class)
    ResponseEntity<ApiError> tooMany(UserQuotaExceededException e) {
        log.debug("429 {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiError("TOO_MANY_REQUESTS", "오늘은 검색을 너무 많이 했어요. 내일 다시 시도해 주세요"));
    }

    @ExceptionHandler({SearchUnavailableException.class, ProviderUnavailableException.class})
    ResponseEntity<ApiError> tryLater(RuntimeException e) {
        log.warn("503 {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("TRY_LATER", "잠시 후 다시 시도해 주세요"));
    }
}
