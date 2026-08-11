package com.github.fmaiassistent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.codex.enabled=false",
                "spring.datasource.url=jdbc:h2:mem:mcp-protocol-test;DB_CLOSE_DELAY=-1"
        })
class McpProtocolCompatibilityTest {
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private ObjectMapper mapper;

    @Value("${local.server.port}")
    private int port;

    @Test
    void supportsAntigravityAndCodexProtocolVersions() throws Exception {
        for (String protocol : new String[] {"2025-11-25", "2025-06-18"}) {
            JsonNode initialize = mapper.createObjectNode()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "initialize")
                    .set("params", mapper.createObjectNode()
                            .put("protocolVersion", protocol)
                            .set("capabilities", mapper.createObjectNode())
                            .set("clientInfo", mapper.createObjectNode()
                                    .put("name", "compatibility-test")
                                    .put("version", "1.0")));

            HttpResponse<String> initialized = post(mapper.writeValueAsString(initialize), null, null);

            assertEquals(200, initialized.statusCode());
            assertEquals(protocol, mapper.readTree(initialized.body())
                    .path("result").path("protocolVersion").asText());
            String sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
            post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", sessionId, protocol);

            HttpResponse<String> tools = post(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                    sessionId,
                    protocol);
            String data = tools.body().lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()))
                    .findFirst()
                    .orElse(tools.body());
            JsonNode listedTools = mapper.readTree(data).path("result").path("tools");
            assertEquals(6, listedTools.size());
            assertTrue(listedTools.valueStream()
                    .anyMatch(tool -> "fm26_find_players".equals(tool.path("name").asText())));
        }
    }

    private HttpResponse<String> post(String body, String sessionId, String protocol) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        if (protocol != null) {
            request.header("MCP-Protocol-Version", protocol);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
