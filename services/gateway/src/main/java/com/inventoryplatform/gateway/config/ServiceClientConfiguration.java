package com.inventoryplatform.gateway.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.inventoryplatform.common.client.HttpServiceClient;
import com.inventoryplatform.common.client.ServiceClient;
import com.inventoryplatform.common.client.ServiceRoutes;

/**
 * Supplies the HTTP transport when nothing else has.
 *
 * <p>In desktop mode the launcher's parent context already provides an in-process
 * {@link ServiceClient}, and {@link ConditionalOnMissingBean} makes this back off. Running the
 * gateway standalone — cloud mode — there is no parent, so this fills in.
 *
 * <p>Without it the gateway simply cannot start outside the launcher, which would quietly make
 * "one codebase, two deployment shapes" false.
 */
@Configuration
public class ServiceClientConfiguration {

    /**
     * Every outbound call is bounded (BUILD_PROMPT.md §9). These are deliberately short: a
     * shopkeeper waiting on a counter sale would rather see an error than a spinner.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @ConfigurationProperties("platform.services")
    ServiceRoutesProperties serviceRoutesProperties() {
        return new ServiceRoutesProperties();
    }

    @Bean
    @ConditionalOnMissingBean(ServiceClient.class)
    ServiceClient httpServiceClient(
            ObjectProvider<RestClient.Builder> restClientBuilder, ServiceRoutesProperties properties) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        // Boot's builder carries the observability instrumentation that propagates trace
        // context on outgoing calls, so prefer it; fall back only if it is absent.
        RestClient restClient =
                restClientBuilder
                        .getIfAvailable(RestClient::builder)
                        .requestFactory(requestFactory)
                        .build();

        return new HttpServiceClient(restClient, new ServiceRoutes(properties.getBaseUrls()));
    }

    /** Base URL per service, e.g. {@code platform.services.base-urls.stock-service=http://…}. */
    public static class ServiceRoutesProperties {

        private Map<String, String> baseUrls = Map.of();

        public Map<String, String> getBaseUrls() {
            return baseUrls;
        }

        public void setBaseUrls(Map<String, String> baseUrls) {
            this.baseUrls = baseUrls;
        }
    }
}
