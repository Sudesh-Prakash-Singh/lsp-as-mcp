package dev.llmtools.lspmcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Initializes configured language/root sessions during MCP application startup. */
@Component
public class LspStartup {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<StartupSession>> SESSION_LIST = new TypeReference<>() {};
  private final LspSessionManager sessions;
  private final ServerConfig config;
  private final Environment environment;

  public LspStartup(LspSessionManager sessions, ServerConfig config, Environment environment) {
    this.sessions = sessions;
    this.config = config;
    this.environment = environment;
  }

  @PostConstruct
  public void initialize() throws IOException {
    var profiles = new LinkedHashMap<String, JsonNode>();
    List<StartupSession> requested = List.of();
    Path rootBase = null;
    var configPath = property("LSP_STARTUP_CONFIG");
    if (configPath != null) {
      var path = UriUtils.toPath(configPath);
      StartupFile file;
      try (var input = Files.newInputStream(path)) {
        file = MAPPER.readValue(input, StartupFile.class);
      }
      if (file == null) {
        throw new IllegalArgumentException("LSP_STARTUP_CONFIG must contain a JSON object");
      }
      if (file.profiles() != null) {
        file.profiles().forEach((key, value) -> profiles.put(key.toLowerCase(Locale.ROOT), value));
      }
      requested = file.sessions() == null ? List.of() : file.sessions();
      rootBase = path.getParent();
    }

    var sessionJson = property("LSP_STARTUP_SESSIONS");
    var language = property("LSP_STARTUP_LANGUAGE");
    var root = property("LSP_STARTUP_ROOT");
    var command = property("LSP_STARTUP_COMMAND");
    if (sessionJson != null) {
      if (language != null || root != null || command != null) {
        throw new IllegalArgumentException("Use LSP_STARTUP_SESSIONS or the single-session LSP_STARTUP_LANGUAGE/ROOT/COMMAND variables, not both");
      }
      requested = MAPPER.readValue(sessionJson, SESSION_LIST);
      rootBase = null;
    } else if (language != null || root != null || command != null) {
      if (language == null || root == null) {
        throw new IllegalArgumentException("LSP_STARTUP_LANGUAGE and LSP_STARTUP_ROOT must both be set");
      }
      requested = List.of(new StartupSession(language, root));
      rootBase = null;
    }
    if (requested == null) {
      throw new IllegalArgumentException("LSP_STARTUP_SESSIONS must be a JSON array");
    }

    // Normalize and validate the entire session list before starting any server.
    var normalized = new LinkedHashSet<StartupSession>();
    for (var session : requested) {
      if (session == null || session.language() == null || session.language().isBlank()
        || session.root() == null || session.root().isBlank()) {
        throw new IllegalArgumentException("Each startup session requires nonblank language and root fields");
      }
      var sessionRoot = session.root();
      if (rootBase != null && !sessionRoot.startsWith("file://") && !Path.of(sessionRoot).isAbsolute()) {
        sessionRoot = rootBase.resolve(sessionRoot).toString();
      }
      normalized.add(new StartupSession(session.language().toLowerCase(Locale.ROOT), UriUtils.toPath(sessionRoot).toString()));
    }

    // Environment profile overrides take precedence over profiles from the file.
    var envProfiles = property("LSP_PROFILES");
    if (envProfiles != null) {
      var overrides = MAPPER.readTree(envProfiles);
      if (overrides == null || !overrides.isObject()) {
        throw new IllegalArgumentException("LSP_PROFILES must be a JSON object");
      }
      overrides.properties().forEach(entry -> {
        var key = entry.getKey().toLowerCase(Locale.ROOT);
        var base = profiles.remove(key);
        var override = entry.getValue();
        if (base != null && base.isObject() && override.isObject()) {
          profiles.put(key, ((ObjectNode) base).deepCopy()
            .setAll((ObjectNode) override));
        } else {
          profiles.put(key, override);
        }
      });
    }
    if (command != null) {
      var commandNode = command.stripLeading().startsWith("[")
        ? MAPPER.readTree(command) : MAPPER.createArrayNode().add(command);
      if (!commandNode.isArray() || commandNode.isEmpty()) {
        throw new IllegalArgumentException("LSP_STARTUP_COMMAND must be an executable path or a nonempty JSON argument array");
      }
      for (var argument : commandNode) {
        if (!argument.isTextual() || argument.asText().isBlank()) {
          throw new IllegalArgumentException("LSP_STARTUP_COMMAND arguments must be nonblank strings");
        }
      }
      var key = language.toLowerCase(Locale.ROOT);
      var profile = profiles.get(key);
      if (profile != null && !profile.isObject()) {
        throw new IllegalArgumentException("Startup profile must be a JSON object: " + key);
      }
      var override = profile == null ? MAPPER.createObjectNode()
        : ((ObjectNode) profile).deepCopy();
      override.put("transport", "stdio");
      override.set("command", commandNode);
      profiles.put(key, override);
    }
    for (var entry : profiles.entrySet()) {
      if (entry.getKey().isBlank() || entry.getValue() == null || !entry.getValue().isObject()) {
        throw new IllegalArgumentException("Startup profiles require nonblank keys and JSON object values");
      }
      config.upsertProfile(entry.getKey(), entry.getValue());
    }
    for (var session : normalized) {
      config.profile(session.language());
    }
    for (var session : normalized) {
      try {
        sessions.get(session.language(), session.root());
      } catch (RuntimeException error) {
        throw new IllegalStateException("Failed to initialize startup LSP session " + session.language()
          + " at " + session.root() + ": " + error.getMessage(), error);
      }
    }
  }

  private String property(String name) {
    var value = environment.getProperty(name);
    return value == null || value.isBlank() ? null : value;
  }

  private record StartupFile(Map<String, JsonNode> profiles, List<StartupSession> sessions) {}
  private record StartupSession(String language, String root) {}
}
