package com.firstclub.notifications.webhooks;

import com.firstclub.notifications.webhooks.service.MerchantWebhookDeliveryService;
import com.firstclub.platform.scheduler.PrimaryOnlySchedulerGuard;
import com.firstclub.platform.scheduler.lock.SchedulerLockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls for and dispatches due outbound webhook deliveries every 60 seconds.
 *
 * <p>Runs independently of the dunning schedulers (initial delay = 135 s)
 * to avoid thundering-herd effects at startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MerchantWebhookDeliveryScheduler {

    private static final String SCHEDULER_NAME = "merchant-webhook-delivery";

    private final MerchantWebhookDeliveryService deliveryService;
    private final SchedulerLockService schedulerLockService;
    private final PrimaryOnlySchedulerGuard primaryOnlySchedulerGuard;

    @Scheduled(fixedRate = 60_000, initialDelay = 135_000)
    @Transactional
    public void processDeliveries() {
        if (!primaryOnlySchedulerGuard.canRunScheduler(SCHEDULER_NAME)) {
            return;
        }
        if (!schedulerLockService.tryAcquireForBatch(SCHEDULER_NAME)) {
            log.debug("[{}] advisory lock not acquired — another node is running this batch", SCHEDULER_NAME);
            return;
        }
        log.debug("Polling for due merchant webhook deliveries");
        try {
            deliveryService.retryDueDeliveries();
        } catch (Exception e) {
            // Never let the scheduler die
            log.error("Error in MerchantWebhookDeliveryScheduler: {}", e.getMessage(), e);
        }
    }
}
