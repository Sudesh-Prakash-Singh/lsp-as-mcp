package dev.llmtools.lspmcp;

import java.net.URI;
import java.nio.file.Path;

public final class UriUtils {
  private UriUtils() {}

  public static String toFileUri(String pathOrUri) {
    if (pathOrUri.startsWith("file://")) {
      return pathOrUri;
    }
    return Path.of(pathOrUri).toAbsolutePath().normalize().toUri().toString();
  }

  public static Path toPath(String pathOrUri) {
    if (pathOrUri.startsWith("file://")) {
      return Path.of(URI.create(pathOrUri)).toAbsolutePath().normalize();
    }
    return Path.of(pathOrUri).toAbsolutePath().normalize();
  }
}
