package com.payments.gateway.gateway;

import com.payments.gateway.common.Money;

/**
 * Outbound port to the upstream payment gateway. The merchant reference is sent as the gateway's
 * idempotency key, so a charge retried after an ambiguous timeout cannot be taken twice.
 */
public interface GatewayClient {

    /**
     * @throws GatewayException retryable for timeouts, network errors and 5xx (the charge may or
     *                          may not have happened); non-retryable for 4xx rejections
     */
    GatewayResponse charge(String reference, Money amount);
}
