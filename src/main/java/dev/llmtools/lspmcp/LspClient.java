package dev.llmtools.lspmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class LspClient {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ScheduledExecutorService TIMEOUTS = Executors.newScheduledThreadPool(1, task -> {
    var thread = new Thread(task, "lsp-request-timeouts");
    thread.setDaemon(true);
    return thread;
  });

  private final String language;
  private final String rootUri;
  private final LspProfile profile;
  private final LspTransport transport;
  private final AtomicInteger nextId = new AtomicInteger(1);
  private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  private final Map<String, ObjectNode> diagnostics = new ConcurrentHashMap<>();

  private boolean initialized;
  private boolean started;

  public LspClient(LspProfile profile, String rootUri, LspTransport transport) {
    this.language = profile.language();
    this.rootUri = rootUri;
    this.profile = profile;
    this.transport = transport;
  }

  public synchronized void ensureInitialized() {
    if (initialized) {
      return;
    }
    start();

    var capabilities = MAPPER.createObjectNode();
    capabilities.set("textDocument", object(
      "definition", object("linkSupport", true),
      "references", object(),
      "hover", object("contentFormat", array("markdown", "plaintext")),
      "documentSymbol", object("hierarchicalDocumentSymbolSupport", true),
      "publishDiagnostics", object("relatedInformation", true)
    ));
    capabilities.set("workspace", object("symbol", object("dynamicRegistration", false)));

    request("initialize", object(
      "processId", ProcessHandle.current().pid(),
      "rootUri", rootUri,
      "capabilities", capabilities,
      "initializationOptions", profile.initializationOptions() == null ? object() : profile.initializationOptions()
    ));
    notify("initialized", object());
    initialized = true;
  }

  public JsonNode request(String method, JsonNode params) {
    return request(method, params, 15_000);
  }

  public JsonNode request(String method, JsonNode params, long timeoutMs) {
    start();
    var id = nextId.getAndIncrement();
    var message = object("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    var future = new CompletableFuture<JsonNode>();
    var requestId = requestIdKey(message.path("id"));
    pending.put(requestId, future);
    TIMEOUTS.schedule(() -> {
      var removed = pending.remove(requestId);
      if (removed != null) {
        removed.completeExceptionally(new TimeoutException("LSP " + language + " request timed out: " + method));
      }
    }, timeoutMs, TimeUnit.MILLISECONDS);

    write(message);
    try {
      return future.join();
    } catch (RuntimeException error) {
      if (error.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw error;
    }
  }

  public JsonNode rawMessage(ObjectNode message) {
    start();
    if (!message.hasNonNull("jsonrpc")) {
      message.put("jsonrpc", "2.0");
    }
    if (!message.has("id")) {
      write(message);
      return object("ok", true);
    }

    reserveRequestId(message.path("id"));
    var requestId = requestIdKey(message.path("id"));
    var future = new CompletableFuture<JsonNode>();
    pending.put(requestId, future);
    TIMEOUTS.schedule(() -> {
      var removed = pending.remove(requestId);
      if (removed != null) {
        removed.completeExceptionally(new TimeoutException("LSP " + language + " raw message timed out"));
      }
    }, 15_000, TimeUnit.MILLISECONDS);

    write(message);
    try {
      return future.join();
    } catch (RuntimeException error) {
      if (error.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw error;
    }
  }

  public void notify(String method, JsonNode params) {
    start();
    write(object("jsonrpc", "2.0", "method", method, "params", params));
  }

  public String fileUri(String file) {
    return UriUtils.toFileUri(file);
  }

  public ArrayNode getDiagnostics(String uri) {
    var records = MAPPER.createArrayNode();
    diagnostics.values().stream()
      .filter(record -> uri == null || uri.equals(record.path("uri").asText()))
      .forEach(records::add);
    return records;
  }

  public synchronized void close() {
    if (!started) {
      return;
    }
    try {
      if (initialized) {
        request("shutdown", MAPPER.nullNode(), 5_000);
        notify("exit", MAPPER.nullNode());
      }
    } finally {
      transport.close();
      started = false;
      initialized = false;
    }
  }

  private synchronized void start() {
    if (started) {
      return;
    }
    transport.start();
    started = true;
    var reader = new Thread(this::readLoop, "lsp-reader-" + language);
    reader.setDaemon(true);
    reader.start();
  }

  private void write(ObjectNode message) {
    try {
      var body = MAPPER.writeValueAsBytes(message);
      transport.writeMessage(body);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to write LSP message for " + language, error);
    }
  }

  private void readLoop() {
    try {
      while (started) {
        var body = transport.readMessage();
        if (body == null) {
          rejectAllPending("LSP connection closed");
          return;
        }
        handleMessage(MAPPER.readTree(body));
      }
    } catch (Exception error) {
      rejectAllPending("LSP connection failed: " + error.getMessage());
    }
  }

  private void handleMessage(JsonNode message) {
    if ("textDocument/publishDiagnostics".equals(message.path("method").asText()) && message.path("params").isObject()) {
      var params = (ObjectNode) message.path("params");
      var uri = params.path("uri").asText(null);
      if (uri != null) {
        var record = MAPPER.createObjectNode();
        record.put("uri", uri);
        record.set("diagnostics", params.path("diagnostics").isArray() ? params.path("diagnostics") : MAPPER.createArrayNode());
        record.put("receivedAt", Instant.now().toString());
        diagnostics.put(uri, record);
      }
      return;
    }

    if (!message.has("id")) {
      return;
    }
    var future = pending.remove(requestIdKey(message.path("id")));
    if (future == null) {
      return;
    }
    if (message.has("error")) {
      future.completeExceptionally(new IllegalStateException(message.path("error").toString()));
      return;
    }
    future.complete(message.has("result") ? message.path("result") : MAPPER.nullNode());
  }

  private void rejectAllPending(String reason) {
    for (var entry : pending.entrySet()) {
      pending.remove(entry.getKey());
      entry.getValue().completeExceptionally(new IllegalStateException(reason));
    }
  }

  private String requestIdKey(JsonNode id) {
    if (id == null || id.isMissingNode() || id.isNull()) {
      throw new IllegalArgumentException("LSP request id must not be null");
    }
    if (!id.isIntegralNumber() && !id.isTextual()) {
      throw new IllegalArgumentException("LSP request id must be a string or integer");
    }
    return id.toString();
  }

  private void reserveRequestId(JsonNode id) {
    if (!id.canConvertToInt()) {
      return;
    }
    var rawId = id.asInt();
    nextId.updateAndGet(current -> Math.max(current, rawId + 1));
  }

  private static ObjectNode object(Object... fields) {
    var node = MAPPER.createObjectNode();
    for (var i = 0; i < fields.length; i += 2) {
      var key = (String) fields[i];
      var value = fields[i + 1];
      if (value instanceof JsonNode jsonNode) {
        node.set(key, jsonNode);
      } else if (value instanceof String string) {
        node.put(key, string);
      } else if (value instanceof Integer integer) {
        node.put(key, integer);
      } else if (value instanceof Long longValue) {
        node.put(key, longValue);
      } else if (value instanceof Boolean bool) {
        node.put(key, bool);
      } else if (value == null) {
        node.set(key, MAPPER.nullNode());
      } else {
        node.set(key, MAPPER.valueToTree(value));
      }
    }
    return node;
  }

  private static ArrayNode array(String... values) {
    var node = MAPPER.createArrayNode();
    for (var value : values) {
      node.add(value);
    }
    return node;
  }
}
