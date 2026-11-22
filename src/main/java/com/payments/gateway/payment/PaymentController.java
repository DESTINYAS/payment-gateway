package com.payments.gateway.payment;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** Safe to retry with the same {@code Idempotency-Key}: the first response is replayed. */
    @PostMapping
    public ResponseEntity<PaymentResult> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreatePaymentRequest request) {
        return ResponseEntity.ok(payments.createPayment(idempotencyKey, request));
    }

    @GetMapping("/{reference}")
    public PaymentResult get(@PathVariable String reference) {
        return payments.getPayment(reference);
    }
}
