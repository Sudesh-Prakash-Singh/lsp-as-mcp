package dev.llmtools.lspmcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DockerSupportTests {
  @Test
  void defaultImageNameIsProjectAndLanguageSpecific() {
    var support = new DockerSupport();
    var profile = new LspProfile(
      "java",
      "java",
      LspProfile.TransportKind.STDIO,
      List.of("jdtls"),
      List.of("jdtls"),
      List.of("echo setup"),
      null,
      null,
      null,
      null,
      Set.of()
    );

    var first = support.imageName(Path.of("D:/work/one"), profile);
    var second = support.imageName(Path.of("D:/work/two"), profile);

    assertThat(first).startsWith("lsp-as-mcp/sandbox:java-");
    assertThat(second).startsWith("lsp-as-mcp/sandbox:java-");
    assertThat(first).isNotEqualTo(second);
  }
}
