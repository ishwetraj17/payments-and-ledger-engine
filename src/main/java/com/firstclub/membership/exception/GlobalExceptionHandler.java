package com.firstclub.membership.exception;

import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.platform.ratelimit.RateLimitExceededException;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.payments.exception.PaymentIntentException;
import com.firstclub.payments.exception.PaymentMethodException;
import com.firstclub.payments.routing.exception.RoutingException;
import com.firstclub.subscription.exception.SubscriptionException;
import com.firstclub.support.exception.SupportCaseException;
import jakarta.validation.ConstraintViolationException;
import lombok.Data;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.firstclub.platform.concurrency.ConcurrencyConflictException;
import com.firstclub.platform.errors.BaseDomainException;
import com.firstclub.platform.logging.StructuredLogFields;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for the application
 *
 * Catches all exceptions and returns consistent JSON error responses.
 * Every new exception type gets its own handler — never rely solely on the
 * generic catch-all, which hides useful diagnostic information.
 *
 * Implemented by Shwet Raj
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle gateway routing errors (no eligible gateway, rule not found)
     */
    @ExceptionHandler(RoutingException.class)
    public ResponseEntity<ErrorResponse> handleRoutingException(RoutingException e) {
        log.error("Routing error [{}]: {}", e.getErrorCode(), e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle payment intent and attempt domain errors
     */
    @ExceptionHandler(PaymentIntentException.class)
    public ResponseEntity<ErrorResponse> handlePaymentIntentException(PaymentIntentException e) {
        log.error("PaymentIntent error [{}]: {}", e.getErrorCode(), e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle payment method and mandate domain errors
     */
    @ExceptionHandler(PaymentMethodException.class)
    public ResponseEntity<ErrorResponse> handlePaymentMethodException(PaymentMethodException e) {
        log.error("PaymentMethod error [{}]: {}", e.getErrorCode(), e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle subscription domain errors (subscriptions v2, schedules)
     */
    @ExceptionHandler(SubscriptionException.class)
    public ResponseEntity<ErrorResponse> handleSubscriptionException(SubscriptionException e) {
        log.error("Subscription error [{}]: {}", e.getErrorCode(), e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle catalog domain errors (products, prices, price versions)
     */
    @ExceptionHandler(CatalogException.class)
    public ResponseEntity<ErrorResponse> handleCatalogException(CatalogException e) {
        log.error("Catalog error [{}]: {}", e.getErrorCode(), e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle customer domain errors
     */
    @ExceptionHandler(CustomerException.class)
    public ResponseEntity<ErrorResponse> handleCustomerException(CustomerException e) {
        log.error("Customer error [{}]: {}", e.getErrorCode(), e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle support-case business logic errors (Phase 18).
     */
    @ExceptionHandler(SupportCaseException.class)
    public ResponseEntity<ErrorResponse> handleSupportCaseException(SupportCaseException e) {
        log.error("Support case error [{}]: {}", e.getErrorCode(), e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle merchant/tenant-specific business logic errors
     */
    @ExceptionHandler(MerchantException.class)
    public ResponseEntity<ErrorResponse> handleMerchantException(MerchantException e) {
        log.error("Merchant error [{}]: {}", e.getErrorCode(), e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle membership-specific business logic errors
     */
    @ExceptionHandler(MembershipException.class)
    public ResponseEntity<ErrorResponse> handleMembershipException(MembershipException e) {
        log.error("Membership error [{}]: {}", e.getErrorCode(), e.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();
            
        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle Spring Security bad-credentials (wrong username or password).
     * Mapping to 401 rather than letting it propagate as 500.
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException e) {
        log.warn("Bad credentials: {}", e.getMessage());
        ErrorResponse error = ErrorResponse.builder()
            .message("Invalid email or password")
            .errorCode("BAD_CREDENTIALS")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.UNAUTHORIZED.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * Handle validation errors from @Valid on @RequestBody
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException e) {
        log.warn("Validation error: {}", e.getMessage());
        
        Map<String, String> validationErrors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            validationErrors.put(fieldName, errorMessage);
        });

        ErrorResponse error = ErrorResponse.builder()
            .message("Validation failed")
            .errorCode("VALIDATION_ERROR")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .validationErrors(validationErrors)
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();
            
        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle @Validated constraint violations on path/query parameters.
     * Example: GET /users/-1 when @Positive is placed on the id param.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("Constraint violation: {}", e.getMessage());

        Map<String, String> validationErrors = e.getConstraintViolations().stream()
            .collect(Collectors.toMap(
                v -> {
                    String path = v.getPropertyPath().toString();
                    // Strip method name prefix from path (e.g. "getUserById.id" -> "id")
                    int dot = path.lastIndexOf('.');
                    return dot >= 0 ? path.substring(dot + 1) : path;
                },
                v -> v.getMessage(),
                (existing, replacement) -> existing
            ));

        ErrorResponse error = ErrorResponse.builder()
            .message("Invalid request parameters")
            .errorCode("CONSTRAINT_VIOLATION")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .validationErrors(validationErrors)
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle malformed or unreadable JSON in the request body.
     * Without this, Spring returns a non-standard 400 with an HTML error page.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException e) {
        log.warn("Malformed JSON request: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message("Malformed JSON request body")
            .errorCode("INVALID_JSON")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle requests to routes that don't exist (404).
     * Without this, Spring returns its default white-label error page.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("No resource found: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message("The requested endpoint does not exist")
            .errorCode("ENDPOINT_NOT_FOUND")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.NOT_FOUND.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle wrong HTTP method on a valid endpoint (405 Method Not Allowed).
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {} — supported: {}", e.getMethod(), e.getSupportedMethods());

        String supported = e.getSupportedHttpMethods() != null
            ? e.getSupportedHttpMethods().stream().map(Object::toString).collect(Collectors.joining(", "))
            : "unknown";

        ErrorResponse error = ErrorResponse.builder()
            .message(String.format("HTTP method '%s' is not allowed. Supported: %s", e.getMethod(), supported))
            .errorCode("METHOD_NOT_ALLOWED")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.METHOD_NOT_ALLOWED.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(error);
    }

    /**
     * Handle type mismatch on path/query variables.
     * Example: GET /users/abc when id is a Long.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Type mismatch for parameter '{}': value='{}', expected type={}",
            e.getName(), e.getValue(), e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown");

        String expectedType = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown";
        ErrorResponse error = ErrorResponse.builder()
            .message(String.format("Invalid value '%s' for parameter '%s'. Expected type: %s",
                e.getValue(), e.getName(), expectedType))
            .errorCode("INVALID_PARAMETER_TYPE")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Handle Spring Security access denied (403 Forbidden).
     * Without this, AuthorizationDeniedException is caught by the generic handler returning 500.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AuthorizationDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message("Access denied: you do not have permission to perform this action")
            .errorCode("ACCESS_DENIED")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.FORBIDDEN.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * Handle Hibernate optimistic locking failures (stale object state — concurrent update won the race).
     * Maps to HTTP 409 so clients can retry the read-modify-write cycle.
     *
     * Triggered when two transactions attempt to modify the same entity row and the @Version field
     * on the loser's UPDATE detects that the row was already mutated by the winner's commit.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException e) {
        String entityName = e.getPersistentClassName() != null
            ? e.getPersistentClassName().substring(e.getPersistentClassName().lastIndexOf('.') + 1)
            : "Unknown";
        log.warn("Optimistic lock conflict: entity={} requestId={} correlationId={} merchantId={}",
            entityName, MDC.get(StructuredLogFields.REQUEST_ID), MDC.get(StructuredLogFields.CORRELATION_ID), MDC.get(StructuredLogFields.MERCHANT_ID));

        ErrorResponse error = ErrorResponse.builder()
            .message("Concurrent update conflict — another request modified this record simultaneously. Please re-read and retry.")
            .errorCode("OPTIMISTIC_LOCK_CONFLICT")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.CONFLICT.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle typed concurrency conflict exceptions thrown by domain services and ConcurrencyGuard.
     * Carries structured fields: entityType, entityId, ConflictReason — all logged for traceability.
     */
    @ExceptionHandler(ConcurrencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencyConflict(ConcurrencyConflictException e) {
        log.warn("Concurrency conflict [{}]: entityType={} entityId={} requestId={} correlationId={} merchantId={}",
            e.getReason(), e.getEntityType(), e.getEntityId(),
            MDC.get(StructuredLogFields.REQUEST_ID), MDC.get(StructuredLogFields.CORRELATION_ID), MDC.get(StructuredLogFields.MERCHANT_ID));

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.CONFLICT.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle database constraint violations (duplicate key, FK violation, etc.) → 409 Conflict.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message("Request conflicts with existing data (e.g. duplicate entry or constraint violation)")
            .errorCode("DATA_CONFLICT")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.CONFLICT.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle rate limit violations (HTTP 429 Too Many Requests).
     * Sets Retry-After and X-RateLimit-Reset headers.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException e) {
        log.warn("Rate limit exceeded: policy={} subject={}", e.getPolicy(), e.getSubject());

        ErrorResponse error = ErrorResponse.builder()
            .message("Too many requests — rate limit exceeded. Please slow down and retry after the reset time.")
            .errorCode(e.errorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.TOO_MANY_REQUESTS.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After",       String.valueOf(e.resetEpochSeconds() - System.currentTimeMillis() / 1000))
            .header("X-RateLimit-Reset", String.valueOf(e.resetEpochSeconds()))
            .body(error);
    }

    /**
     * Platform domain exception catch-all.
     *
     * Handles any {@link BaseDomainException} subclass that does not have its own
     * dedicated handler above.  The exception carries its own {@code httpStatus},
     * {@code errorCode}, and structured {@code metadata}, so no switch/instanceof
     * branching is required here.
     *
     * <p>Invariant violations (HTTP 500) are logged at ERROR level; all other
     * platform exceptions (4xx) are logged at WARN level.
     */
    @ExceptionHandler(BaseDomainException.class)
    public ResponseEntity<ErrorResponse> handleBaseDomainException(BaseDomainException e) {
        if (e.getHttpStatus().is5xxServerError()) {
            log.error("Platform domain error [{}]: {} metadata={} requestId={}",
                e.getErrorCode(), e.getMessage(), e.getMetadata(), MDC.get(StructuredLogFields.REQUEST_ID));
        } else {
            log.warn("Platform domain error [{}]: {} metadata={} requestId={}",
                e.getErrorCode(), e.getMessage(), e.getMetadata(), MDC.get(StructuredLogFields.REQUEST_ID));
        }

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode(e.getErrorCode())
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getHttpStatus().value())
            .errorMetadata(e.getMetadata().isEmpty() ? null : e.getMetadata())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getHttpStatus()).body(error);
    }

    /**
     * Handle JPA entity-not-found errors — maps to 404.
     */
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFoundException(
            jakarta.persistence.EntityNotFoundException e) {
        log.warn("Entity not found: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode("NOT_FOUND")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.NOT_FOUND.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle Spring's ResponseStatusException — forwards its status code.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(
            org.springframework.web.server.ResponseStatusException e) {
        log.warn("ResponseStatusException: {} {}", e.getStatusCode(), e.getReason());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getReason() != null ? e.getReason() : e.getMessage())
            .errorCode("RESPONSE_STATUS_ERROR")
            .timestamp(LocalDateTime.now())
            .httpStatus(e.getStatusCode().value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.status(e.getStatusCode()).body(error);
    }

    /**
     * Handle IllegalArgumentException — maps to 400 Bad Request.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());

        ErrorResponse error = ErrorResponse.builder()
            .message(e.getMessage())
            .errorCode("BAD_REQUEST")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.BAD_REQUEST.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();

        return ResponseEntity.badRequest().body(error);
    }

    /**
     * Catch-all for unexpected errors.
     * Stack trace is logged server-side but NOT returned to the caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception e) {
        log.error("Unexpected error occurred", e);
        
        ErrorResponse error = ErrorResponse.builder()
            .message("An unexpected error occurred")
            .errorCode("INTERNAL_ERROR")
            .timestamp(LocalDateTime.now())
            .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .requestId(MDC.get(StructuredLogFields.REQUEST_ID))
            .correlationId(MDC.get(StructuredLogFields.CORRELATION_ID))
            .build();
            
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Consistent error response format for all handled exceptions.
     */
    @Data
    @Builder
    public static class ErrorResponse {
        private String message;
        private String errorCode;
        private LocalDateTime timestamp;
        private Integer httpStatus;
        private Map<String, String> validationErrors;
        /** Structured metadata from {@link BaseDomainException} subclasses (entity IDs, states, etc.). */
        private Map<String, Object> errorMetadata;
        /** Request ID from X-Request-Id header (or auto-generated by RequestIdFilter). */
        private String requestId;
        /** Correlation ID linking a business flow (X-Correlation-Id or falls back to requestId). */
        private String correlationId;
    }
}