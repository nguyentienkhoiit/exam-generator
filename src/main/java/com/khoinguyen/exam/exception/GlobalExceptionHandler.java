package com.khoinguyen.exam.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Date;

import static com.khoinguyen.exam.exception.ExceptionHelper.getTrace;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${app.error.show-trace:false}")
    private boolean showTrace;

    // 1️⃣ Validate error
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("Validation error");

        ErrorResponse error = ErrorResponse.builder()
                .error(HttpStatus.BAD_REQUEST.name())
                .message(message)
                .status(HttpStatus.BAD_REQUEST.value())
                .path(request.getRequestURI())
                .timestamp(new Date())
                .trace(showTrace ? getTrace(ex) : null)
                .build();

        return ResponseEntity.badRequest().body(error);
    }

    // 2️⃣ Business error
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            BadRequestException ex,
            HttpServletRequest request
    ) {
        ErrorResponse error = ErrorResponse.builder()
                .error(HttpStatus.BAD_REQUEST.name())
                .message(ex.getMessage())
                .status(HttpStatus.BAD_REQUEST.value())
                .path(request.getRequestURI())
                .timestamp(new Date())
                .trace(showTrace ? getTrace(ex) : null)
                .build();

        return ResponseEntity.badRequest().body(error);
    }

    // 3️⃣ Unknown error
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception ex,
            HttpServletRequest request
    ) {
        ErrorResponse error = ErrorResponse.builder()
                .error(HttpStatus.INTERNAL_SERVER_ERROR.name())
                .message("Internal server error")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .path(request.getRequestURI())
                .timestamp(new Date())
                .trace(showTrace ? getTrace(ex) : null)
                .build();

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException ex,
            HttpServletRequest request
    ) {
        ErrorResponse error = ErrorResponse.builder()
                .error(ex.getStatus().name())
                .message(ex.getMessage())
                .status(ex.getStatus().value())
                .path(request.getRequestURI())
                .timestamp(new Date())
                .trace(showTrace ? getTrace(ex) : null)
                .build();

        return ResponseEntity
                .status(ex.getStatus())
                .body(error);
    }
}