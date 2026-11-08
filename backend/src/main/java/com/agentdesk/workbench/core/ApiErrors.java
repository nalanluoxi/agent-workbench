package com.agentdesk.workbench.core;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,Object>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,Object>> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求参数无效"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,Object>> unexpected(Exception e) {
        log.error("请求处理失败：{}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "服务处理失败"));
    }
}
