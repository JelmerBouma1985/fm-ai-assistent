package com.github.fmaiassistent.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Handles a narrow client compatibility case before the MCP SDK deserializes tool arguments. */
@Component
public class McpToolArgumentsFilter extends OncePerRequestFilter {
    private final ObjectMapper json;

    public McpToolArgumentsFilter(ObjectMapper json) { this.json = json; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/mcp".equals(request.getServletPath())
                || request.getContentType() == null
                || !request.getContentType().startsWith("application/json");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        JsonNode parsed;
        try {
            parsed = json.readTree(body);
        } catch (RuntimeException exception) {
            chain.doFilter(new BodyRequest(request, body), response);
            return;
        }
        if (!(parsed instanceof ObjectNode root)
                || !"tools/call".equals(root.path("method").asString())
                || !(root.path("params") instanceof ObjectNode params)
                || !(params.path("arguments") instanceof ArrayNode arguments)) {
            chain.doFilter(new BodyRequest(request, body), response);
            return;
        }
        if (arguments.isEmpty()) {
            params.set("arguments", json.createObjectNode());
        } else if (arguments.size() == 1 && arguments.get(0).isObject()) {
            params.set("arguments", arguments.get(0));
        } else {
            ObjectNode error = json.createObjectNode().put("jsonrpc", "2.0");
            if (root.has("id")) error.set("id", root.path("id"));
            else error.putNull("id");
            error.putObject("error").put("code", -32602)
                    .put("message", "tools/call arguments must be an object");
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(json.writeValueAsString(error));
            return;
        }
        chain.doFilter(new BodyRequest(request,
                json.writeValueAsBytes(root)), response);
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    try {
                        if (!isFinished()) listener.onDataAvailable();
                        if (isFinished()) listener.onAllDataRead();
                    } catch (IOException exception) {
                        listener.onError(exception);
                    }
                }
            };
        }
    }
}
