package com.firstclub.payments.refund.service;

import com.firstclub.events.service.DomainEventLog;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.payments.capacity.PaymentCapacityInvariantService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.dto.RefundV2ResponseDTO;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.guard.RefundMutationGuard;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.refund.service.impl.RefundServiceV2Impl;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.platform.redis.RedisKeyFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mutation-killing tests for {@link RefundServiceV2Impl}.
 *
 * <p>Targets surviving and no-coverage mutants identified by PIT baseline (39%):
 * <ul>
 *   <li>createRefund — idempotency fingerprint logic (L77-L94)</li>
 *   <li>createRefund — Redis lock acquire/release (L100, L178-179)</li>
 *   <li>createRefund — syncMinorUnitFields call (L149)</li>
 *   <li>createRefund — refund status COMPLETED + completedAt (L153-154)</li>
 *   <li>listRefundsByPayment — validateMerchantOwnership (L212), toDto lambda (L216)</li>
 *   <li>computeRefundableAmount — return value (L228, L236)</li>
 *   <li>computeFingerprint — return value (L287)</li>
 *   <li>tryAcquireRedisLock / releaseRedisLock — conditionals (L302-318)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefundServiceV2Impl — Mutation-killing tests")
class RefundServiceV2MutationTest {

    @Mock private PaymentRepository              paymentRepository;
    @Mock private RefundV2Repository             refundV2Repository;
    @Mock private RefundAccountingService        refundAccountingService;
    @Mock private OutboxService                  outboxService;
    @Mock private DomainEventLog                 domainEventLog;
    @Mock private RedisKeyFactory                redisKeyFactory;
    @Mock private RefundMutationGuard            refundMutationGuard;
    @Mock private PaymentCapacityInvariantService invariantService;
    @Mock private ObjectProvider<StringRedisTemplate> redisProvider;

    @InjectMocks private RefundServiceV2Impl service;

    private static final Long MERCHANT_ID = 10L;
    private static final Long PAYMENT_ID  = 100L;

    // ── Builder helpers ───────────────────────────────────────────────────

    private Payment capturedPayment(BigDecimal captured, BigDecimal alreadyRefunded) {
        return Payment.builder()
                .id(PAYMENT_ID)
                .merchantId(MERCHANT_ID)
                .paymentIntentId(999L)
                .amount(captured)
                .capturedAmount(captured)
                .refundedAmount(alreadyRefunded)
                .disputedAmount(BigDecimal.ZERO)
                .netAmount(captured.subtract(alreadyRefunded))
                .currency("INR")
                .status(alreadyRefunded.compareTo(BigDecimal.ZERO) == 0
                        ? PaymentStatus.CAPTURED
                        : PaymentStatus.PARTIALLY_REFUNDED)
                .gatewayTxnId("txn-unit-test")
                .capturedAt(LocalDateTime.now())
                .build();
    }

    private RefundV2 savedRefund(Long id, BigDecimal amount) {
        return RefundV2.builder()
                .id(id)
                .merchantId(MERCHANT_ID)
                .paymentId(PAYMENT_ID)
                .amount(amount)
                .reasonCode("TEST_REASON")
                .status(RefundV2Status.COMPLETED)
                .completedAt(LocalDateTime.now())
                .build();
    }

    private RefundCreateRequestDTO request(BigDecimal amount) {
        return RefundCreateRequestDTO.builder()
                .amount(amount)
                .reasonCode("TEST_REASON")
                .build();
    }

    private RefundCreateRequestDTO requestWithFingerprint(BigDecimal amount, String fingerprint) {
        return RefundCreateRequestDTO.builder()
                .amount(amount)
                .reasonCode("TEST_REASON")
                .requestFingerprint(fingerprint)
                .build();
    }

