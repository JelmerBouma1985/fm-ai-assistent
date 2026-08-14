package com.github.fmaiassistent.desktop;

import com.github.fmaiassistent.FmAiAssistentApplication;
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

    static SpringApplication createApplication() {
        SpringApplication application = new SpringApplication(FmAiAssistentApplication.class);
        application.setMainApplicationClass(FmAiAssistentApplication.class);
        application.setAdditionalProfiles("desktop");
        application.setHeadless(false);
        application.setRegisterShutdownHook(false);
        return application;
    }
}
