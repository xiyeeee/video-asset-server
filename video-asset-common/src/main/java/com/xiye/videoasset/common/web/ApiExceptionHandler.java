package com.xiye.videoasset.common.web;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.common.Result;
import jakarta.validation.ConstraintViolationException;
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
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Result<Void>> business(BusinessException exception) {
        return respond(exception.errorCode());
    }

    @ExceptionHandler({ServletRequestBindingException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
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
            // Preserve framework statuses and headers such as Allow on HTTP 405.
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(new Result<>("HTTP_" + error.getStatusCode().value(), "Request failed", null));
        }
        return respond(ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<Result<Void>> respond(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(Result.error(errorCode));
    }
}