    /**
     * Stubs refundV2Repository.save to return the argument with an id assigned.
     * First call returns with PENDING (initial persist), second returns as-is (completion save).
     */
    private void stubSaveRefund(Long refundId) {
        when(refundV2Repository.save(any(RefundV2.class)))
                .thenAnswer(inv -> {
                    RefundV2 r = inv.getArgument(0);
                    return RefundV2.builder()
                            .id(refundId)
                            .merchantId(r.getMerchantId())
                            .paymentId(r.getPaymentId())
                            .amount(r.getAmount())
                            .reasonCode(r.getReasonCode())
                            .status(r.getStatus())
                            .refundReference(r.getRefundReference())
                            .requestFingerprint(r.getRequestFingerprint())
                            .build();
                })
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // =====================================================================
    // createRefund — Idempotency Fingerprint
    // =====================================================================

    @Nested
    @DisplayName("createRefund — idempotency fingerprint")
    class IdempotencyFingerprint {

        @Test
        @DisplayName("auto-generated fingerprint is stored on refund (kills computeFingerprint EmptyObjectReturn L287)")
        void autoGeneratedFingerprint_isNonEmpty() {
            // Kills: computeFingerprint returning "" — the fingerprint must be a valid SHA-256 hex
            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(1L);

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    request(new BigDecimal("100.00")));

            assertThat(result.getRequestFingerprint()).isNotNull().isNotBlank();
            // SHA-256 hex is 64 chars
            assertThat(result.getRequestFingerprint()).hasSize(64);
        }

        @Test
        @DisplayName("caller-supplied fingerprint replay returns existing refund (kills L77-78 conditionals, L85-87, L94)")
        void callerSuppliedFingerprint_replay_returnsExisting() {
            // Kills: removed conditional L77 (null check), L78 (ternary), L85 (if callerSupplied),
            //        L87 (existing.isPresent()), L94 (null return)
            String fingerprint = "caller-dedup-key-12345";
            RefundV2 existingRefund = RefundV2.builder()
                    .id(42L)
                    .merchantId(MERCHANT_ID)
                    .paymentId(PAYMENT_ID)
                    .amount(new BigDecimal("200.00"))
                    .reasonCode("TEST_REASON")
                    .status(RefundV2Status.COMPLETED)
                    .requestFingerprint(fingerprint)
                    .completedAt(LocalDateTime.now())
                    .build();

            Payment payment = capturedPayment(new BigDecimal("1000.00"), new BigDecimal("200.00"));
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);

            when(refundV2Repository.findByRequestFingerprint(fingerprint))
                    .thenReturn(Optional.of(existingRefund));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    requestWithFingerprint(new BigDecimal("200.00"), fingerprint));

