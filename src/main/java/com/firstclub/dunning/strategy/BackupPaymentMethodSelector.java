package com.firstclub.dunning.strategy;

import com.firstclub.dunning.entity.SubscriptionPaymentPreference;
import com.firstclub.dunning.repository.SubscriptionPaymentPreferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Selects the backup payment method for a subscription, if one is configured.
 *
 * <p>Delegates to {@link SubscriptionPaymentPreferenceRepository} to load the
 * subscription's payment preference row, then returns the
 * {@code backupPaymentMethodId} when present.  Returns an empty Optional when:
 * <ul>
 *   <li>no preference row exists for the subscription, or</li>
 *   <li>a preference row exists but {@code backup_payment_method_id} is {@code null}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class BackupPaymentMethodSelector {

    private final SubscriptionPaymentPreferenceRepository preferenceRepository;

    /**
     * Return the backup payment method ID for the given subscription, if configured.
     *
     * @param subscriptionId the subscription to look up
     * @return Optional containing the backup PM id, or empty if none is configured
     */
    public Optional<Long> findBackup(Long subscriptionId) {
        return preferenceRepository.findBySubscriptionId(subscriptionId)
                .map(SubscriptionPaymentPreference::getBackupPaymentMethodId);
    }
}
