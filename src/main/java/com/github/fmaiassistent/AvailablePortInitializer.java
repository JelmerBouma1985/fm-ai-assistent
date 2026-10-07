package com.github.fmaiassistent;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;

public final class AvailablePortInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!(context instanceof WebServerApplicationContext)) {
            return;
        }
        var environment = context.getEnvironment();
        int startingPort = environment.getProperty("server.port", Integer.class, 8080);
        if (startingPort == 0) {
            return; // Preserve Spring Boot's random-port setting.
        }

        String configuredAddress = environment.getProperty("server.address");
        try {
            InetAddress address = configuredAddress == null || configuredAddress.isBlank()
                    ? null : InetAddress.getByName(configuredAddress);
            int port = firstAvailablePort(startingPort, address);
            environment.getPropertySources().addFirst(
                    new MapPropertySource("availableServerPort", Map.of("server.port", port)));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not find an available server port starting at " + startingPort,
                    exception);
        }
    }

    static int firstAvailablePort(int startingPort, InetAddress address) throws IOException {
        for (int port = startingPort; port <= 65535; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(address, port));
                return port;
            } catch (BindException occupied) {
                // Try the next port.
            }
        }
        throw new IOException("No available server port at or above " + startingPort);
    }
}
