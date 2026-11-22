package com.payments.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
public class PaymentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentGatewayApplication.class, args);
    }

    /** UTC clock. Tests can replace it to make time-dependent behaviour deterministic. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
