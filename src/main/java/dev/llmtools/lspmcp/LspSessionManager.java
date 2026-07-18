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
  private final Map<String, LspClient> sessions = new ConcurrentHashMap<>();

  public LspSessionManager(ServerConfig config) {
    this.config = config;
  }

  public LspClient get(String language, String rootPathOrUri) {
    var normalizedLanguage = language.toLowerCase(Locale.ROOT);
    var root = UriUtils.toPath(rootPathOrUri);
    var rootUri = root.toUri().toString();
    var key = sessionKey(normalizedLanguage, root);
    var client = sessions.computeIfAbsent(key, ignored -> createSession(normalizedLanguage, rootUri, root));
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

  private LspClient createSession(String language, String rootUri, Path root) {
    var profile = config.profile(language);
    return new LspClient(profile, rootUri, profile.createTransport(root));
  }

  private String sessionKey(String language, Path root) {
    return language + ":" + root;
  }

  String languageId(String language) {
    return config.profile(language).languageId();
  }
}