            // Must return the existing refund, not create a new one
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("200.00"));

            // Guard was NOT called — no DB lock acquired for replay
            verifyNoInteractions(refundMutationGuard);
            verifyNoInteractions(refundAccountingService);
        }

        @Test
        @DisplayName("caller-supplied fingerprint with no prior refund proceeds to create (kills L85 EQUAL_ELSE, L87 EQUAL_ELSE)")
        void callerSuppliedFingerprint_noPrior_proceedsToCreate() {
            // Kills: removed conditional L85 EQUAL_ELSE — if (callerSuppliedFingerprint) always false
            //        removed conditional L87 EQUAL_ELSE — if (existing.isPresent()) always false
            String fingerprint = "new-dedup-key-67890";

            when(refundV2Repository.findByRequestFingerprint(fingerprint))
                    .thenReturn(Optional.empty());

            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(7L);

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    requestWithFingerprint(new BigDecimal("100.00"), fingerprint));

            // New refund created with the caller-supplied fingerprint
            assertThat(result).isNotNull();
            assertThat(result.getRequestFingerprint()).isEqualTo(fingerprint);
            verify(refundMutationGuard).acquireAndCheck(eq(PAYMENT_ID), any());
        }

        @Test
        @DisplayName("no fingerprint provided uses auto-generated — each invocation gets unique fingerprint (kills L77 EQUAL_IF, L78 EQUAL_IF)")
        void noFingerprint_autoGenerates() {
            // Kills: L77/L78 conditionals — callerSuppliedFingerprint must be false when null
            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(8L);

            // No fingerprint — blank is same as null for this logic
            RefundCreateRequestDTO req = RefundCreateRequestDTO.builder()
                    .amount(new BigDecimal("100.00"))
                    .reasonCode("TEST_REASON")
                    .requestFingerprint("")   // blank → auto-generate
                    .build();

            service.createRefund(MERCHANT_ID, PAYMENT_ID, req);

            // Idempotency fast-path should NOT be checked for blank fingerprint
            verify(refundV2Repository, never()).findByRequestFingerprint(anyString());
        }

        @Test
        @DisplayName("fingerprint replay — parent payment not found throws PAYMENT_NOT_FOUND (kills lambda L91)")
        void fingerprintReplay_parentPaymentNotFound_throws() {
            // Kills: lambda L91 NullReturnVals — exception must be thrown, not null returned
            String fingerprint = "dedup-for-missing-payment";
            RefundV2 existingRefund = RefundV2.builder()
                    .id(55L).merchantId(MERCHANT_ID).paymentId(PAYMENT_ID)
                    .amount(new BigDecimal("100.00")).reasonCode("TEST_REASON")
                    .status(RefundV2Status.COMPLETED).requestFingerprint(fingerprint).build();

            when(refundV2Repository.findByRequestFingerprint(fingerprint))
                    .thenReturn(Optional.of(existingRefund));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

            MembershipException ex = catchThrowableOfType(
                    () -> service.createRefund(MERCHANT_ID, PAYMENT_ID,
                            requestWithFingerprint(new BigDecimal("100.00"), fingerprint)),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_NOT_FOUND");
        }
    }

    // =====================================================================
    // createRefund — Refund Status Transitions & Aggregate Mutations
    // =====================================================================

    @Nested
    @DisplayName("createRefund — status transitions and aggregate mutations")
    class StatusTransitionsAndMutations {

        @Test
        @DisplayName("refund transitions to COMPLETED with non-null completedAt (kills setStatus L153, setCompletedAt L154)")
        void refundTransitions_toCompleted_withCompletedAt() {
            // Kills: VoidMethodCall removing setStatus(COMPLETED) and setCompletedAt(now)
            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);

            // Capture the second save (completion save) of the refund
            ArgumentCaptor<RefundV2> refundCaptor = ArgumentCaptor.forClass(RefundV2.class);
            when(refundV2Repository.save(any(RefundV2.class)))
                    .thenAnswer(inv -> {
                        RefundV2 r = inv.getArgument(0);
                        return RefundV2.builder()
                                .id(10L).merchantId(r.getMerchantId()).paymentId(r.getPaymentId())
                                .amount(r.getAmount()).reasonCode(r.getReasonCode())
                                .status(r.getStatus()).requestFingerprint(r.getRequestFingerprint())
                                .completedAt(r.getCompletedAt())
                                .build();
                    })
                    .thenAnswer(inv -> inv.getArgument(0));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    request(new BigDecimal("100.00")));

            // Verify refund was saved with COMPLETED status in the second save
            verify(refundV2Repository, times(2)).save(refundCaptor.capture());
            RefundV2 completionSave = refundCaptor.getAllValues().get(1);
            assertThat(completionSave.getStatus()).isEqualTo(RefundV2Status.COMPLETED);
            assertThat(completionSave.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("syncMinorUnitFields is called before payment save (kills VoidMethodCall L149)")
        void syncMinorUnitFields_calledBeforePaymentSave() {
            // Kills: removed call to syncMinorUnitFields
            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(11L);

            service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("100.00")));

            verify(invariantService).syncMinorUnitFields(payment);
        }
    }

    // =====================================================================
    // createRefund — Redis Lock Acquire/Release
    // =====================================================================

    @Nested
    @DisplayName("createRefund — Redis lock behavior")
    class RedisLockBehavior {

        @Mock private StringRedisTemplate redisTemplate;
        @Mock private ValueOperations<String, String> valueOps;

        @Test
        @DisplayName("Redis lock acquired and released on success path (kills L178 conditional, L179 releaseRedisLock, L302 conditionals)")
        void redisLock_acquiredAndReleased_onSuccess() {
            // Kills: L178 EQUAL_ELSE/EQUAL_IF (if redisLockAcquired), L179 VoidMethodCall (releaseRedisLock)
            //        L302 conditionals (tmpl == null), L318 conditionals (tmpl != null)
            when(redisKeyFactory.refundLockKey(anyString())).thenReturn("lock:refund:100");
            when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);

            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(20L);

            service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("100.00")));

            // Lock was acquired
            verify(valueOps).setIfAbsent(eq("lock:refund:100"), eq("1"), any());
            // Lock was released
            verify(redisTemplate).delete("lock:refund:100");
        }

        @Test
        @DisplayName("Redis lock NOT released when acquisition failed (kills L178 EQUAL_ELSE — false path)")
        void redisLock_notReleased_whenNotAcquired() {
            // Kills: L178 — when redisLockAcquired is false, releaseRedisLock must NOT be called
            when(redisKeyFactory.refundLockKey(anyString())).thenReturn("lock:refund:100");
            when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);

            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(21L);

            service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("100.00")));

            // Lock was NOT released because it was not acquired
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("Redis lock released even when createRefund throws (kills L179 — finally block)")
        void redisLock_released_onFailure() {
            // Kills: releaseRedisLock in finally block
            when(redisKeyFactory.refundLockKey(anyString())).thenReturn("lock:refund:100");
            when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);

            // Cause createRefund to fail after Redis lock acquired
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any()))
                    .thenThrow(new MembershipException("Payment not found", "PAYMENT_NOT_FOUND",
                            org.springframework.http.HttpStatus.NOT_FOUND));

            assertThatThrownBy(() -> service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    request(new BigDecimal("100.00"))))
                    .isInstanceOf(MembershipException.class);

            // Lock must still be released in the finally block
            verify(redisTemplate).delete("lock:refund:100");
        }

        @Test
        @DisplayName("Redis unavailable on acquire → redisLockAcquired=true → release attempted (kills L302 BooleanFalseReturn)")
        void redisUnavailable_lockStillReleasedBecauseTrueReturned() {
            // Kills: BooleanFalseReturn on L302 — null template must return true (fall-through),
            //        which means the finally block WILL call releaseRedisLock.
            //        If mutant changes return to false, releaseRedisLock is NOT called → verifiable.
            when(redisKeyFactory.refundLockKey(anyString())).thenReturn("lock:refund:100");
            // First call (tryAcquireRedisLock) → null → return true
            // Second call (releaseRedisLock) → returns a template → delete observable
            when(redisProvider.getIfAvailable()).thenReturn(null).thenReturn(redisTemplate);

            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(22L);

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    request(new BigDecimal("100.00")));

            assertThat(result).isNotNull();
            // Because tryAcquireRedisLock returned true (fall-through), releaseRedisLock IS called
            // and on the second getIfAvailable() call, we get the template → delete is invoked
            verify(redisTemplate).delete("lock:refund:100");
        }

        @Test
        @DisplayName("Redis setIfAbsent returns null — treated as not acquired, no NPE (kills L304 conditionals)")
        void redisSetIfAbsent_returnsNull_noNpe() {
            // Kills: L304 RemoveConditional — Boolean.TRUE.equals(null) is false
            when(redisKeyFactory.refundLockKey(anyString())).thenReturn("lock:refund:100");
            when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(null);

            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(23L);

            // Should succeed — null is treated as not acquired, falls through to DB lock
            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    request(new BigDecimal("100.00")));

            assertThat(result).isNotNull();
            // Lock was not acquired → should not try to release
            verify(redisTemplate, never()).delete(anyString());
        }

        @Test
        @DisplayName("Redis throws on acquire → redisLockAcquired=true → release attempted (kills L311 BooleanFalseReturn)")
        void redisException_lockStillReleasedBecauseTrueReturned() {
            // Kills: L311 BooleanFalseReturn — exception path must return true,
            //        which means the finally block WILL call releaseRedisLock.
            //        If mutant changes return to false, releaseRedisLock is NOT called → verifiable.
            when(redisKeyFactory.refundLockKey(anyString())).thenReturn("lock:refund:100");
            // First call (tryAcquireRedisLock) → throws → returns true (exception catch)
            // Second call (releaseRedisLock) → returns template → delete observable
            when(redisProvider.getIfAvailable())
                    .thenThrow(new RuntimeException("Redis down"))
                    .thenReturn(redisTemplate);

            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(24L);

            RefundV2ResponseDTO result = service.createRefund(MERCHANT_ID, PAYMENT_ID,
                    request(new BigDecimal("100.00")));

            assertThat(result).isNotNull();
            // Because tryAcquireRedisLock returned true (exception fall-through),
            // releaseRedisLock IS called and delete is invoked
            verify(redisTemplate).delete("lock:refund:100");
        }

        @Test
        @DisplayName("Redis TRUE return — lock acquired returns true (kills L307 BooleanFalseReturn, BooleanTrueReturn)")
        void redisTrue_lockAcquired() {
            // Kills: L307 BooleanFalseReturn/BooleanTrueReturn — must return actual Boolean.TRUE
            when(redisKeyFactory.refundLockKey(anyString())).thenReturn("lock:refund:100");
            when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
            when(redisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);

            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(refundMutationGuard.acquireAndCheck(eq(PAYMENT_ID), any())).thenReturn(payment);
            stubSaveRefund(25L);

            service.createRefund(MERCHANT_ID, PAYMENT_ID, request(new BigDecimal("100.00")));

            // Lock was acquired (true) → must be released in finally
            verify(redisTemplate).delete("lock:refund:100");
        }
    }

    // =====================================================================
    // listRefundsByPayment — merchant ownership & DTO mapping
    // =====================================================================

    @Nested
    @DisplayName("listRefundsByPayment — merchant scoping and mapping")
    class ListRefundsMutation {

        @Test
        @DisplayName("listRefundsByPayment with wrong merchant throws PAYMENT_MERCHANT_MISMATCH (kills validateMerchantOwnership L212)")
        void listRefunds_wrongMerchant_throwsMismatch() {
            // Kills: VoidMethodCall removing validateMerchantOwnership at L212
            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

            MembershipException ex = catchThrowableOfType(
                    () -> service.listRefundsByPayment(99L, PAYMENT_ID),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_MERCHANT_MISMATCH");
        }

        @Test
        @DisplayName("listRefundsByPayment returns DTOs with correct fields (kills toDto lambda L216 NullReturnVals)")
        void listRefunds_returnsDtosWithCorrectFields() {
            // Kills: NullReturnVals — lambda returning null instead of DTO
            Payment payment = capturedPayment(new BigDecimal("1000.00"), new BigDecimal("300.00"));
            payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);

            RefundV2 r1 = savedRefund(1L, new BigDecimal("200.00"));
            r1.setReasonCode("REASON_A");
            RefundV2 r2 = savedRefund(2L, new BigDecimal("100.00"));
            r2.setReasonCode("REASON_B");

            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));
            when(refundV2Repository.findByPaymentIdAndMerchantId(PAYMENT_ID, MERCHANT_ID))
                    .thenReturn(List.of(r1, r2));

            List<RefundV2ResponseDTO> list = service.listRefundsByPayment(MERCHANT_ID, PAYMENT_ID);

            assertThat(list).hasSize(2);
            // Assert each DTO has non-null fields — kills null return mutant
            assertThat(list.get(0).getId()).isEqualTo(1L);
            assertThat(list.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
            assertThat(list.get(0).getReasonCode()).isEqualTo("REASON_A");
            assertThat(list.get(1).getId()).isEqualTo(2L);
            assertThat(list.get(1).getAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("listRefundsByPayment — payment not found throws PAYMENT_NOT_FOUND (kills lambda L207)")
        void listRefunds_paymentNotFound_throws() {
            // Kills: NullReturnVals on lambda L207 — exception must be thrown
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

            MembershipException ex = catchThrowableOfType(
                    () -> service.listRefundsByPayment(MERCHANT_ID, PAYMENT_ID),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_NOT_FOUND");
        }
    }

    // =====================================================================
    // getRefund — parent payment not found
    // =====================================================================

    @Nested
    @DisplayName("getRefund — edge cases")
    class GetRefundEdgeCases {

        @Test
        @DisplayName("getRefund — parent payment not found throws PAYMENT_NOT_FOUND (kills lambda L194)")
        void getRefund_parentPaymentNotFound_throws() {
            // Kills: NullReturnVals on lambda L194
            RefundV2 refund = savedRefund(1L, new BigDecimal("100.00"));
            when(refundV2Repository.findByMerchantIdAndId(MERCHANT_ID, 1L))
                    .thenReturn(Optional.of(refund));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

            MembershipException ex = catchThrowableOfType(
                    () -> service.getRefund(MERCHANT_ID, 1L),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_NOT_FOUND");
        }
    }

    // =====================================================================
    // computeRefundableAmount
    // =====================================================================

    @Nested
    @DisplayName("computeRefundableAmount — arithmetic and edge cases")
    class ComputeRefundableAmount {

        @Test
        @DisplayName("fully refundable payment returns captured amount (kills NullReturnVals L228, L236)")
        void fullyRefundable_returnsCapturedAmount() {
            // Kills: NullReturnVals L228 (public method return) and L236 (private helper return)
            Payment payment = capturedPayment(new BigDecimal("1000.00"), BigDecimal.ZERO);
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

            BigDecimal result = service.computeRefundableAmount(PAYMENT_ID);

            assertThat(result).isNotNull();
            assertThat(result).isEqualByComparingTo(new BigDecimal("1000.00"));
        }

        @Test
        @DisplayName("partially refunded payment returns remaining amount")
        void partiallyRefunded_returnsRemainder() {
            Payment payment = capturedPayment(new BigDecimal("1000.00"), new BigDecimal("300.00"));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

            BigDecimal result = service.computeRefundableAmount(PAYMENT_ID);

            assertThat(result).isEqualByComparingTo(new BigDecimal("700.00"));
        }

        @Test
        @DisplayName("fully refunded payment returns zero")
        void fullyRefunded_returnsZero() {
            Payment payment = capturedPayment(new BigDecimal("1000.00"), new BigDecimal("1000.00"));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

            BigDecimal result = service.computeRefundableAmount(PAYMENT_ID);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("payment not found throws PAYMENT_NOT_FOUND (kills lambda L224)")
        void paymentNotFound_throws() {
            // Kills: NullReturnVals on lambda L224
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

            MembershipException ex = catchThrowableOfType(
                    () -> service.computeRefundableAmount(PAYMENT_ID),
                    MembershipException.class);

            assertThat(ex.getErrorCode()).isEqualTo("PAYMENT_NOT_FOUND");
        }

        @Test
        @DisplayName("payment with disputed amount subtracts it from refundable")
        void disputedAmount_subtractedFromRefundable() {
            Payment payment = capturedPayment(new BigDecimal("1000.00"), new BigDecimal("200.00"));
            payment.setDisputedAmount(new BigDecimal("300.00"));
            when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(payment));

            BigDecimal result = service.computeRefundableAmount(PAYMENT_ID);

            // 1000 - 200 - 300 = 500
            assertThat(result).isEqualByComparingTo(new BigDecimal("500.00"));
        }
    }
}
