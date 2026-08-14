package com.github.fmaiassistent.desktop;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationUrlResolverTest {
    @Test
    void constructsUriFromActualServerSettings() {
        assertThat(ApplicationUrlResolver.buildUri("https", "localhost", 9443, "/fm"))
                .hasToString("https://localhost:9443/fm/");
    }

    @Test
    void convertsWildcardBindingToReachableLoopbackAddress() {
        assertThat(ApplicationUrlResolver.buildUri("http", "0.0.0.0", 8080, ""))
                .hasToString("http://127.0.0.1:8080/");
        assertThat(ApplicationUrlResolver.buildUri("http", "::", 8080, "/"))
                .hasToString("http://127.0.0.1:8080/");
    }

    @Test
    void formatsIpv6AddressesCorrectly() {
        assertThat(ApplicationUrlResolver.buildUri("http", "[::1]", 8080, "app"))
                .hasToString("http://[::1]:8080/app/");
    }
}
