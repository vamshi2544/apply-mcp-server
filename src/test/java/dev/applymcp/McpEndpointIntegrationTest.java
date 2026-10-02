package dev.applymcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * End-to-end test over the real MCP protocol: starts the server on a random port and talks to it
 * with the official MCP Java client, exactly as Copilot or any other MCP host would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    int port;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();
        client = McpClient.sync(transport).build();
        client.initialize();
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    @Test
    void publishesThreeToolsWithSafetyAnnotations() {
        var tools = client.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "prequal_get_offers", "prequal_accept_offer", "prequal_get_application_status");

        McpSchema.Tool accept = tools.stream().filter(t -> t.name().equals("prequal_accept_offer")).findFirst().orElseThrow();
        assertThat(accept.annotations().destructiveHint()).isTrue();
        assertThat(accept.description()).contains("SAME idempotencyKey");

        McpSchema.Tool status = tools.stream().filter(t -> t.name().equals("prequal_get_application_status")).findFirst().orElseThrow();
        assertThat(status.annotations().readOnlyHint()).isTrue();
    }

    @Test
    void offersAcceptStatusOverTheProtocol() throws Exception {
        JsonNode offers = call("prequal_get_offers", Map.of(
                "firstName", "Jane", "lastName", "Doe", "dateOfBirth", "1990-04-12", "ssnLast4", "6789",
                "addressLine1", "100 Main St", "city", "Dayton", "state", "OH", "zipCode", "45402",
                "annualIncome", 85000, "consentSoftInquiry", true));
        assertThat(offers.get("decision").asText()).isEqualTo("QUALIFIED");

        JsonNode accepted = call("prequal_accept_offer", Map.of(
                "prequalId", offers.get("prequalId").asText(),
                "offerId", offers.get("offers").get(0).get("offerId").asText(),
                "idempotencyKey", UUID.randomUUID().toString(),
                "applicantConfirmed", true));
        assertThat(accepted.get("outcome").asText()).isEqualTo("ACCEPTED");

        JsonNode status = call("prequal_get_application_status",
                Map.of("applicationId", accepted.get("applicationId").asText()));
        assertThat(status.get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void ruleViolationsComeBackAsToolErrors() {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("prequal_accept_offer", Map.of(
                "prequalId", "PQ-1", "offerId", "OF-1", "idempotencyKey", UUID.randomUUID().toString(),
                "applicantConfirmed", false)));
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("not confirmed");
    }

    private JsonNode call(String tool, Map<String, Object> args) throws Exception {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, args));
        assertThat(result.isError()).as(text(result)).isFalse();
        return json.readTree(text(result));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}
