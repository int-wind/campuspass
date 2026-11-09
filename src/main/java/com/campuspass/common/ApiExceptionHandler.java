package com.campuspass.common;

import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.access.AccessDeniedException;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.UndeclaredThrowableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> handle(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(body(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage()).orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(body("INVALID_ARGUMENT", message));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<?> handleDuplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("DUPLICATE_RESOURCE", "资源已存在或请求重复"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body("FORBIDDEN", "不允许访问"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof BlockException block) return handleRateLimit(block);
            cause = cause instanceof UndeclaredThrowableException wrapped
                    ? wrapped.getUndeclaredThrowable() : cause.getCause();
        }
        log.error("Unhandled API exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body("INTERNAL_ERROR", "服务暂时不可用"));
    }

    @ExceptionHandler(BlockException.class)
    public ResponseEntity<?> handleRateLimit(BlockException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body("SENTINEL_RATE_LIMITED", "请求过于频繁，请稍后重试"));
    }

    private Map<String, Object> body(String code, String message) {
        return Map.of("timestamp", Instant.now(), "code", code, "message", message);
    }
}
