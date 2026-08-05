package dev.llmtools.lspmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LspPathMapperTests {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void mapsHostPathsToContainerUris() {
    var root = Path.of("D:/work/project").toAbsolutePath().normalize();
    var mapper = LspPathMapper.docker(root, "/workspace/project");

    var uri = mapper.toServerFileUri(root.resolve("src/Main.java").toString());

    assertThat(uri).isEqualTo("file:///workspace/project/src/Main.java");
  }

  @Test
  void mapsContainerUrisBackToHostUrisInResults() throws Exception {
    var root = Path.of("D:/work/project").toAbsolutePath().normalize();
    var mapper = LspPathMapper.docker(root, "/workspace/project");
    var result = MAPPER.readTree("""
      {
        "uri": "file:///workspace/project/src/Main.java",
        "range": {
          "start": { "line": 1, "character": 2 },
          "end": { "line": 1, "character": 5 }
        }
      }
      """);

    var mapped = mapper.toClientTree(result);

    assertThat(mapped.path("uri").asText()).isEqualTo(root.resolve("src/Main.java").toUri().toString());
    assertThat(mapped.path("range").path("start").path("line").asInt()).isEqualTo(1);
  }
}
