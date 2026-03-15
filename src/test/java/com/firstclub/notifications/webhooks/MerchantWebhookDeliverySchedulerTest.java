package com.firstclub.notifications.webhooks;

import com.firstclub.notifications.webhooks.service.MerchantWebhookDeliveryService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MerchantWebhookDeliveryScheduler} multi-instance safety.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MerchantWebhookDeliveryScheduler — Guard conditions")
class MerchantWebhookDeliverySchedulerTest {

    @Mock private MerchantWebhookDeliveryService deliveryService;
    @Mock private SchedulerLockService schedulerLockService;
    @Mock private PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @InjectMocks private MerchantWebhookDeliveryScheduler scheduler;

    @Nested
    @DisplayName("Guard conditions")
    class GuardConditions {

        @Test
        @DisplayName("processDeliveries_notPrimary_skipsExecution")
        void processDeliveries_notPrimary_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(false);

            scheduler.processDeliveries();

            verify(schedulerLockService, never()).tryAcquireForBatch(any());
            verify(deliveryService, never()).retryDueDeliveries();
        }

        @Test
        @DisplayName("processDeliveries_lockNotAcquired_skipsExecution")
        void processDeliveries_lockNotAcquired_skips() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(false);

            scheduler.processDeliveries();

            verify(deliveryService, never()).retryDueDeliveries();
        }

        @Test
        @DisplayName("processDeliveries_primaryAndLockAcquired_invokesService")
        void processDeliveries_happyPath_invokesService() {
            when(primaryOnlySchedulerGuard.canRunScheduler(any())).thenReturn(true);
            when(schedulerLockService.tryAcquireForBatch(any())).thenReturn(true);

            scheduler.processDeliveries();

            verify(deliveryService).retryDueDeliveries();
        }
    }
}
