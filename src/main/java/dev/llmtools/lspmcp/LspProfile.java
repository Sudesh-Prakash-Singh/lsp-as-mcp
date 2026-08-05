package dev.llmtools.lspmcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record LspProfile(
  String language,
  String languageId,
  TransportKind transport,
  List<String> command,
  String host,
  Integer port,
  JsonNode initializationOptions,
  Set<String> features,
  String installHint
) {
  public LspProfile {
    if (language == null || language.isBlank()) {
      throw new IllegalArgumentException("LSP profile language must not be blank");
    }
    language = language.toLowerCase(Locale.ROOT);
    if (languageId == null || languageId.isBlank()) {
      languageId = language;
    }
    if (transport == null) {
      transport = TransportKind.STDIO;
    }
    command = command == null ? List.of() : List.copyOf(command);
    features = features == null ? Set.of() : Set.copyOf(features);
  }

  public enum TransportKind {
    STDIO,
    TCP;

    static TransportKind from(String raw) {
      if (raw == null || raw.isBlank()) {
        return STDIO;
      }
      return TransportKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
  }
}
