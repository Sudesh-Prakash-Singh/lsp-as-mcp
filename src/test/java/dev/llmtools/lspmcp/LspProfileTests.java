package dev.llmtools.lspmcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LspProfileTests {
  @Test
  void normalizesLanguageAndCopiesCommand() {
    var profile = new LspProfile(
      "TypeScript",
      "typescript",
      LspProfile.TransportKind.STDIO,
      List.of("typescript-language-server", "--stdio"),
      null,
      null,
      null,
      Set.of(),
      "Install with npm"
    );

    assertThat(profile.language()).isEqualTo("typescript");
    assertThat(profile.command()).containsExactly("typescript-language-server", "--stdio");
    assertThat(profile.installHint()).isEqualTo("Install with npm");
  }

  @Test
  void defaultsTransportToStdio() {
    assertThat(LspProfile.TransportKind.from(null)).isEqualTo(LspProfile.TransportKind.STDIO);
  }
}
