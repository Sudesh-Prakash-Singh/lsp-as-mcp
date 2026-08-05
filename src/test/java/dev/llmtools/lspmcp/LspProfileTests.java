package dev.llmtools.lspmcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LspProfileTests {
  @Test
  void dockerCommandDefaultsToLocalCommand() {
    var profile = new LspProfile(
      "TypeScript",
      "typescript",
      LspProfile.TransportKind.STDIO,
      List.of("typescript-language-server", "--stdio"),
      null,
      null,
      null,
      null,
      null,
      null,
      Set.of()
    );

    assertThat(profile.language()).isEqualTo("typescript");
    assertThat(profile.dockerCommand()).containsExactly("typescript-language-server", "--stdio");
  }

  @Test
  void parsesDockerTransportKind() {
    assertThat(LspProfile.TransportKind.from("docker")).isEqualTo(LspProfile.TransportKind.DOCKER);
  }
}
