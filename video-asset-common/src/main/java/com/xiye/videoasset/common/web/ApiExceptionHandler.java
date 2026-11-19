package com.xiye.videoasset.common.web;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
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
            ConstraintViolationException.class
    })
    ResponseEntity<Result<Void>> invalidRequest(Exception exception) {
        return respond(ErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<Result<Void>> notFound(Exception exception) {
        return respond(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            // 保留框架的协议响应头，例如 HTTP 405 的 Allow，调用方可据此选择请求方法。
            return ResponseEntity.status(error.getStatusCode())
                    .headers(error.getHeaders())
                    .body(new Result<>("HTTP_" + error.getStatusCode().value(), "Request failed", null));
        }

        // 异常消息可能包含请求或上游响应内容，只记录异常类型和抛出位置。
        StackTraceElement[] stackTrace = exception.getStackTrace();
        StackTraceElement failureLocation = stackTrace.length == 0 ? null : stackTrace[0];
        LOG.error("接口处理异常 exceptionType={} location={}", exception.getClass().getName(), failureLocation);
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<Result<Void>> respond(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(Result.error(errorCode));
    }
}
