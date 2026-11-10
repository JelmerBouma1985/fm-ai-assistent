package com.github.fmaiassistent.desktop;

import com.github.fmaiassistent.FmAiAssistentApplication;
import com.github.fmaiassistent.AvailablePortInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

public final class DesktopLauncher {
    private static final Logger log = LoggerFactory.getLogger(DesktopLauncher.class);

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        configureTestHome(args);
        if (Boolean.getBoolean("fmai.desktop.image")) {
            try {
                var image = com.github.fmaiassistent.update.InstalledApplicationInfo.detect();
                if (java.nio.file.Files.exists(image.root().resolve(".updater-data-review")))
                    throw new IllegalStateException("Updater recovery requires data review before starting. See .updater-data-review and updater transaction logs.");
            } catch (java.io.IOException ignored) { /* Unsupported installations remain manually usable. */ }
        }
        log.info("Starting FM AI Assistent desktop application");
        AtomicReference<Runnable> exitAction = new AtomicReference<>(() -> System.exit(0));
        DesktopWindow desktopWindow = DesktopWindow.show(() -> exitAction.get().run());
        ConfigurableApplicationContext context = null;
        DesktopLifecycle lifecycle = null;
        try {
            SpringApplication application = createApplication();

            context = application.run(args);
            URI applicationUri = new ApplicationUrlResolver().resolve(context);
            log.info("Spring Boot application is ready");
            log.info("Desktop application URL: {}", applicationUri);

            lifecycle = new DesktopLifecycle(
                    context, new BrowserLauncher(), desktopWindow, System::exit);
            exitAction.set(lifecycle::exit);
            DesktopLifecycle runningLifecycle = lifecycle;
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
                    .name("desktop-jvm-shutdown")
                    .unstarted(runningLifecycle::shutdown));
            lifecycle.start(applicationUri);
            context.getBean(com.github.fmaiassistent.update.UpdateCoordinator.class).desktopReady(transaction -> {
                if (runningLifecycle.shutdown()) {
                    try {
                        java.nio.file.Files.createFile(transaction.resolve("clean-shutdown"));
                        System.exit(0);
                    } catch (java.io.IOException failure) {
                        log.error("Could not acknowledge clean update shutdown", failure);
                    }
                }
            });
            if (java.util.Arrays.asList(args).contains("--app.updates.test-install=true")) {
                if (java.util.Arrays.stream(args).noneMatch(a -> a.startsWith("--app.updates.test-home=")))
                    throw new IllegalArgumentException("Automated updater test requires an isolated test home");
                var updates = context.getBean(com.github.fmaiassistent.update.UpdateCoordinator.class);
                updates.check(true);
                Thread.ofPlatform().daemon().name("updater-test-consent").start(() -> {
                    long deadline = System.nanoTime() + java.time.Duration.ofMinutes(2).toNanos();
                    while (System.nanoTime() < deadline) {
                        if (updates.status().phase() == com.github.fmaiassistent.update.UpdateStatus.Phase.AVAILABLE) { updates.install(); return; }
                        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                });
            }
        } catch (Throwable failure) {
            log.error("Unable to start FM AI Assistent desktop application", failure);
            if (lifecycle != null) {
                lifecycle.shutdown();
            } else if (context != null && context.isActive()) {
                try {
                    context.close();
                } catch (RuntimeException shutdownFailure) {
                    log.error("Spring application context reported an error while closing", shutdownFailure);
                }
            }
            desktopWindow.close();
            System.exit(1);
        }
    }

    /** Explicit opt-in, disposable profile isolation for packaged offline tests. */
    static void configureTestHome(String[] args) {
        var arguments = java.util.Arrays.asList(args);
        var testHome = arguments.stream().filter(a -> a.startsWith("--app.updates.test-home=")).findFirst();
        if (testHome.isEmpty()) return;
        if (!arguments.contains("--app.updates.source=local") || !arguments.contains("--app.updates.developer-mode=true")) throw new IllegalArgumentException("Test home requires explicit local developer mode");
        try {
            var directory = java.nio.file.Path.of(testHome.get().substring("--app.updates.test-home=".length())).toRealPath();
            if (!java.nio.file.Files.isRegularFile(directory.resolve(".fmai-updater-test-profile"))) throw new IllegalArgumentException("Test home must contain .fmai-updater-test-profile");
            if (directory.equals(java.nio.file.Path.of(System.getProperty("user.home")).toRealPath())) throw new IllegalArgumentException("Test home must be isolated");
            System.setProperty("user.home", directory.toString());
        } catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid updater test home", e); }
    }

    static SpringApplication createApplication() {
        SpringApplication application = new SpringApplication(FmAiAssistentApplication.class);
        application.addInitializers(new AvailablePortInitializer());
        application.setMainApplicationClass(FmAiAssistentApplication.class);
        application.setAdditionalProfiles("desktop");
        application.setHeadless(false);
        application.setRegisterShutdownHook(false);
        return application;
    }
}
