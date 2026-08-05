package dev.llmtools.lspmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.function.Function;

final class LspPathMapper {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path hostRoot;
  private final String hostRootUri;
  private final String containerRoot;
  private final String containerRootUri;

  private LspPathMapper(Path hostRoot, String containerRoot) {
    this.hostRoot = hostRoot == null ? null : hostRoot.toAbsolutePath().normalize();
    this.hostRootUri = this.hostRoot == null ? null : this.hostRoot.toUri().toString();
    this.containerRoot = normalizeContainerRoot(containerRoot);
    this.containerRootUri = this.containerRoot == null ? null : uriForContainerPath(this.containerRoot);
  }

  static LspPathMapper identity(Path root) {
    return new LspPathMapper(root, null);
  }

  static LspPathMapper docker(Path hostRoot, String containerRoot) {
    return new LspPathMapper(hostRoot, containerRoot);
  }

  String serverRootUri() {
    return containerRootUri == null ? hostRootUri : containerRootUri;
  }

  String toServerFileUri(String pathOrUri) {
    if (containerRoot == null || hostRoot == null) {
      return UriUtils.toFileUri(pathOrUri);
    }
    try {
      var hostPath = UriUtils.toPath(pathOrUri).toAbsolutePath().normalize();
      if (!hostPath.startsWith(hostRoot)) {
        return UriUtils.toFileUri(pathOrUri);
      }
      var relative = hostRoot.relativize(hostPath);
      return uriForContainerPath(appendRelative(containerRoot, relative));
    } catch (RuntimeException error) {
      return UriUtils.toFileUri(pathOrUri);
    }
  }

  JsonNode toServerTree(JsonNode node) {
    return mapText(node, this::toServerText);
  }

  JsonNode toClientTree(JsonNode node) {
    return mapText(node, this::toClientText);
  }

  private String toServerText(String value) {
    if (containerRoot == null || hostRoot == null || value == null || !value.startsWith("file://")) {
      return value;
    }
    try {
      var path = UriUtils.toPath(value).toAbsolutePath().normalize();
      if (!path.startsWith(hostRoot)) {
        return value;
      }
      return uriForContainerPath(appendRelative(containerRoot, hostRoot.relativize(path)));
    } catch (RuntimeException error) {
      return value;
    }
  }

  private String toClientText(String value) {
    if (containerRoot == null || hostRoot == null || value == null || !value.startsWith("file://")) {
      return value;
    }
    try {
      var uri = URI.create(value);
      if (!"file".equalsIgnoreCase(uri.getScheme())) {
        return value;
      }
      var path = uri.getPath();
      if (!path.equals(containerRoot) && !path.startsWith(containerRoot + "/")) {
        return value;
      }
      var relative = path.substring(containerRoot.length());
      while (relative.startsWith("/")) {
        relative = relative.substring(1);
      }
      var hostPath = hostRoot;
      if (!relative.isBlank()) {
        for (var segment : relative.split("/")) {
          hostPath = hostPath.resolve(segment);
        }
      }
      return hostPath.toUri().toString();
    } catch (RuntimeException error) {
      return value;
    }
  }

  private JsonNode mapText(JsonNode node, Function<String, String> mapper) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return MAPPER.nullNode();
    }
    if (node.isTextual()) {
      return TextNode.valueOf(mapper.apply(node.asText()));
    }
    if (node.isArray()) {
      var array = MAPPER.createArrayNode();
      node.forEach(child -> array.add(mapText(child, mapper)));
      return array;
    }
    if (node.isObject()) {
      var object = MAPPER.createObjectNode();
      node.properties().forEach(entry -> object.set(entry.getKey(), mapText(entry.getValue(), mapper)));
      return object;
    }
    return node.deepCopy();
  }

  private static String appendRelative(String root, Path relative) {
    var result = root;
    for (var segment : relative) {
      if (!result.endsWith("/")) {
        result += "/";
      }
      result += segment.toString().replace('\\', '/');
    }
    return result;
  }

  private static String normalizeContainerRoot(String root) {
    if (root == null || root.isBlank()) {
      return null;
    }
    var normalized = root.replace('\\', '/');
    while (normalized.endsWith("/") && normalized.length() > 1) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.startsWith("/") ? normalized : "/" + normalized;
  }

  private static String uriForContainerPath(String path) {
    try {
      return new URI("file", "", path, null).toASCIIString();
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("Invalid container path: " + path, error);
    }
  }
}
