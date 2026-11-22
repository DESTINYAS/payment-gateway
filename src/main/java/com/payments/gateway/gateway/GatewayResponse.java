package com.payments.gateway.gateway;

/**
 * Synchronous gateway answer. A DECLINED outcome is final and the payment fails immediately.
 * An ACCEPTED outcome means the final result will arrive later by webhook.
 */
public record GatewayResponse(Outcome outcome, String gatewayReference) {

    public enum Outcome { ACCEPTED, DECLINED }
}
