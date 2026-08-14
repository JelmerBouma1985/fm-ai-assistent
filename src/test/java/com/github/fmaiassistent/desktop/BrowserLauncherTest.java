package com.github.fmaiassistent.desktop;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BrowserLauncherTest {
    @Test
    void startupOpenIsIdempotentButManualOpenCanRetry() {
        AtomicInteger opens = new AtomicInteger();
        BrowserLauncher browser = new BrowserLauncher(ignored -> opens.incrementAndGet());
        URI uri = URI.create("http://127.0.0.1:8080/");

        browser.openAtStartup(uri);
        browser.openAtStartup(uri);
        browser.open(uri);

        assertThat(opens).hasValue(2);
    }

    @Test
    void browserFailureDoesNotEscape() {
        BrowserLauncher browser = new BrowserLauncher(ignored -> {
            throw new UnsupportedOperationException("no desktop");
        });

        assertThatCode(() -> browser.open(URI.create("http://127.0.0.1:8080/")))
                .doesNotThrowAnyException();
    }
}
