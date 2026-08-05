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
      override.dockerCommand() != null ? override.dockerCommand() : existing != null ? existing.dockerCommand() : null,
      override.dockerInstallCommands() != null ? override.dockerInstallCommands() : existing != null ? existing.dockerInstallCommands() : null,
      override.dockerImage() != null ? override.dockerImage() : existing != null ? existing.dockerImage() : null,
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

    profiles.put("typescript", stdio("typescript", "typescript", List.of("typescript-language-server", "--stdio"), npmInstall("typescript-language-server", "typescript typescript-language-server"), emptyOptions, common));
    profiles.put("javascript", stdio("javascript", "javascript", List.of("typescript-language-server", "--stdio"), npmInstall("typescript-language-server", "typescript typescript-language-server"), emptyOptions, common));
    profiles.put("python", stdio("python", "python", List.of("pyright-langserver", "--stdio"), List.of(
      "command -v python >/dev/null 2>&1 || (apt-get update && apt-get install -y --no-install-recommends python-is-python3 python3 python3-pip python3-venv && rm -rf /var/lib/apt/lists/*)",
      npmInstallCommand("pyright-langserver", "pyright")
    ), emptyOptions, common));
    profiles.put("go", stdio("go", "go", List.of("gopls"), List.of(
      "command -v go >/dev/null 2>&1 || (apt-get update && apt-get install -y --no-install-recommends golang-go && rm -rf /var/lib/apt/lists/*)",
      "command -v gopls >/dev/null 2>&1 || GOBIN=/usr/local/bin go install golang.org/x/tools/gopls@latest"
    ), emptyOptions, common));
    profiles.put("rust", stdio("rust", "rust", List.of("rust-analyzer"), List.of(
      "command -v rustup >/dev/null 2>&1 || curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal",
      "command -v rust-analyzer >/dev/null 2>&1 || (/root/.cargo/bin/rustup component add rust-analyzer && ln -sf /root/.rustup/toolchains/*/bin/rust-analyzer /usr/local/bin/rust-analyzer)"
    ), emptyOptions, common));
    profiles.put("java", stdio("java", "java", List.of("jdtls"), List.of(
      "command -v jdtls >/dev/null 2>&1 || (lsp-install-java \"${LSP_DOCKER_JAVA_VERSION:-21}\" && mkdir -p /opt/jdtls && curl -fsSL https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz | tar -xz -C /opt/jdtls && ln -sf /opt/jdtls/bin/jdtls /usr/local/bin/jdtls)"
    ), emptyOptions, common));
    profiles.put("c", stdio("c", "c", List.of("clangd"), aptInstall("clangd", "clangd"), emptyOptions, common));
    profiles.put("cpp", stdio("cpp", "cpp", List.of("clangd"), aptInstall("clangd", "clangd"), emptyOptions, common));
    profiles.put("php", stdio("php", "php", List.of("intelephense", "--stdio"), List.of(
      "command -v php >/dev/null 2>&1 || (apt-get update && apt-get install -y --no-install-recommends php-cli php-mbstring php-xml && rm -rf /var/lib/apt/lists/*)",
      npmInstallCommand("intelephense", "intelephense")
    ), emptyOptions, common));
    profiles.put("ruby", stdio("ruby", "ruby", List.of("solargraph", "stdio"), List.of(
      "command -v ruby >/dev/null 2>&1 || (apt-get update && apt-get install -y --no-install-recommends build-essential ruby-dev ruby-full && rm -rf /var/lib/apt/lists/*)",
      "command -v solargraph >/dev/null 2>&1 || gem install --no-document solargraph"
    ), emptyOptions, common));
    profiles.put("csharp", stdio("csharp", "csharp", List.of("csharp-ls"), List.of(
      "command -v dotnet >/dev/null 2>&1 || (apt-get update && apt-get install -y --no-install-recommends dotnet-sdk-10.0 && rm -rf /var/lib/apt/lists/*)",
      "command -v csharp-ls >/dev/null 2>&1 || dotnet tool install --global csharp-ls"
    ), emptyOptions, common));
    profiles.put("kotlin", stdio("kotlin", "kotlin", List.of("kotlin-lsp", "--stdio"), List.of(
      "command -v kotlin-lsp >/dev/null 2>&1 || (lsp-install-java \"${LSP_DOCKER_KOTLIN_JAVA_VERSION:-25}\" && mkdir -p /opt/kotlin-lsp && curl -fsSL \"https://download-cdn.jetbrains.com/language-server/kotlin-server/${LSP_DOCKER_KOTLIN_LSP_VERSION:-262.9593.0}/kotlin-server-${LSP_DOCKER_KOTLIN_LSP_VERSION:-262.9593.0}.tar.gz\" | tar -xz -C /opt/kotlin-lsp --strip-components=1 && printf '%s\\n' '#!/usr/bin/env bash' 'cd /opt/kotlin-lsp' 'exec ./bin/intellij-server --stdio \"$@\"' > /usr/local/bin/kotlin-lsp && chmod +x /usr/local/bin/kotlin-lsp)"
    ), emptyOptions, common));
    profiles.put("lua", stdio("lua", "lua", List.of("lua-language-server"), List.of(
      "command -v lua-language-server >/dev/null 2>&1 || (mkdir -p /opt/lua-language-server && curl -fsSL \"https://github.com/LuaLS/lua-language-server/releases/download/${LSP_DOCKER_LUA_LS_VERSION:-3.18.2}/lua-language-server-${LSP_DOCKER_LUA_LS_VERSION:-3.18.2}-linux-x64.tar.gz\" | tar -xz -C /opt/lua-language-server && printf '%s\\n' '#!/usr/bin/env bash' 'cd /opt/lua-language-server' 'exec ./bin/lua-language-server \"$@\"' > /usr/local/bin/lua-language-server && chmod +x /usr/local/bin/lua-language-server)"
    ), emptyOptions, common));
    profiles.put("yaml", stdio("yaml", "yaml", List.of("yaml-language-server", "--stdio"), npmInstall("yaml-language-server", "yaml-language-server"), emptyOptions, common));
    profiles.put("json", stdio("json", "json", List.of("vscode-json-language-server", "--stdio"), npmInstall("vscode-json-language-server", "vscode-langservers-extracted"), emptyOptions, common));
    profiles.put("bash", stdio("bash", "shellscript", List.of("bash-language-server", "start"), npmInstall("bash-language-server", "bash-language-server"), emptyOptions, common));
    profiles.put("dockerfile", stdio("dockerfile", "dockerfile", List.of("docker-langserver", "--stdio"), npmInstall("docker-langserver", "dockerfile-language-server-nodejs"), emptyOptions, common));
    return profiles;
  }

  private static LspProfile stdio(String language, String languageId, List<String> command, JsonNode options, Set<String> features) {
    return stdio(language, languageId, command, List.of(), options, features);
  }

  private static LspProfile stdio(String language, String languageId, List<String> command, List<String> dockerInstallCommands, JsonNode options, Set<String> features) {
    return new LspProfile(language, languageId, LspProfile.TransportKind.STDIO, command, command, dockerInstallCommands, null, null, null, options, features);
  }

  private static List<String> npmInstall(String binary, String packages) {
    return List.of(npmInstallCommand(binary, packages));
  }

  private static String npmInstallCommand(String binary, String packages) {
    return "command -v " + binary + " >/dev/null 2>&1 || (lsp-install-node \"${LSP_DOCKER_NODE_VERSION:-24.12.0}\" && npm install -g " + packages + " && npm cache clean --force)";
  }

  private static List<String> aptInstall(String binary, String packages) {
    return List.of("command -v " + binary + " >/dev/null 2>&1 || (apt-get update && apt-get install -y --no-install-recommends " + packages + " && rm -rf /var/lib/apt/lists/*)");
  }

  private record PartialLspProfile(
    String languageId,
    String transport,
    List<String> command,
    List<String> dockerCommand,
    List<String> dockerInstallCommands,
    String dockerImage,
    String host,
    Integer port,
    JsonNode initializationOptions,
    Set<String> features
  ) {}
}
