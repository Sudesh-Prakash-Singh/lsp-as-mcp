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
  private final Map<String, LspClient> sessions = new ConcurrentHashMap<>();

  public LspSessionManager(ServerConfig config, CommandAvailability commandAvailability) {
    this.config = config;
    this.commandAvailability = commandAvailability;
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
    return new LspClient(profile, root.toUri().toString(), transport);
  }

  private LspTransport createTransport(LspProfile profile, Path root) {
    return switch (profile.transport()) {
      case TCP -> new TcpLspTransport(profile.host(), profile.port() == null ? 0 : profile.port());
      case STDIO -> createStdio(profile, root);
    };
  }

  private LspTransport createStdio(LspProfile profile, Path root) {
    if (commandAvailability.isAvailable(profile.command())) {
      return new StdioLspTransport(profile.command(), root);
    }
    var hint = profile.installHint() == null || profile.installHint().isBlank()
      ? "Install the language server and ensure the executable is on PATH."
      : profile.installHint();
    throw new IllegalStateException(
      "LSP command not found on PATH for "
        + profile.language()
        + ": "
        + profile.command()
        + ". "
        + hint
    );
  }

  private String sessionKey(String language, Path root) {
    return language + ":" + root;
  }

  String languageId(String language) {
    return config.profile(language).languageId();
  }
}
