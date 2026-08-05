package dev.llmtools.lspmcp;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LspSessionManager {
  private final ServerConfig config;
  private final CommandAvailability commandAvailability;
  private final DockerSupport dockerSupport;
  private final Map<String, LspClient> sessions = new ConcurrentHashMap<>();

  public LspSessionManager(ServerConfig config, CommandAvailability commandAvailability, DockerSupport dockerSupport) {
    this.config = config;
    this.commandAvailability = commandAvailability;
    this.dockerSupport = dockerSupport;
  }

  public LspClient get(String language, String rootPathOrUri) {
    var normalizedLanguage = language.toLowerCase(Locale.ROOT);
    var root = UriUtils.toPath(rootPathOrUri);
    var key = sessionKey(normalizedLanguage, root);
    var client = sessions.computeIfAbsent(key, ignored -> createSession(normalizedLanguage, root));
    client.ensureInitialized();
    return client;
  }

  public void disconnect(String language, String rootPathOrUri) {
    var normalizedLanguage = language.toLowerCase(Locale.ROOT);
    var root = UriUtils.toPath(rootPathOrUri);
    var client = sessions.remove(sessionKey(normalizedLanguage, root));
    if (client != null) {
      client.close();
    }
  }

  public void prepareDocker(String language, String rootPathOrUri) {
    var normalizedLanguage = language.toLowerCase(Locale.ROOT);
    var root = UriUtils.toPath(rootPathOrUri);
    dockerSupport.prepare(root, config.profile(normalizedLanguage));
  }

  public DockerSupport.DockerCommandResult dockerExec(String language, String rootPathOrUri, String command) {
    var normalizedLanguage = language.toLowerCase(Locale.ROOT);
    var root = UriUtils.toPath(rootPathOrUri);
    return dockerSupport.execShell(root, config.profile(normalizedLanguage), command);
  }

  @PreDestroy
  public void closeAll() {
    for (var client : sessions.values()) {
      client.close();
    }
    sessions.clear();
  }

  private LspClient createSession(String language, Path root) {
    var profile = config.profile(language);
    var transport = createTransport(profile, root);
    var pathMapper = transport instanceof DockerLspTransport
      ? LspPathMapper.docker(root, DockerSupport.CONTAINER_ROOT)
      : LspPathMapper.identity(root);
    return new LspClient(profile, pathMapper.serverRootUri(), transport, pathMapper);
  }

  private LspTransport createTransport(LspProfile profile, Path root) {
    return switch (profile.transport()) {
      case TCP -> new TcpLspTransport(profile.host(), profile.port() == null ? 0 : profile.port());
      case DOCKER -> new DockerLspTransport(dockerSupport, profile, root);
      case STDIO -> createStdioOrDockerFallback(profile, root);
    };
  }

  private LspTransport createStdioOrDockerFallback(LspProfile profile, Path root) {
    if (commandAvailability.isAvailable(profile.command())) {
      return new StdioLspTransport(profile.command(), root);
    }
    if (dockerSupport.enabled() && !profile.dockerCommand().isEmpty()) {
      return new DockerLspTransport(dockerSupport, profile, root);
    }
    throw new IllegalStateException("LSP command not found on PATH and Docker fallback is unavailable for " + profile.language() + ": " + profile.command());
  }

  private String sessionKey(String language, Path root) {
    return language + ":" + root;
  }

  String languageId(String language) {
    return config.profile(language).languageId();
  }
}
