package dev.llmtools.lspmcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ServerConfig {
  private static final TypeReference<Map<String, PartialLspProfile>> PROFILE_CONFIG_TYPE = new TypeReference<>() {};
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Map<String, LspProfile> profiles;

  public ServerConfig() throws IOException {
    this.profiles = new ConcurrentHashMap<>(loadProfiles());
  }

  public LspProfile profile(String language) {
    var profile = profiles.get(language.toLowerCase(Locale.ROOT));
    if (profile == null) {
      throw new IllegalArgumentException("No LSP profile configured for language \"" + language + "\"");
    }
    return profile;
  }

  public Map<String, LspProfile> profiles() {
    return Map.copyOf(profiles);
  }

  public LspProfile upsertProfile(String language, JsonNode profileJson) {
    var normalizedLanguage = language.toLowerCase(Locale.ROOT);
    var override = MAPPER.convertValue(profileJson == null || profileJson.isNull() ? MAPPER.createObjectNode() : profileJson, PartialLspProfile.class);
    var profile = merge(normalizedLanguage, profiles.get(normalizedLanguage), override);
    profiles.put(normalizedLanguage, profile);
    return profile;
  }

  private static Map<String, LspProfile> loadProfiles() throws IOException {
    var profiles = defaultProfiles();
    applyProfileOverrides(profiles);
    return profiles;
  }

  private static void applyProfileOverrides(Map<String, LspProfile> profiles) throws IOException {
    var raw = System.getenv("LSP_PROFILES");
    if (raw == null || raw.isBlank()) {
      return;
    }

    var overrides = MAPPER.readValue(raw, PROFILE_CONFIG_TYPE);
    for (var entry : overrides.entrySet()) {
      var language = entry.getKey().toLowerCase(Locale.ROOT);
      profiles.put(language, merge(language, profiles.get(language), entry.getValue()));
    }
  }

  private static LspProfile merge(String language, LspProfile existing, PartialLspProfile override) {
    return new LspProfile(
      language,
      override.languageId() != null ? override.languageId() : existing != null ? existing.languageId() : language,
      override.transport() != null ? LspProfile.TransportKind.from(override.transport()) : existing != null ? existing.transport() : LspProfile.TransportKind.STDIO,
      override.command() != null ? override.command() : existing != null ? existing.command() : List.of(),
      override.host() != null ? override.host() : existing != null ? existing.host() : "127.0.0.1",
      override.port() != null ? override.port() : existing != null ? existing.port() : null,
      override.initializationOptions() != null ? override.initializationOptions() : existing != null ? existing.initializationOptions() : MAPPER.createObjectNode(),
      override.features() != null ? override.features() : existing != null ? existing.features() : Set.of()
    );
  }

  private static Map<String, LspProfile> defaultProfiles() {
    var profiles = new LinkedHashMap<String, LspProfile>();
    var emptyOptions = MAPPER.createObjectNode();
    var common = Set.of("definition", "references", "hover", "documentSymbol", "workspaceSymbol", "diagnostics");

    profiles.put("typescript", stdio("typescript", "typescript", List.of("typescript-language-server", "--stdio"), emptyOptions, common));
    profiles.put("javascript", stdio("javascript", "javascript", List.of("typescript-language-server", "--stdio"), emptyOptions, common));
    profiles.put("python", stdio("python", "python", List.of("pyright-langserver", "--stdio"), emptyOptions, common));
    profiles.put("go", stdio("go", "go", List.of("gopls"), emptyOptions, common));
    profiles.put("rust", stdio("rust", "rust", List.of("rust-analyzer"), emptyOptions, common));
    profiles.put("java", stdio("java", "java", List.of("jdtls"), emptyOptions, common));
    profiles.put("c", stdio("c", "c", List.of("clangd"), emptyOptions, common));
    profiles.put("cpp", stdio("cpp", "cpp", List.of("clangd"), emptyOptions, common));
    return profiles;
  }

  private static LspProfile stdio(String language, String languageId, List<String> command, JsonNode options, Set<String> features) {
    return new LspProfile(language, languageId, LspProfile.TransportKind.STDIO, command, null, null, options, features);
  }

  private record PartialLspProfile(
    String languageId,
    String transport,
    List<String> command,
    String host,
    Integer port,
    JsonNode initializationOptions,
    Set<String> features
  ) {}
}
