package dev.shortlink.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handle(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(Map.of("error", e.errorCode(), "message", e.getMessage()));
    }

    /**
     * 路由不匹配（例如 8 位的码、/abc/def）必须返回 404，而不是被下面的兜底吞成 500。
     * 短链路径给 HTML 页面，/api/ 下给 JSON。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public void handleNoHandler(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setHeader("Cache-Control", "no-store");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"NOT_FOUND\",\"message\":\"接口不存在\"}");
        } else {
            response.setContentType("text/html;charset=UTF-8");
            response.getWriter().write(Pages.NOT_FOUND);
        }
    }

    /**
     * 数据库不可用（创建接口走的是这条）：503 + Retry-After，而不是 500。
     * 500 表示"我错了"，503 表示"我暂时不行，你等会儿再来"——这是两件不同的事。
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDatabaseDown(Exception e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "2")
                .body(Map.of("error", "STORE_UNAVAILABLE", "message", "存储暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        // 不把堆栈或 SQL 细节返回给调用方（FR-2：跳转响应不含任何调试信息）
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "INTERNAL", "message", "服务内部错误"));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "BODY_INVALID", "message", "请求体不是合法 JSON，或字段类型不对"));
    }
}
