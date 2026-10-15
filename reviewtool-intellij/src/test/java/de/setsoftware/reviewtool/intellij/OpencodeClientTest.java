package de.setsoftware.reviewtool.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.setsoftware.reviewtool.intellij.OpencodeClient.Answer;
import de.setsoftware.reviewtool.intellij.OpencodeClient.ModelInfo;
import de.setsoftware.reviewtool.intellij.OpencodeClient.OpencodeException;

/**
 * Tests for {@link OpencodeClient} against a stub of the opencode server API.
 */
public class OpencodeClientTest {

    private HttpServer server;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonObject> bodies = Collections.synchronizedList(new ArrayList<>());
    private volatile String messageResponse;
    private volatile int messageStatus = 200;
    private volatile CountDownLatch messageBlocker;
    private OpencodeClient client;

    @Before
    public void setUp() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
        this.client = new OpencodeClient(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/"),
                "secret");
    }

    @After
    public void tearDown() {
        this.server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        this.requests.add(exchange.getRequestMethod() + " " + path);
        final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            this.bodies.add(Json.parse(body).asObject());
        }
        if (!("Basic " + java.util.Base64.getEncoder().encodeToString("opencode:secret".getBytes(StandardCharsets.UTF_8)))
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "Unauthorized");
            return;
        }
        if (path.equals("/global/health")) {
            respond(exchange, 200, "{\"healthy\":true,\"version\":\"1.19.0\"}");
        } else if (path.equals("/config/providers")) {
            respond(exchange, 200, PROVIDERS);
        } else if (path.equals("/config")) {
            respond(exchange, 200, "{\"model\":\"acme/review-model\",\"share\":\"disabled\"}");
        } else if (path.equals("/session")) {
            respond(exchange, 200, "{\"id\":\"ses_123\",\"title\":\"x\"}");
        } else if (path.endsWith("/message")) {
            final CountDownLatch blocker = this.messageBlocker;
            if (blocker != null) {
                try {
                    blocker.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(exchange, this.messageStatus, this.messageResponse);
        } else if (path.endsWith("/abort")) {
            respond(exchange, 200, "true");
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private static final String PROVIDERS = "{\"providers\":["
            + "{\"id\":\"acme\",\"name\":\"Acme AI\",\"source\":\"config\",\"models\":{"
            + "\"review-model\":{\"id\":\"review-model\",\"name\":\"Review Model\"},"
            + "\"big\":{\"id\":\"big\",\"name\":\"Big Model\"}}},"
            + "{\"id\":\"openrouter\",\"name\":\"OpenRouter\",\"source\":\"env\",\"models\":{"
            + "\"anthropic/claude-x\":{\"id\":\"anthropic/claude-x\",\"name\":\"Claude X\"}}}],"
            + "\"default\":{\"acme\":\"review-model\"}}";

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String structured(String error, String structured) {
        final JsonObject info = new JsonObject().add("id", "msg_1").add("providerID", "acme").add("modelID", "review-model");
        if (error != null) {
            info.add("error", Json.parse(error));
        }
        if (structured != null) {
            info.add("structured", Json.parse(structured));
        }
        final JsonArray parts = new JsonArray().add(new JsonObject().add("type", "step-start"))
                .add(new JsonObject().add("type", "tool").add("tool", "StructuredOutput"));
        return new JsonObject().add("info", info).add("parts", parts).toString();
    }

    private static String answer(String error, String... texts) {
        final JsonObject info = new JsonObject().add("id", "msg_1").add("providerID", "acme").add("modelID", "review-model");
        if (error != null) {
            info.add("error", Json.parse(error));
        }
        final JsonArray parts = new JsonArray().add(new JsonObject().add("type", "step-start"));
        for (final String text : texts) {
            parts.add(new JsonObject().add("type", "text").add("text", text));
        }
        parts.add(new JsonObject().add("type", "text").add("text", "internal").add("synthetic", true));
        return new JsonObject().add("info", info).add("parts", parts).toString();
    }

    @Test
    public void testHealthAndModel() {
        assertEquals("1.19.0", this.client.health());
        assertEquals("acme/review-model", this.client.configuredModel());
    }

    @Test
    public void testSessionIsReadOnly() {
        assertEquals("ses_123", this.client.createSession("CoRT review: DEMO-1"));
        final JsonObject body = this.bodies.get(0);
        assertEquals("CoRT review: DEMO-1", body.getString("title", null));
        final JsonArray rules = body.get("permission").asArray();
        // first everything is denied, then reading is allowed (the last matching rule wins)
        assertEquals("*", rules.get(0).asObject().getString("permission", null));
        assertEquals("deny", rules.get(0).asObject().getString("action", null));
        final List<String> allowed = new ArrayList<>();
        for (int i = 1; i < rules.size(); i++) {
            assertEquals("allow", rules.get(i).asObject().getString("action", null));
            allowed.add(rules.get(i).asObject().getString("permission", null));
        }
        assertEquals(List.of("read", "grep", "glob", "list", "StructuredOutput"), allowed);
    }

    @Test
    public void testPrompt() {
        this.messageResponse = answer(null, "## Summary", "More text");
        final Answer result = this.client.prompt("ses_123", null, "system text", "the question", () -> false);
        assertEquals("## Summary\n\nMore text", result.getText());
        assertEquals("acme/review-model", result.getModel());
        final JsonObject body = this.bodies.get(0);
        assertEquals("system text", body.getString("system", null));
        assertEquals("the question", body.get("parts").asArray().get(0).asObject().getString("text", null));
        assertTrue(this.requests.contains("POST /session/ses_123/message"));
    }

    @Test
    public void testStructuredPrompt() {
        this.messageResponse = structured(null, "{\"stops\":[{\"stop\":1,\"risk\":\"high\",\"reason\":\"r\"}]}");
        final JsonObject schema = ReviewAiPrompts.risksSchema();
        final Answer result = this.client.promptStructured("ses_123", null, "system text", "Rate the risk", schema, () -> false);
        assertEquals("high", result.getStructured().get("stops").asArray().get(0).asObject().getString("risk", null));
        assertEquals("acme/review-model", result.getModel());
        final JsonObject format = this.bodies.get(0).get("format").asObject();
        assertEquals("json_schema", format.getString("type", null));
        assertEquals(schema, format.get("schema"));
        assertEquals("Rate the risk", this.bodies.get(0).get("parts").asArray().get(0).asObject().getString("text", null));
        // a normal prompt does not ask for a format
        this.messageResponse = answer(null, "text");
        this.client.prompt("ses_123", null, "s", "q", () -> false);
        assertNull(this.bodies.get(1).get("format"));
    }

    @Test
    public void testNoStructuredAnswer() {
        this.messageResponse = structured("{\"name\":\"StructuredOutputError\",\"data\":{\"message\":"
                + "\"Model did not produce structured output\",\"retries\":2}}", null);
        try {
            this.client.promptStructured("ses_123", null, "s", "q", new JsonObject(), () -> false);
            fail();
        } catch (final OpencodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("did not answer in the expected format"));
            assertTrue(e.getMessage(), e.getMessage().contains("Model did not produce structured output"));
        }
        this.messageResponse = structured(null, null);
        try {
            this.client.promptStructured("ses_123", null, "s", "q", new JsonObject(), () -> false);
            fail();
        } catch (final OpencodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("did not answer in the expected format"));
        }
    }

    @Test
    public void testMissingCredentialsOfTheProvider() {
        this.messageResponse = answer(
                "{\"name\":\"ProviderAuthError\",\"data\":{\"providerID\":\"acme\",\"message\":\"no key\"}}");
        try {
            this.client.prompt("ses_123", null, "s", "q", () -> false);
            fail();
        } catch (final OpencodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no valid credentials"));
            assertTrue(e.isConfigurationProblem());
        }
    }

    @Test
    public void testOtherModelErrorAndEmptyAnswer() {
        this.messageResponse = answer("{\"name\":\"APIError\",\"data\":{\"message\":\"overloaded\",\"isRetryable\":true}}");
        try {
            this.client.prompt("ses_123", null, "s", "q", () -> false);
            fail();
        } catch (final OpencodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("overloaded"));
            assertFalse(e.isConfigurationProblem());
        }
        this.messageResponse = answer(null);
        try {
            this.client.prompt("ses_123", null, "s", "q", () -> false);
            fail();
        } catch (final OpencodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("did not answer"));
        }
    }

    @Test
    public void testWrongPassword() {
        final OpencodeClient wrong = new OpencodeClient(this.client.getBaseUrl(), "wrong");
        try {
            wrong.health();
            fail();
        } catch (final OpencodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rejected the password"));
            assertTrue(e.isConfigurationProblem());
        }
    }

    @Test
    public void testCancelAbortsTheAnswer() {
        this.messageBlocker = new CountDownLatch(1);
        this.messageResponse = answer(null, "late");
        final long start = System.currentTimeMillis();
        try {
            this.client.prompt("ses_123", null, "s", "q", () -> System.currentTimeMillis() - start > 300);
            fail();
        } catch (final CancellationException e) {
            assertTrue(this.requests.contains("POST /session/ses_123/abort"));
        } finally {
            this.messageBlocker.countDown();
        }
    }

    @Test
    public void testServerNotReachable() {
        this.server.stop(0);
        try {
            this.client.health();
            fail();
        } catch (final OpencodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not reachable"));
        }
    }

    @Test
    public void testNoModelConfigured() throws IOException {
        this.server.removeContext("/");
        this.server.createContext("/", (exchange) -> respond(exchange, 200, "{}"));
        assertNull(this.client.configuredModel());
    }

    @Test
    public void testAvailableModels() {
        final List<ModelInfo> models = this.client.availableModels();
        final List<String> ids = new ArrayList<>();
        for (final ModelInfo model : models) {
            ids.add(model.getId());
        }
        // sorted by provider name and model name
        assertEquals(List.of("acme/big", "acme/review-model", "openrouter/anthropic/claude-x"), ids);
        assertEquals("Acme AI: Big Model  (acme/big)", models.get(0).toString());
        assertTrue(this.requests.contains("GET /config/providers"));
    }

    @Test
    public void testPromptWithChosenModel() {
        this.messageResponse = answer(null, "text");
        this.client.prompt("ses_123", "openrouter/anthropic/claude-x", "s", "q", () -> false);
        final JsonObject model = this.bodies.get(0).get("model").asObject();
        // the provider id ends at the first "/", the model id can contain more
        assertEquals("openrouter", model.getString("providerID", null));
        assertEquals("anthropic/claude-x", model.getString("modelID", null));

        this.messageResponse = structured(null, "{\"stops\":[]}");
        this.client.promptStructured("ses_123", " acme/big ", "s", "q", new JsonObject(), () -> false);
        assertEquals("big", this.bodies.get(1).get("model").asObject().getString("modelID", null));

        // the default of the opencode configuration: no model in the request
        this.messageResponse = answer(null, "text");
        this.client.prompt("ses_123", "", "s", "q", () -> false);
        assertNull(this.bodies.get(2).get("model"));
    }

    @Test
    public void testCheckModel() {
        this.client.checkModel(null);
        this.client.checkModel("");
        assertFalse(this.requests.contains("GET /config/providers"));
        this.client.checkModel("openrouter/anthropic/claude-x");
        try {
            this.client.checkModel("acme/gone");
            fail();
        } catch (final OpencodeException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("\"acme/gone\" chosen in the CoRT settings is not"
                    + " available"));
            assertTrue(e.isConfigurationProblem());
        }
    }

    @Test
    public void testInvalidModelReference() {
        for (final String invalid : new String[] {"no-provider", "/model", "provider/"}) {
            try {
                OpencodeClient.modelReference(invalid);
                fail(invalid);
            } catch (final OpencodeException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("provider/model"));
                assertTrue(e.isConfigurationProblem());
            }
        }
    }

}
