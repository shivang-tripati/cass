package com.shivang.obd.common.exception;

import com.shivang.obd.common.api.error.ApiErrorCode;
import com.shivang.obd.common.api.error.CommonErrorCode;
import com.shivang.obd.common.api.error.FieldError;
import com.shivang.obd.common.api.response.RequestIdFilter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(BusinessException ex) {
        return respond(problem(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new FieldError(f.getField(), stableCode(f.getCode()), f.getDefaultMessage()))
            .toList();
        return respond(problem(CommonErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors));
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ProblemDetail> handleConstraintViolations(Exception ex) {
        List<FieldError> errors;
        if (ex instanceof ConstraintViolationException cve) {
            errors = cve.getConstraintViolations().stream()
                .map(v -> new FieldError(lastSegment(v.getPropertyPath().toString()), violationCode(v), v.getMessage()))
                .toList();
        } else {
            HandlerMethodValidationException hmve = (HandlerMethodValidationException) ex;
            errors = hmve.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                    .map(error -> new FieldError(
                        result.getMethodParameter().getParameterName(),
                        resolvableCode(error),
                        error.getDefaultMessage())))
                .toList();
        }
        return respond(problem(CommonErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return respond(problem(CommonErrorCode.BAD_REQUEST, "Malformed request body."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(problem(CommonErrorCode.BAD_REQUEST,
            "Invalid value for parameter '" + ex.getName() + "'."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParameter(MissingServletRequestParameterException ex) {
        return respond(problem(CommonErrorCode.BAD_REQUEST,
            "Missing required parameter '" + ex.getParameterName() + "'."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResourceFound(NoResourceFoundException ex) {
        return respond(problem(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        ProblemDetail problem = problem(CommonErrorCode.BAD_REQUEST);
        problem.setTitle("Method not allowed");
        problem.setDetail("Request method '" + ex.getMethod() + "' is not supported.");
        problem.setStatus(405);
        return ResponseEntity.status(405).body(problem);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        return respond(problem(CommonErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return respond(problem(CommonErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception [requestId={}]", RequestIdFilter.currentRequestId(), ex);
        return respond(problem(CommonErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred."));
    }

    private ProblemDetail problem(ApiErrorCode errorCode) {
        return problem(errorCode, errorCode.defaultMessage());
    }

    private ProblemDetail problem(ApiErrorCode errorCode, String message) {
        return problem(errorCode, message, null);
    }

    private ProblemDetail problem(ApiErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatus(errorCode.status());
        problem.setTitle(errorCode.defaultMessage());
        problem.setDetail(message);
        problem.setProperty("code", errorCode.code());
        problem.setProperty("requestId", RequestIdFilter.currentRequestId());
        problem.setProperty("timestamp", Instant.now());
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            problem.setProperty("errors", fieldErrors);
        }
        return problem;
    }

    private ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private String stableCode(String springCode) {
        if (springCode == null || springCode.isBlank()) {
            return "VALIDATION_FAILED";
        }
        int dotIndex = springCode.indexOf('.');
        String base = dotIndex > -1 ? springCode.substring(0, dotIndex) : springCode;
        return upperSnake(base);
    }

    private String violationCode(ConstraintViolation<?> violation) {
        Class<?> annotationType = violation.getConstraintDescriptor().getAnnotation().annotationType();
        return upperSnake(annotationType.getSimpleName());
    }

    private String resolvableCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        if (codes == null || codes.length == 0 || codes[0] == null) {
            return "VALIDATION_FAILED";
        }
        return stableCode(codes[0]);
    }

    private String lastSegment(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int dotIndex = path.lastIndexOf('.');
        return dotIndex > -1 ? path.substring(dotIndex + 1) : path;
    }

    private String upperSnake(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase();
    }
}
