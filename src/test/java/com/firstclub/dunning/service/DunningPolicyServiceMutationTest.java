package com.firstclub.dunning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.dunning.dto.DunningPolicyCreateRequestDTO;
import com.firstclub.dunning.dto.DunningPolicyResponseDTO;
import com.firstclub.dunning.entity.DunningPolicy;
import com.firstclub.dunning.entity.DunningTerminalStatus;
import com.firstclub.dunning.repository.DunningPolicyRepository;
import com.firstclub.dunning.service.impl.DunningPolicyServiceImpl;
import com.firstclub.membership.exception.MembershipException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Targeted mutation-killing tests for {@link DunningPolicyServiceImpl}.
 * <p>
 * Each test addresses a specific surviving or no-coverage mutant
 * identified by PIT mutation analysis.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DunningPolicyServiceImpl — Mutation Tests")
class DunningPolicyServiceMutationTest {

    @Mock  private DunningPolicyRepository policyRepository;
    @Spy   private ObjectMapper            objectMapper = new ObjectMapper();

    @InjectMocks
    private DunningPolicyServiceImpl service;

    private static final Long MERCHANT_ID = 1L;

    // ── validatePolicyPayload boundary mutants ────────────────────────────────

    @Nested
    @DisplayName("Validation boundary mutants")
    class ValidationBoundary {

        @Test
        @DisplayName("offset = 0 → rejected (kills boundary mutant on line 138)")
        void zeroOffset_rejected() {
            DunningPolicyCreateRequestDTO req = DunningPolicyCreateRequestDTO.builder()
                    .policyCode("TEST")
                    .retryOffsetsJson("[0]")
                    .maxAttempts(1)
                    .graceDays(3)
                    .statusAfterExhaustion("SUSPENDED")
                    .build();

            assertThatThrownBy(() -> service.createPolicy(MERCHANT_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("positive integer")
                    .hasMessageContaining("0");
        }

        @Test
        @DisplayName("maxAttempts = 0 → rejected (kills boundary mutant on line 146)")
        void zeroMaxAttempts_rejected() {
            DunningPolicyCreateRequestDTO req = DunningPolicyCreateRequestDTO.builder()
                    .policyCode("TEST")
                    .retryOffsetsJson("[60]")
                    .maxAttempts(0)
                    .graceDays(3)
                    .statusAfterExhaustion("SUSPENDED")
                    .build();

            assertThatThrownBy(() -> service.createPolicy(MERCHANT_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("maxAttempts must be positive");
        }

        @Test
        @DisplayName("maxAttempts = -1 → rejected (kills remove-conditional mutant on line 146)")
        void negativeMaxAttempts_rejected() {
            DunningPolicyCreateRequestDTO req = DunningPolicyCreateRequestDTO.builder()
                    .policyCode("TEST")
                    .retryOffsetsJson("[60]")
                    .maxAttempts(-1)
                    .graceDays(3)
                    .statusAfterExhaustion("SUSPENDED")
                    .build();

            assertThatThrownBy(() -> service.createPolicy(MERCHANT_ID, req))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("maxAttempts must be positive");
        }
    }

    // ── getPolicyById no-coverage mutants ─────────────────────────────────────

    @Nested
    @DisplayName("getPolicyById coverage")
    class GetPolicyById {

        @Test
        @DisplayName("found with matching merchant → returns DTO (kills null-return on line 90)")
        void found_returnsDto() {
            DunningPolicy policy = DunningPolicy.builder()
                    .id(42L).merchantId(MERCHANT_ID).policyCode("DEFAULT")
                    .retryOffsetsJson("[60, 360]").maxAttempts(2).graceDays(5)
                    .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();
            when(policyRepository.findById(42L)).thenReturn(Optional.of(policy));

            DunningPolicyResponseDTO dto = service.getPolicyById(MERCHANT_ID, 42L);

            assertThat(dto).isNotNull();
            assertThat(dto.getId()).isEqualTo(42L);
            assertThat(dto.getPolicyCode()).isEqualTo("DEFAULT");
        }

        @Test
        @DisplayName("found but wrong merchant → 404 (kills boolean-false on line 86)")
        void wrongMerchant_throws() {
            DunningPolicy policy = DunningPolicy.builder()
                    .id(42L).merchantId(999L).policyCode("DEFAULT")
                    .retryOffsetsJson("[60]").maxAttempts(1).graceDays(3)
                    .statusAfterExhaustion(DunningTerminalStatus.SUSPENDED).build();
            when(policyRepository.findById(42L)).thenReturn(Optional.of(policy));

            assertThatThrownBy(() -> service.getPolicyById(MERCHANT_ID, 42L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("not found → 404 (kills null-return on line 87)")
        void notFound_throws() {
            when(policyRepository.findById(42L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getPolicyById(MERCHANT_ID, 42L))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("not found");
        }
    }

    // ── parseOffsets no-coverage mutant ───────────────────────────────────────

    @Nested
    @DisplayName("parseOffsets coverage")
    class ParseOffsets {

        @Test
        @DisplayName("valid JSON → returns offsets list (kills empty-return on line 174)")
        void validJson_returnsList() {
            List<Integer> offsets = service.parseOffsets("[60, 360, 1440]");

            assertThat(offsets).containsExactly(60, 360, 1440);
        }

        @Test
        @DisplayName("invalid JSON → throws MembershipException")
        void invalidJson_throws() {
            assertThatThrownBy(() -> service.parseOffsets("not-json"))
                    .isInstanceOf(MembershipException.class)
                    .hasMessageContaining("Invalid retry_offsets_json");
        }
    }
}
