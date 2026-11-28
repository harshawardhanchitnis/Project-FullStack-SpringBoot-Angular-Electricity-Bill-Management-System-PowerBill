package com.powerbill.common;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem && request instanceof ServletWebRequest servletRequest) {
            problem.setProperty(CorrelationIdFilter.ATTRIBUTE,
                    servletRequest.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE));
            // Unexpected failures never leak details; deliberate 503 responses keep their safe message.
            if (status.is5xxServerError() && status.value() != 503) {
                problem.setDetail("The server could not complete the request.");
            }
        }
        HttpHeaders responseHeaders = new HttpHeaders(headers);
        responseHeaders.setCacheControl("no-store");
        return super.createResponseEntity(body, responseHeaders, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException error,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        error.getBindingResult().getFieldErrors().forEach(field ->
                errors.putIfAbsent(field.getField(), field.getDefaultMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "One or more fields are invalid.");
        problem.setProperty("fieldErrors", errors);
        return handleExceptionInternal(error, problem, headers, status, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(HttpServletRequest request) {
        return problem(request, HttpStatus.FORBIDDEN, "Access to this resource is denied.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthenticationFailure(HttpServletRequest request) {
        return problem(request, HttpStatus.UNAUTHORIZED, "Authentication is required for this resource.");
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ProblemDetail handleDuplicate(HttpServletRequest request) {
        return problem(request, HttpStatus.CONFLICT, "The resource changed concurrently. Reload it and try again.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedException(Exception error, HttpServletRequest request) {
        // The response stays generic; the log keeps the full stack trace, keyed by correlation ID, with messages
        // redacted because database errors can echo row values such as email addresses.
        LOG.error("Unhandled request error on {} {} (correlationId={})", request.getMethod(), request.getRequestURI(),
                request.getAttribute(CorrelationIdFilter.ATTRIBUTE), redacted(error, 0));
        return problem(request, HttpStatus.INTERNAL_SERVER_ERROR, "The server could not complete the request.");
    }

    /** Copies an exception chain with personal data removed from the messages and the stack traces kept. */
    static Throwable redacted(Throwable error, int depth) {
        String message = error.getMessage() == null ? null : error.getMessage()
                .replaceAll("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+", "[email]")
                .replaceAll("=\\([^)]*\\)", "=([redacted])")
                .replaceAll("\\d{6,}", "[number]");
        Throwable cause = error.getCause() == null || depth >= 5 ? null : redacted(error.getCause(), depth + 1);
        var copy = new RedactedException(error.getClass().getName() + (message == null ? "" : ": " + message), cause);
        copy.setStackTrace(error.getStackTrace());
        return copy;
    }

    private static final class RedactedException extends RuntimeException {
        RedactedException(String message, Throwable cause) {
            // The stack trace must stay writable, or setStackTrace() silently keeps this constructor's trace.
            super(message, cause, false, true);
        }
    }

    private ProblemDetail problem(HttpServletRequest request, HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty(CorrelationIdFilter.ATTRIBUTE, request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        return problem;
    }
}
