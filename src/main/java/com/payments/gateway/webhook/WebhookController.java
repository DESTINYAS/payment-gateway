package com.payments.gateway.webhook;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private final WebhookProcessor processor;

    public WebhookController(WebhookProcessor processor) {
        this.processor = processor;
    }

    /**
     * Takes the raw body so the signature covers the exact bytes received. A 5xx tells the
     * gateway to redeliver. A 2xx means the event is handled (including duplicates).
     */
    @PostMapping(value = "/gateway", consumes = "application/json")
    public ResponseEntity<WebhookResult> receive(
            @RequestHeader(value = WebhookSignatureVerifier.HEADER, required = false) String signature,
            @RequestBody String rawBody) {
        return ResponseEntity.ok(processor.process(rawBody, signature));
    }
}
