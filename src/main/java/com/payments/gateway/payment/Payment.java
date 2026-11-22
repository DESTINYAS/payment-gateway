package com.payments.gateway.payment;

import com.payments.gateway.common.Money;
import com.payments.gateway.error.Exceptions.InvalidTransition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "gateway_reference", length = 128)
    private String gatewayReference;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    private Payment(String reference, Money amount, Instant now) {
        this.reference = reference;
        this.amountMinor = amount.amountMinor();
        this.currency = amount.currency();
        this.status = PaymentStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Payment newPending(String reference, Money amount, Instant now) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("reference must not be blank");
        }
        return new Payment(reference, amount, now);
    }

    /** Applies a status change, enforcing the state machine. */
    public void transitionTo(PaymentStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidTransition(reference, status, next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    public void attachGatewayReference(String gatewayReference, Instant now) {
        this.gatewayReference = gatewayReference;
        this.updatedAt = now;
    }

    public Money amount() {
        return new Money(amountMinor, currency);
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }

    public long getVersion() {
        return version;
    }
}
