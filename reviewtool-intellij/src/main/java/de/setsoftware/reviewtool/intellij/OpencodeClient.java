package de.setsoftware.reviewtool.intellij;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

/**
 * A small client for the HTTP API of a local opencode server ({@code opencode serve}). opencode
 * runs the AI models configured by the user (or the company); CoRT only sends prompts to it, by default
 * to the model of the opencode configuration, or to one of the models opencode offers that the user
 * chose in the settings. The sessions are created read-only: the model may read and search the files
 * of the project, but all other tools (editing, shell, web, ...) are denied.
 */
final class OpencodeClient {

    /**
     * A problem in the communication with opencode, with a message for the user.
     */
    static final class OpencodeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final boolean configurationProblem;

        OpencodeException(String message, boolean configurationProblem, Throwable cause) {
            super(message, cause);
            this.configurationProblem = configurationProblem;
        }

        /**
         * True iff the problem can probably be solved in the settings (e.g. opencode not found).
         */
        boolean isConfigurationProblem() {
            return this.configurationProblem;
        }
    }

    /**
     * The answer of the model.
     */
    static final class Answer {
        private final String text;
        private final String model;
        private final JsonObject structured;

        Answer(String text, String model) {
            this(text, model, null);
        }

        Answer(String text, String model, JsonObject structured) {
            this.text = text;
            this.model = model;
            this.structured = structured;
        }

        String getText() {
            return this.text;
        }

        /**
         * The answer in the requested JSON format (only for {@link OpencodeClient#promptStructured}).
         */
        JsonObject getStructured() {
            return this.structured;
        }

        /**
         * The model that answered ("provider/model"), or null if unknown.
         */
        String getModel() {
            return this.model;
        }
    }

    /**
     * A model opencode offers (a model of a provider with credentials or a free one).
     */
    static final class ModelInfo {
        private final String providerId;
        private final String providerName;
        private final String modelId;
        private final String modelName;

        ModelInfo(String providerId, String providerName, String modelId, String modelName) {
            this.providerId = providerId;
            this.providerName = providerName;
            this.modelId = modelId;
            this.modelName = modelName;
        }

        /**
         * The id of the model as used in the opencode configuration ("provider/model").
         */
        String getId() {
            return this.providerId + "/" + this.modelId;
        }

        String getProviderName() {
            return this.providerName;
        }

        String getModelName() {
            return this.modelName;
        }

        @Override
        public String toString() {
            return this.providerName + ": " + this.modelName + "  (" + this.getId() + ")";
        }
    }

    /**
     * The tools the model may use in the sessions: only reading and searching the project's files, and
     * the tool opencode provides for answers in a given JSON format.
     */
    private static final String[] ALLOWED_TOOLS = {"read", "grep", "glob", "list", "StructuredOutput"};

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);
    /** Answers can take a while (the model may read files), but not forever. */
    private static final Duration PROMPT_TIMEOUT = Duration.ofMinutes(15);

    private final URI baseUrl;
    private final String authorization;
    private final HttpClient http;

    /**
     * @param password The password of the server (null if it does not need one). The user name is
     *      "opencode" (the default of opencode).
     */
    OpencodeClient(URI baseUrl, String password) {
        this.baseUrl = baseUrl;
        this.authorization = password == null ? null : "Basic " + Base64.getEncoder().encodeToString(
                ("opencode:" + password).getBytes(StandardCharsets.UTF_8));
        // the server runs locally: never use the IDE's proxy (it would not reach 127.0.0.1)
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    URI getBaseUrl() {
        return this.baseUrl;
    }

    /**
     * Returns the version of the server if it is up and healthy.
     */
    String health() {
        return this.health(SHORT_TIMEOUT);
    }

    /**
     * Returns the version of the server if it is up and healthy (with the given timeout, e.g. a short
     * one while waiting for the server to start).
     */
    String health(Duration timeout) {
        final JsonObject result = this.send("GET", "/global/health", null, timeout).asObject();
        return result.getString("version", "?");
    }

    /**
     * Returns the model opencode uses by default ("provider/model"), or null if none is configured.
     */
    String configuredModel() {
        final JsonValue config = this.send("GET", "/config", null, SHORT_TIMEOUT);
        final JsonValue model = config.isObject() ? config.asObject().get("model") : null;
        return model != null && model.isString() ? model.asString() : null;
    }

    /**
     * Returns the models opencode offers, sorted by provider and name.
     */
    List<ModelInfo> availableModels() {
        final JsonValue result = this.send("GET", "/config/providers", null, SHORT_TIMEOUT);
        final List<ModelInfo> ret = new ArrayList<>();
        final JsonValue providers = result.isObject() ? result.asObject().get("providers") : null;
        if (providers == null || !providers.isArray()) {
            return ret;
        }
        for (final JsonValue provider : providers.asArray()) {
            if (!provider.isObject()) {
                continue;
            }
            final JsonObject p = provider.asObject();
            final String providerId = p.getString("id", null);
            final JsonValue models = p.get("models");
            if (providerId == null || models == null || !models.isObject()) {
                continue;
            }
            final String providerName = p.getString("name", providerId);
            for (final JsonObject.Member model : models.asObject()) {
                final String name = model.getValue().isObject()
                        ? model.getValue().asObject().getString("name", model.getName()) : model.getName();
                ret.add(new ModelInfo(providerId, providerName, model.getName(), name));
            }
        }
        ret.sort(Comparator.comparing(ModelInfo::getProviderName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ModelInfo::getModelName, String.CASE_INSENSITIVE_ORDER));
        return ret;
    }

    /**
     * Checks that opencode offers the given model ("provider/model"; null or empty for the default
     * model of the opencode configuration). opencode only answers an unknown model with an
     * "unexpected server error", so it is checked before the prompt is sent.
     */
    void checkModel(String model) {
        if (isDefaultModel(model)) {
            return;
        }
        for (final ModelInfo available : this.availableModels()) {
            if (available.getId().equals(model.trim())) {
                return;
            }
        }
        throw new OpencodeException("The model \"" + model.trim() + "\" chosen in the CoRT settings is not"
                + " available in opencode (unknown model, or opencode has no credentials for its provider)."
                + " Choose another model in the settings.", true, null);
    }

    private static boolean isDefaultModel(String model) {
        return model == null || model.trim().isEmpty();
    }

    /**
     * Converts "provider/model" to the model reference of the opencode API (the provider id contains no
     * "/", but the model id can, e.g. "openrouter/anthropic/claude-x").
     */
    static JsonObject modelReference(String model) {
        final String trimmed = model.trim();
        final int slash = trimmed.indexOf('/');
        if (slash <= 0 || slash == trimmed.length() - 1) {
            throw new OpencodeException("The model \"" + trimmed + "\" in the CoRT settings is not of the form"
                    + " \"provider/model\".", true, null);
        }
        return new JsonObject().add("providerID", trimmed.substring(0, slash))
                .add("modelID", trimmed.substring(slash + 1));
    }

    /**
     * Creates a new read-only session and returns its id.
     */
    String createSession(String title) {
        final JsonArray rules = new JsonArray();
        // the last matching rule wins: first deny everything, then allow reading
        rules.add(rule("*", "deny"));
        for (final String tool : ALLOWED_TOOLS) {
            rules.add(rule(tool, "allow"));
        }
        final JsonObject body = new JsonObject().add("title", title).add("permission", rules);
        return this.send("POST", "/session", body, SHORT_TIMEOUT).asObject().getString("id", null);
    }

    private static JsonObject rule(String permission, String action) {
        return new JsonObject().add("permission", permission).add("pattern", "*").add("action", action);
    }

    /**
     * Sends the text to the session and waits for the answer of the model.
     *
     * @param model The model ("provider/model"), or null or empty for the default model of the opencode
     *      configuration.
     * @param system Additional system instructions (appended to the ones of opencode).
     * @param canceled Is polled while waiting; if it returns true, the answer is aborted.
     * @return The answer.
     */
    Answer prompt(String sessionId, String model, String system, String text, BooleanSupplier canceled) {
        return answer(sessionId, this.sendPrompt(sessionId, model, system, text, null, canceled));
    }

    /**
     * Sends the text to the session and waits for an answer in the JSON format described by the schema
     * (opencode lets the model answer with a tool call whose parameters are this schema).
     *
     * @return The answer, with the object matching the schema in {@link Answer#getStructured()}.
     */
    Answer promptStructured(String sessionId, String model, String system, String text, JsonObject schema,
            BooleanSupplier canceled) {
        final JsonObject format = new JsonObject().add("type", "json_schema").add("schema", schema)
                .add("retryCount", 2);
        return structuredAnswer(sessionId, this.sendPrompt(sessionId, model, system, text, format, canceled));
    }

    private JsonValue sendPrompt(String sessionId, String model, String system, String text, JsonObject format,
            BooleanSupplier canceled) {
        final JsonObject body = new JsonObject()
                .add("system", system)
                .add("parts", new JsonArray().add(new JsonObject().add("type", "text").add("text", text)));
        if (!isDefaultModel(model)) {
            body.add("model", modelReference(model));
        }
        if (format != null) {
            body.add("format", format);
        }
        final CompletableFuture<HttpResponse<String>> future = this.http.sendAsync(
                this.request("POST", "/session/" + sessionId + "/message", body, PROMPT_TIMEOUT),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        final HttpResponse<String> response;
        try {
            response = waitFor(future, canceled);
        } catch (final CancellationException e) {
            this.abortQuietly(sessionId);
            throw e;
        }
        return this.parse("POST", "/session/.../message", response);
    }

    private static HttpResponse<String> waitFor(CompletableFuture<HttpResponse<String>> future,
            BooleanSupplier canceled) {
        while (true) {
            if (canceled.getAsBoolean()) {
                future.cancel(true);
                throw new CancellationException();
            }
            try {
                return future.get(200, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException e) {
                continue;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new CancellationException();
            } catch (final ExecutionException e) {
                throw connectionProblem(e.getCause());
            }
        }
    }

    /**
     * Extracts the text of the answer (the text parts of the message) or throws an exception with the
     * error opencode reported (e.g. missing credentials of the model provider).
     */
    static Answer answer(String sessionId, JsonValue answer) {
        final JsonObject info = answer.asObject().get("info").asObject();
        final JsonValue error = info.get("error");
        if (error != null && error.isObject()) {
            throw modelError(error.asObject());
        }
        final StringBuilder text = new StringBuilder();
        for (final JsonValue part : answer.asObject().get("parts").asArray()) {
            final JsonObject p = part.asObject();
            if ("text".equals(p.getString("type", "")) && !p.getBoolean("synthetic", false)
                    && !p.getBoolean("ignored", false)) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(p.getString("text", ""));
            }
        }
        if (text.toString().trim().isEmpty()) {
            throw new OpencodeException("The model did not answer (session " + sessionId + ").", false, null);
        }
        return new Answer(text.toString().trim(), model(info));
    }

    private static String model(JsonObject info) {
        final String provider = info.getString("providerID", null);
        final String model = info.getString("modelID", null);
        return provider == null || model == null ? model : provider + "/" + model;
    }

    /**
     * Extracts the JSON answer of a structured prompt or throws an exception with the error opencode
     * reported.
     */
    static Answer structuredAnswer(String sessionId, JsonValue answer) {
        final JsonObject info = answer.asObject().get("info").asObject();
        final JsonValue error = info.get("error");
        if (error != null && error.isObject()) {
            throw modelError(error.asObject());
        }
        final JsonValue structured = info.get("structured");
        if (structured == null || !structured.isObject()) {
            throw new OpencodeException("The model did not answer in the expected format (session " + sessionId
                    + ").", false, null);
        }
        return new Answer("", model(info), structured.asObject());
    }

    private static OpencodeException modelError(JsonObject error) {
        final String name = error.getString("name", "Error");
        final JsonValue data = error.get("data");
        final String message = data != null && data.isObject() ? data.asObject().getString("message", "") : "";
        switch (name) {
        case "ProviderAuthError":
            return new OpencodeException("opencode has no valid credentials for the model provider ("
                    + message + "). Check the provider configuration of opencode, e.g. with \"opencode auth login\".",
                    true, null);
        case "MessageAbortedError":
            return new OpencodeException("The answer has been aborted.", false, null);
        case "StructuredOutputError":
            return new OpencodeException("The model did not answer in the expected format (" + message
                    + "). Some models cannot answer in a given format; try another model (CoRT settings or"
                    + " opencode configuration).", false, null);
        case "ContextOverflowError":
            return new OpencodeException("The changes are too big for the model's context window (" + message
                    + "). Try a model with a bigger context window (CoRT settings or opencode"
                    + " configuration).", false, null);
        default:
            return new OpencodeException("The model reported an error (" + name + "): " + message, false, null);
        }
    }

    /**
     * Aborts the running answer of the session (errors are ignored).
     */
    void abortQuietly(String sessionId) {
        try {
            this.send("POST", "/session/" + sessionId + "/abort", null, SHORT_TIMEOUT);
        } catch (final RuntimeException e) {
            // the answer may have been finished in the meantime
        }
    }

    private JsonValue send(String method, String path, JsonObject body, Duration timeout) {
        final HttpResponse<String> response;
        try {
            response = this.http.send(this.request(method, path, body, timeout),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw connectionProblem(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
        return this.parse(method, path, response);
    }

    private HttpRequest request(String method, String path, JsonObject body, Duration timeout) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(this.baseUrl.resolve(path))
                .timeout(timeout)
                .header("Accept", "application/json");
        if (this.authorization != null) {
            builder.header("Authorization", this.authorization);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private JsonValue parse(String method, String path, HttpResponse<String> response) {
        if (response.statusCode() == 401) {
            throw new OpencodeException("The opencode server at " + this.baseUrl + " rejected the password.",
                    true, null);
        }
        if (response.statusCode() >= 400) {
            final String body = response.body() == null ? "" : response.body().trim();
            throw new OpencodeException("opencode answered " + method + " " + path + " with HTTP "
                    + response.statusCode() + (body.isEmpty() ? "" : ": " + abbreviate(body)), false, null);
        }
        try {
            return Json.parse(response.body());
        } catch (final RuntimeException e) {
            throw new OpencodeException("opencode sent an unexpected answer to " + method + " " + path + ": "
                    + abbreviate(String.valueOf(response.body())), false, e);
        }
    }

    private static OpencodeException connectionProblem(Throwable cause) {
        if (cause instanceof ConnectException || cause instanceof java.net.http.HttpConnectTimeoutException) {
            return new OpencodeException("opencode is not reachable (" + cause + ").", false, cause);
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return new OpencodeException("opencode did not answer in time.", false, cause);
        }
        return new OpencodeException("The communication with opencode failed: " + cause, false, cause);
    }

    private static String abbreviate(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

}
