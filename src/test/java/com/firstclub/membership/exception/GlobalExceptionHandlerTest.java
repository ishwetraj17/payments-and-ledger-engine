package com.firstclub.membership.exception;

import com.firstclub.platform.logging.StructuredLogFields;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler} — focused on observability:
 * request-ID and correlation-ID propagation into error responses.
 */
@DisplayName("GlobalExceptionHandler — observability")
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MDC.clear();
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    // ── requestId / correlationId propagation ─────────────────────────────────

    @Nested
    @DisplayName("Tracing ID propagation")
    class TracingIdPropagation {

        @Test
        @DisplayName("requestId from MDC appears in error response")
        void requestIdFromMdcAppearsInResponse() {
            MDC.put(StructuredLogFields.REQUEST_ID, "req-abc-123");

            ResponseEntity<GlobalExceptionHandler.ErrorResponse> resp =
                    handler.handleGenericException(new RuntimeException("boom"));

            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getRequestId()).isEqualTo("req-abc-123");
        }

        @Test
        @DisplayName("correlationId from MDC appears in error response")
        void correlationIdFromMdcAppearsInResponse() {
            MDC.put(StructuredLogFields.REQUEST_ID, "req-xyz-456");
            MDC.put(StructuredLogFields.CORRELATION_ID, "corr-xyz-789");

            ResponseEntity<GlobalExceptionHandler.ErrorResponse> resp =
                    handler.handleGenericException(new RuntimeException("boom"));

            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getCorrelationId()).isEqualTo("corr-xyz-789");
        }

        @Test
        @DisplayName("requestId is null when MDC has no requestId")
        void requestIdIsNullWhenMdcEmpty() {
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> resp =
                    handler.handleGenericException(new RuntimeException("boom"));

            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getRequestId()).isNull();
        }

        @Test
        @DisplayName("correlationId is null when MDC has no correlationId")
        void correlationIdIsNullWhenMdcEmpty() {
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> resp =
                    handler.handleGenericException(new RuntimeException("boom"));

            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getCorrelationId()).isNull();
        }

        @Test
        @DisplayName("requestId and correlationId are independent fields in response")
        void requestIdAndCorrelationIdAreIndependent() {
            MDC.put(StructuredLogFields.REQUEST_ID, "req-111");
            MDC.put(StructuredLogFields.CORRELATION_ID, "corr-222");

            ResponseEntity<GlobalExceptionHandler.ErrorResponse> resp =
                    handler.handleIllegalArgumentException(new IllegalArgumentException("bad input"));

            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().getRequestId()).isEqualTo("req-111");
            assertThat(resp.getBody().getCorrelationId()).isEqualTo("corr-222");
        }

        @Test
        @DisplayName("correlationId propagates in domain exception handler")
        void correlationIdInDomainHandler() {
            MDC.put(StructuredLogFields.REQUEST_ID, "req-domain");
            MDC.put(StructuredLogFields.CORRELATION_ID, "corr-domain");

            ResponseEntity<GlobalExceptionHandler.ErrorResponse> resp =
                    handler.handleBadCredentials(
                            new org.springframework.security.authentication.BadCredentialsException("bad"));

            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(resp.getBody().getRequestId()).isEqualTo("req-domain");
            assertThat(resp.getBody().getCorrelationId()).isEqualTo("corr-domain");
        }
    }
}
