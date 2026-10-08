package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.application.OperationNotPermittedException;
import com.altronixsoft.opp.order.application.OrderConcurrentlyModifiedException;
import com.altronixsoft.opp.order.application.OrderNotFoundException;
import com.altronixsoft.opp.order.application.ProductNotAvailableException;
import com.altronixsoft.opp.order.domain.IllegalOrderTransitionException;
import com.altronixsoft.opp.order.domain.InvalidOrderException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure of the API into an RFC 9457 problem (architecture §11): {@code type} is
 * {@code urn:problem-type:<code>} (see {@link Problems}), validation failures list their fields in {@code errors}.
 * Messages never contain stack traces, SQL or the rejected values.
 *
 * <p>The failures Spring MVC itself raises (wrong method, wrong media type, unknown path, ...) go through
 * {@link ResponseEntityExceptionHandler}; {@link #handleExceptionInternal} gives them a {@code type} too.
 */
@RestControllerAdvice
class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);
    private static final URI BLANK_TYPE = URI.create("about:blank");

    // ---- domain and application failures ----

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Object> orderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        return problem(Problems.of(HttpStatus.NOT_FOUND, Problems.ORDER_NOT_FOUND, ex.getMessage()), request);
    }

    @ExceptionHandler(IllegalOrderTransitionException.class)
    ResponseEntity<Object> illegalTransition(IllegalOrderTransitionException ex, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, Problems.ORDER_STATE_CONFLICT, ex.getMessage());
        problem.setProperty("currentStatus", ex.from());
        return problem(problem, request);
    }

    @ExceptionHandler(OrderConcurrentlyModifiedException.class)
    ResponseEntity<Object> concurrentModification(OrderConcurrentlyModifiedException ex, HttpServletRequest request) {
        return problem(
                Problems.of(
                        HttpStatus.CONFLICT,
                        Problems.CONCURRENT_MODIFICATION,
                        "The order was changed by another request; fetch it again and retry"),
                request);
    }

    @ExceptionHandler(ProductNotAvailableException.class)
    ResponseEntity<Object> productNotAvailable(ProductNotAvailableException ex, HttpServletRequest request) {
        ProblemDetail problem =
                Problems.of(HttpStatus.UNPROCESSABLE_CONTENT, Problems.PRODUCT_NOT_AVAILABLE, ex.getMessage());
        problem.setProperty("skus", ex.skus());
        return problem(problem, request);
    }

    @ExceptionHandler(InvalidOrderException.class)
    ResponseEntity<Object> invalidOrder(InvalidOrderException ex, HttpServletRequest request) {
        return problem(Problems.of(HttpStatus.BAD_REQUEST, Problems.INVALID_ORDER, ex.getMessage()), request);
    }

    @ExceptionHandler(OperationNotPermittedException.class)
    ResponseEntity<Object> notPermitted(OperationNotPermittedException ex, HttpServletRequest request) {
        return problem(Problems.of(HttpStatus.FORBIDDEN, Problems.FORBIDDEN, ex.getMessage()), request);
    }

    /** Anything unforeseen: logged with its stack trace, answered without details. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, HttpServletRequest request) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex; // Spring Security's filter answers these (401/403 problems), not this advice
        }
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(
                Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, Problems.INTERNAL_ERROR, "Unexpected error"), request);
    }

    // ---- request validation ----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            errors.add(violation(field, error.getDefaultMessage()));
        }
        return validationFailed(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = parameterName(result.getMethodParameter());
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(violation(field, error.getDefaultMessage()));
            }
        });
        return validationFailed(errors, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return validationFailed(List.of(violation(ex.getParameterName(), "is required")), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String expected = ex.getRequiredType() == null
                ? "a valid value"
                : ex.getRequiredType().getSimpleName();
        return validationFailed(List.of(violation(ex.getPropertyName(), "must be " + expected)), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // The exception text can quote the offending input; say only that the body is unusable.
        return handleExceptionInternal(
                ex,
                Problems.of(
                        HttpStatus.BAD_REQUEST,
                        Problems.MALFORMED_REQUEST,
                        "The request body is missing or is not valid JSON of the expected shape"),
                headers,
                status,
                request);
    }

    /** Gives the problems Spring MVC creates itself a {@code type} and the request path as {@code instance}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            body = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail problem) {
            if (problem.getType() == null || BLANK_TYPE.equals(problem.getType())) {
                problem.setType(Problems.type(codeFor(statusCode)));
            }
            if (problem.getInstance() == null && request instanceof ServletWebRequest servletRequest) {
                problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
            }
        }
        HttpHeaders withContentType = new HttpHeaders();
        withContentType.putAll(headers);
        withContentType.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleExceptionInternal(ex, body, withContentType, statusCode, request);
    }

    // ---- helpers ----

    private ResponseEntity<Object> validationFailed(List<Map<String, String>> errors, WebRequest request) {
        ProblemDetail problem =
                Problems.of(HttpStatus.BAD_REQUEST, Problems.VALIDATION_FAILED, "Request validation failed");
        problem.setProperty("errors", errors);
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static ResponseEntity<Object> problem(ProblemDetail problem, HttpServletRequest request) {
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(problem.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static Map<String, String> violation(String field, String message) {
        Map<String, String> violation = new LinkedHashMap<>();
        violation.put("field", field);
        violation.put("message", message);
        return violation;
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
        if (annotation != null && !annotation.name().isEmpty()) {
            return annotation.name();
        }
        return parameter.getParameterName() == null ? "parameter" : parameter.getParameterName();
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> Problems.MALFORMED_REQUEST;
            case 404 -> Problems.NOT_FOUND;
            case 405 -> Problems.METHOD_NOT_ALLOWED;
            case 406 -> Problems.NOT_ACCEPTABLE;
            case 415 -> Problems.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? Problems.INTERNAL_ERROR : Problems.MALFORMED_REQUEST;
        };
    }
}
