package com.bank.epricing.temporal.activity;

import com.bank.epricing.dto.PricingRequestDto;
import com.bank.epricing.dto.PricingResponseDto;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal Activity Interface for ePricing operations.
 * Activities represent atomic, retryable tasks executed by workers.
 */
@ActivityInterface
public interface PricingActivities {

    /**
     * Executes business calculation and database persistence.
     */
    @ActivityMethod
    PricingResponseDto calculateAndPersistPricing(PricingRequestDto requestDto, String requestIp);

    /**
     * Records customer or underwriter approval for the locked rate quote.
     */
    @ActivityMethod
    void recordQuoteApproval(String quoteId, String customerId);

    /**
     * Records rejection of the rate quote with reason.
     */
    @ActivityMethod
    void recordQuoteRejection(String quoteId, String customerId, String reason);

    /**
     * Marks rate quote as expired after the validity window elapses.
     */
    @ActivityMethod
    void recordQuoteExpiration(String quoteId, String customerId);
}

