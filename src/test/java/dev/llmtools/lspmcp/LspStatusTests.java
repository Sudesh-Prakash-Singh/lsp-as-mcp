package dev.llmtools.lspmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LspStatusTests {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void reportsInitializationWithoutBlockingAndAdvertisesProgress() throws Exception {
    var transport = new FakeTransport(false);
    var client = client(transport);
    try {
      assertThat(client.status().path("state").asText()).isEqualTo("created");
      var initializing = CompletableFuture.runAsync(client::ensureInitialized);
      var request = transport.writes.poll(2, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      assertThat(request.at("/params/capabilities/window/workDoneProgress").asBoolean()).isTrue();
      assertThat(client.status().path("state").asText()).isEqualTo("initializing");
      transport.send("{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id") + ",\"result\":{}}");
      initializing.get(2, TimeUnit.SECONDS);
      assertThat(client.status().path("state").asText()).isEqualTo("ready");
      assertThat(client.status().path("progressObserved").asBoolean()).isFalse();
    } finally {
      client.close();
    }
    assertThat(client.status().path("state").asText()).isEqualTo("closed");
  }

  @Test
  void tracksConcurrentTasksPreservesFieldsAndIgnoresPartialResults() throws Exception {
    var transport = new FakeTransport(true);
    var client = client(transport);
    try {
      client.ensureInitialized();
      transport.send("{\"method\":\"$/progress\",\"params\":{\"token\":\"partial\",\"value\":[1,2]}}");
      progress(transport, "\"index\"", "{\"kind\":\"begin\",\"title\":\"Indexing\",\"percentage\":0}");
      progress(transport, "1", "{\"kind\":\"begin\",\"title\":\"Building\"}");
      await(() -> client.status().path("progress").size() == 2);
      assertThat(client.status().path("state").asText()).isEqualTo("loading");
      progress(transport, "\"index\"", "{\"kind\":\"report\",\"message\":\"Scanning sources\",\"percentage\":50}");
      await(() -> client.status().at("/lastProgress/percentage").asInt() == 50);
      assertThat(client.status().at("/lastProgress/title").asText()).isEqualTo("Indexing");
      var snapshot = client.status();
      ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.at("/progress/0")).put("title", "mutated");
      assertThat(client.status().at("/progress/0/title").asText()).isEqualTo("Indexing");
      progress(transport, "\"index\"", "{\"kind\":\"end\",\"message\":\"Done\"}");
      await(() -> client.status().path("progress").size() == 1);
      assertThat(client.status().path("state").asText()).isEqualTo("loading");
      progress(transport, "1", "{\"kind\":\"end\"}");
      await(() -> client.status().path("state").asText().equals("ready"));
      assertThat(client.status().path("progressObserved").asBoolean()).isTrue();
      assertThat(client.status().at("/lastProgress/kind").asText()).isEqualTo("end");
    } finally {
      client.close();
    }
  }

  @Test
  void acknowledgesServerProgressCreationWithoutConsumingClientRequest() throws Exception {
    var transport = new FakeTransport(false);
    var client = client(transport);
    try {
      var initializing = CompletableFuture.runAsync(client::ensureInitialized);
      var request = transport.writes.poll(2, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      // Server and client request IDs are independent and may collide.
      transport.send("{\"id\":" + request.path("id") + ",\"method\":\"window/workDoneProgress/create\",\"params\":{\"token\":\"build\"}}");
      var response = transport.writes.poll(2, TimeUnit.SECONDS);
      assertThat(response).isNotNull();
      assertThat(response.has("result")).isTrue();
      assertThat(response.path("result").isNull()).isTrue();
      assertThat(initializing.isDone()).isFalse();
      transport.send("{\"id\":" + request.path("id") + ",\"result\":{}}");
      initializing.get(2, TimeUnit.SECONDS);
    } finally {
      client.close();
    }
  }

  @Test
  void retainsInitializationErrorsInStatus() throws Exception {
    var transport = new FakeTransport(false);
    var client = client(transport);
    try {
      var initializing = CompletableFuture.runAsync(client::ensureInitialized);
      var request = transport.writes.poll(2, TimeUnit.SECONDS);
      assertThat(request).isNotNull();
      transport.send("{\"id\":" + request.path("id") + ",\"error\":{\"code\":-32603,\"message\":\"Initialization failed\"}}");
      assertThatThrownBy(() -> initializing.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
      assertThat(client.status().path("state").asText()).isEqualTo("failed");
      assertThat(client.status().path("error").asText()).contains("Initialization failed");
      assertThat(client.status().path("initialized").asBoolean()).isFalse();
    } finally {
      client.close();
    }
  }

  @Test
  void reportsConnectionFailureAndRequiresReconnect() throws Exception {
    var transport = new FakeTransport(true);
    var client = client(transport);
    try {
      client.ensureInitialized();
      transport.close();
      await(() -> client.status().path("state").asText().equals("failed"));
      assertThat(client.status().path("initialized").asBoolean()).isFalse();
      assertThat(client.status().path("error").asText()).contains("connection closed");
      assertThatThrownBy(client::ensureInitialized).hasMessageContaining("reconnect");
    } finally {
      client.close();
    }
  }

  @Test
  void statusFiltersExistingSessionsWithoutStartingServers() throws Exception {
    var config = mock(ServerConfig.class);
    var commands = mock(CommandAvailability.class);
    var manager = new LspSessionManager(config, commands);
    assertThat(manager.status(null, null)).isEmpty();
    var first = client(new FakeTransport(true));
    var second = new LspClient(new ServerConfig().profile("go"), Path.of("/other").toUri().toString(), new FakeTransport(true));
    ReflectionTestUtils.setField(manager, "sessions", new java.util.concurrent.ConcurrentHashMap<>(Map.of("java:/project", first, "go:/other", second)));
    assertThat(manager.status(null, null)).hasSize(2);
    assertThat(manager.status("JAVA", "/project")).hasSize(1);
    assertThat(manager.status(null, Path.of("/other").toUri().toString())).hasSize(1);
    assertThat(manager.status("python", null)).isEmpty();
    var tools = new LspMcpTools(manager, config);
    assertThat(MAPPER.readTree(tools.status("java", "/project")).path("sessions")).hasSize(1);
    manager.disconnect("java", "/project");
    assertThat(manager.status("java", null)).isEmpty();
    verifyNoInteractions(config, commands);
  }

  private static LspClient client(FakeTransport transport) throws Exception {
    return new LspClient(new ServerConfig().profile("java"), Path.of("/project").toUri().toString(), transport);
  }

  private static void progress(FakeTransport transport, String token, String value) {
    transport.send("{\"method\":\"$/progress\",\"params\":{\"token\":" + token + ",\"value\":" + value + "}}");
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static class FakeTransport implements LspTransport {
    final BlockingQueue<JsonNode> writes = new LinkedBlockingQueue<>();
    final BlockingQueue<byte[]> reads = new LinkedBlockingQueue<>();
    final boolean autoInitialize;

    FakeTransport(boolean autoInitialize) { this.autoInitialize = autoInitialize; }
    public void start() { }
    public void writeMessage(byte[] body) {
      try {
        var message = MAPPER.readTree(body);
        writes.add(message);
        var method = message.path("method").asText();
        if ((autoInitialize && "initialize".equals(method)) || "shutdown".equals(method)) {
          send("{\"id\":" + message.path("id") + ",\"result\":{}}");
        }
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
    }
    void send(String message) { reads.add(message.getBytes(StandardCharsets.UTF_8)); }
    public byte[] readMessage() {
      try {
        var body = reads.take();
        return body.length == 0 ? null : body;
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(error);
      }
    }
    public void close() { reads.add(new byte[0]); }
  }
}
