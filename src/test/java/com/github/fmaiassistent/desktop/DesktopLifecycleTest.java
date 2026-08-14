package com.github.fmaiassistent.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopLifecycleTest {
    @Test
    void concurrentShutdownPathsCloseSpringAndExitOnlyOnce() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        DesktopWindow window = mock(DesktopWindow.class);
        AtomicInteger exits = new AtomicInteger();
        when(context.isActive()).thenReturn(true);
        DesktopLifecycle lifecycle = new DesktopLifecycle(
                context, mock(BrowserLauncher.class), window, ignored -> exits.incrementAndGet());

        lifecycle.exit();
        lifecycle.exit();
        lifecycle.shutdown();

        verify(window, times(1)).close();
        verify(context, times(1)).close();
        assertThat(exits).hasValue(1);
    }

    @Test
    void taskbarWindowAndBrowserReceiveTheRunningApplicationActions() {
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        DesktopWindow window = mock(DesktopWindow.class);
        BrowserLauncher browser = mock(BrowserLauncher.class);
        URI uri = URI.create("http://127.0.0.1:8080/");
        DesktopLifecycle lifecycle = new DesktopLifecycle(context, browser, window, ignored -> { });

        lifecycle.start(uri);

        verify(window).applicationReady(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(browser).openAtStartup(uri);
    }
}
