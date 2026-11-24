package com.xiye.videoasset.common.web;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import java.sql.SQLException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Result<Void>> business(BusinessException exception) {
        return respond(exception.errorCode());
    }

    @ExceptionHandler({
            ServletRequestBindingException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class,
            ConstraintViolationException.class
    })
    ResponseEntity<Result<Void>> invalidRequest(Exception exception) {
        return respond(ErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Result<Void>> uploadTooLarge() {
        return respond(ErrorCode.VIDEO_TOO_LARGE);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<Result<Void>> notFound(Exception exception) {
        return respond(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse error) {
            // 保留框架的协议响应头，例如 HTTP 405 的 Allow，调用方可据此选择请求方法。
            return ResponseEntity.status(error.getStatusCode())
                    .headers(error.getHeaders())
                    .body(new Result<>("HTTP_" + error.getStatusCode().value(), "Request failed", null));
        }

        // SQL 语句使用预编译参数；只记录脱敏后的数据库诊断信息，不输出请求体或绑定参数。
        SQLException sqlFailure = findSqlException(exception);
        if (sqlFailure != null) {
            String message = safeMessage(sqlFailure.getMessage());
            LOG.error("接口 SQL 执行失败 method={} path={} sqlState={} vendorCode={} message={}",
                    request.getMethod(), request.getRequestURI(),
                    sqlFailure.getSQLState(), sqlFailure.getErrorCode(), message,
                    exception);
            return respond(ErrorCode.INTERNAL_ERROR);
        }

        StackTraceElement[] stackTrace = exception.getStackTrace();
        StackTraceElement failureLocation = stackTrace.length == 0 ? null : stackTrace[0];
        LOG.error("接口处理异常 method={} path={} exceptionType={} location={}", request.getMethod(),
                request.getRequestURI(), exception.getClass().getName(), failureLocation, exception);
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    private static String safeMessage(String message) {
        if (message == null) {
            return "unknown";
        }
        String sanitized = message.replaceAll("[\\r\\n\\t]+", " ");
        return sanitized.length() <= 400 ? sanitized : sanitized.substring(0, 400);
    }

    private static SQLException findSqlException(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private ResponseEntity<Result<Void>> respond(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(Result.error(errorCode));
    }
}
