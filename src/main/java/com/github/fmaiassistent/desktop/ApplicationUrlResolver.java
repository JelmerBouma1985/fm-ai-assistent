package com.github.fmaiassistent.desktop;

import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URISyntaxException;

final class ApplicationUrlResolver {
    URI resolve(ConfigurableApplicationContext context) {
        if (!(context instanceof WebServerApplicationContext webContext)) {
            throw new IllegalStateException("Desktop mode requires an embedded web server");
        }

        Environment environment = context.getEnvironment();
        String scheme = environment.getProperty("server.ssl.enabled", Boolean.class, false)
                ? "https"
                : "http";
        String host = browserHost(environment.getProperty("server.address"));
        int port = webContext.getWebServer().getPort();
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        return buildUri(scheme, host, port, contextPath);
    }

    static URI buildUri(String scheme, String host, int port, String contextPath) {
        String path = normalizePath(contextPath);
        try {
            return new URI(scheme, null, browserHost(host), port, path, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Could not construct the desktop application URL", exception);
        }
    }

    private static String browserHost(String configuredHost) {
        if (configuredHost == null || configuredHost.isBlank()
                || "0.0.0.0".equals(configuredHost)
                || "::".equals(configuredHost)
                || "[::]".equals(configuredHost)) {
            return "127.0.0.1";
        }
        if (configuredHost.startsWith("[") && configuredHost.endsWith("]")) {
            return configuredHost.substring(1, configuredHost.length() - 1);
        }
        return configuredHost;
    }

    private static String normalizePath(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return "/";
        }
        String path = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        return path.endsWith("/") ? path : path + "/";
    }
}
