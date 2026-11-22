package com.payments.gateway.gateway;

import com.payments.gateway.common.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * REST implementation of {@link GatewayClient}. Timeouts and 5xx are retryable. 4xx is a
 * definitive rejection.
 */
@Component
public class HttpGatewayClient implements GatewayClient {

    record ChargeRequest(String reference, long amountMinor, String currency) {
    }

    record ChargeResponse(String status, String gatewayReference) {
    }

    private final RestClient restClient;

    public HttpGatewayClient(@Value("${payments.gateway.base-url}") String baseUrl,
                             @Value("${payments.gateway.connect-timeout-ms}") int connectTimeoutMs,
                             @Value("${payments.gateway.read-timeout-ms}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public GatewayResponse charge(String reference, Money amount) {
        try {
            ChargeResponse body = restClient.post()
                    .uri("/v1/charges")
                    .header("Idempotency-Key", reference)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChargeRequest(reference, amount.amountMinor(), amount.currency()))
                    .retrieve()
                    .body(ChargeResponse.class);
            if (body == null) {
                throw new GatewayException("empty gateway response", true);
            }
            return "DECLINED".equals(body.status())
                    ? new GatewayResponse(GatewayResponse.Outcome.DECLINED, body.gatewayReference())
                    : new GatewayResponse(GatewayResponse.Outcome.ACCEPTED, body.gatewayReference());
        } catch (ResourceAccessException e) {
            throw new GatewayException("gateway unreachable or timed out", true, e);
        } catch (HttpServerErrorException e) {
            throw new GatewayException("gateway server error " + e.getStatusCode(), true, e);
        } catch (HttpClientErrorException e) {
            throw new GatewayException("gateway rejected charge " + e.getStatusCode(), false, e);
        }
    }
}
