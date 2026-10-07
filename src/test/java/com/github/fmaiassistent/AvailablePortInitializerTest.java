package com.github.fmaiassistent;

import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class AvailablePortInitializerTest {
    @Test
    void skipsOccupiedPortsInOrder() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        for (int attempt = 0; attempt < 20; attempt++) {
            try (ServerSocket first = new ServerSocket(0, 0, loopback);
                 ServerSocket second = new ServerSocket();
                 ServerSocket third = new ServerSocket()) {
                int startingPort = first.getLocalPort();
                if (startingPort >= 65534) {
                    continue;
                }
                try {
                    second.bind(new InetSocketAddress(loopback, startingPort + 1));
                    third.bind(new InetSocketAddress(loopback, startingPort + 2));
                } catch (IOException occupied) {
                    continue;
                }
                third.close();

                AnnotationConfigServletWebServerApplicationContext context =
                        new AnnotationConfigServletWebServerApplicationContext();
                context.setEnvironment(new MockEnvironment()
                        .withProperty("server.port", Integer.toString(startingPort))
                        .withProperty("server.address", loopback.getHostAddress()));

                new AvailablePortInitializer().initialize(context);

                assertThat(context.getEnvironment().getProperty("server.port", Integer.class))
                        .isEqualTo(startingPort + 2);
                return;
            }
        }
        throw new AssertionError("Could not reserve adjacent ports for the test");
    }

    @Test
    void preservesRandomPortSetting() {
        AnnotationConfigServletWebServerApplicationContext context =
                new AnnotationConfigServletWebServerApplicationContext();
        context.setEnvironment(new MockEnvironment().withProperty("server.port", "0"));

        new AvailablePortInitializer().initialize(context);

        assertThat(context.getEnvironment().getProperty("server.port", Integer.class)).isZero();
    }

    @Test
    void leavesNonWebModeAlone() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(new MockEnvironment().withProperty("server.port", "8080"));

        new AvailablePortInitializer().initialize(context);

        assertThat(context.getEnvironment().getPropertySources().contains("availableServerPort"))
                .isFalse();
    }
}
