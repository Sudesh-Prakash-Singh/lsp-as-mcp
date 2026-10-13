package dev.llmtools.lspmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LspStartupTests {
  @TempDir Path directory;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void wrapperReturnsHookOutputWithoutDisconnecting() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var tools = new LspMcpTools(sessions, mock(ServerConfig.class));
    var output = mapper.readTree(tools.connectSessionStart("python", "/project"));
    assertThat(output.path("hookSpecificOutput").path("hookEventName").asText()).isEqualTo("SessionStart");
    assertThat(output.path("hookSpecificOutput").path("additionalContext").asText()).contains("/project");
    verify(sessions).get("python", "/project");
    verifyNoMoreInteractions(sessions);
  }

  @Test
  void startupIsDisabledWithoutConfiguration() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var config = mock(ServerConfig.class);
    new LspStartup(sessions, config, new MockEnvironment()).initialize();
    verifyNoInteractions(sessions, config);
  }

  @Test
  void startsAnyConfiguredLanguageDuringBeanInitialization() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var env = singleSession("python");
    var config = new ServerConfig();
    try (var context = new AnnotationConfigApplicationContext()) {
      context.setEnvironment(env);
      context.registerBean(LspSessionManager.class, () -> sessions);
      context.registerBean(ServerConfig.class, () -> config);
      context.registerBean(LspStartup.class);
      context.refresh();
      verify(sessions).get("python", directory.toString());
      assertThat(config.profile("python").command()).containsExactly("pyright-langserver", "--stdio");
      verifyNoMoreInteractions(sessions);
    }
  }

  @Test
  void commandOverrideUsesExactArgumentsWithoutJavaSpecificFlags() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var config = new ServerConfig();
    var env = singleSession("java").withProperty("LSP_STARTUP_COMMAND", "[\"/opt/jdtls\",\"-data\",\"/chosen/workspace\"]");
    new LspStartup(sessions, config, env).initialize();
    assertThat(config.profile("java").command()).containsExactly("/opt/jdtls", "-data", "/chosen/workspace");
    verify(sessions).get("java", directory.toString());
  }

  @Test
  void executablePathCanContainSpaces() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var config = new ServerConfig();
    new LspStartup(sessions, config, singleSession("rust").withProperty("LSP_STARTUP_COMMAND", "/opt/my tools/rust-analyzer")).initialize();
    assertThat(config.profile("rust").command()).containsExactly("/opt/my tools/rust-analyzer");
  }

  @Test
  void startsMultipleLanguagesAndRootsAndDeduplicatesSessions() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var root = directory.toString();
    var env = new MockEnvironment().withProperty("LSP_STARTUP_SESSIONS", """
      [{"language":"PYTHON","root":"%s"},
       {"language":"go","root":"%s/other"},
       {"language":"python","root":"%s/./"}]
      """.formatted(root, root, root));
    new LspStartup(sessions, new ServerConfig(), env).initialize();
    verify(sessions).get("python", root);
    verify(sessions).get("go", directory.resolve("other").toString());
    verifyNoMoreInteractions(sessions);
  }

  @Test
  void configFileDefinesProfilesAndRootsRelativeToItsDirectory() throws Exception {
    var file = writeConfig("""
      {"profiles":{"custom":{"languageId":"python","command":["custom-server","--stdio"],
        "initializationOptions":{"analysis":true}},
        "go-tcp":{"languageId":"go","transport":"tcp","host":"localhost","port":2093}},
       "sessions":[{"language":"custom","root":"./project"},{"language":"go-tcp","root":"./project"}]}
      """);
    var sessions = mock(LspSessionManager.class);
    var config = new ServerConfig();
    new LspStartup(sessions, config, new MockEnvironment().withProperty("LSP_STARTUP_CONFIG", file.toString())).initialize();
    verify(sessions).get("custom", directory.resolve("project").toString());
    verify(sessions).get("go-tcp", directory.resolve("project").toString());
    assertThat(config.profile("custom").command()).containsExactly("custom-server", "--stdio");
    assertThat(config.profile("custom").initializationOptions().path("analysis").asBoolean()).isTrue();
    assertThat(config.profile("go-tcp").transport()).isEqualTo(LspProfile.TransportKind.TCP);
    assertThat(config.profile("go-tcp").port()).isEqualTo(2093);
  }

  @Test
  void environmentOverridesFileSessionsAndMergesProfileOverrides() throws Exception {
    var file = writeConfig("""
      {"profiles":{"CUSTOM":{"languageId":"python","command":["from-file"]}},
       "sessions":[{"language":"custom","root":"file-project"}]}
      """);
    var sessions = mock(LspSessionManager.class);
    var config = new ServerConfig();
    var env = new MockEnvironment().withProperty("LSP_STARTUP_CONFIG", file.toString())
      .withProperty("LSP_PROFILES", "{\"custom\":{\"command\":[\"from-env\"]}}")
      .withProperty("LSP_STARTUP_SESSIONS", "[{\"language\":\"custom\",\"root\":\"" + directory.toUri() + "\"}]");
    new LspStartup(sessions, config, env).initialize();
    assertThat(config.profile("custom").command()).containsExactly("from-env");
    assertThat(config.profile("custom").languageId()).isEqualTo("python");
    verify(sessions).get("custom", directory.toString());
    verifyNoMoreInteractions(sessions);
  }

  @Test
  void emptyEnvironmentListDisablesFileSessions() throws Exception {
    var file = writeConfig("{\"sessions\":[{\"language\":\"go\",\"root\":\".\"}]}");
    var sessions = mock(LspSessionManager.class);
    new LspStartup(sessions, new ServerConfig(), new MockEnvironment()
      .withProperty("LSP_STARTUP_CONFIG", file.toString()).withProperty("LSP_STARTUP_SESSIONS", "[]")).initialize();
    verifyNoInteractions(sessions);
  }

  @Test
  void validatesAllSessionsBeforeStartingAnyServer() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var config = new ServerConfig();
    for (var json : new String[]{"null", "{}", "[{\"language\":\"go\",\"root\":\".\"},{}]",
      "[{\"language\":\"go\",\"root\":\".\"},{\"language\":\"missing\",\"root\":\".\"}]"}) {
      var env = new MockEnvironment().withProperty("LSP_STARTUP_SESSIONS", json);
      assertThatThrownBy(() -> new LspStartup(sessions, config, env).initialize()).isInstanceOf(Exception.class);
    }
    verifyNoInteractions(sessions);
  }

  @Test
  void rejectsIncompleteOrConflictingEnvironmentConfiguration() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var config = new ServerConfig();
    for (var env : new MockEnvironment[]{
      new MockEnvironment().withProperty("LSP_STARTUP_ROOT", "."),
      new MockEnvironment().withProperty("LSP_STARTUP_LANGUAGE", "python"),
      singleSession("python").withProperty("LSP_STARTUP_SESSIONS", "[]"),
      singleSession("python").withProperty("LSP_STARTUP_COMMAND", "[]"),
      singleSession("python").withProperty("LSP_STARTUP_COMMAND", "[123]")}) {
      assertThatThrownBy(() -> new LspStartup(sessions, config, env).initialize()).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(sessions);
  }

  @Test
  void rejectsMissingMalformedAndUnknownConfigFields() throws Exception {
    var sessions = mock(LspSessionManager.class);
    var config = new ServerConfig();
    for (var content : new String[]{"null", "[]", "{", "{\"session\":[]}", "{\"profiles\":{\"bad\":[]}}"}) {
      var file = writeConfig(content);
      assertThatThrownBy(() -> new LspStartup(sessions, config,
        new MockEnvironment().withProperty("LSP_STARTUP_CONFIG", file.toString())).initialize()).isInstanceOf(Exception.class);
    }
    assertThatThrownBy(() -> new LspStartup(sessions, config, new MockEnvironment()
      .withProperty("LSP_STARTUP_CONFIG", directory.resolve("missing.json").toString())).initialize()).isInstanceOf(java.io.IOException.class);
    verifyNoInteractions(sessions);
  }

  @Test
  void startupFailureIncludesLanguageRootAndUnderlyingReason() throws Exception {
    var sessions = mock(LspSessionManager.class);
    when(sessions.get("python", directory.toString())).thenThrow(new IllegalStateException("command missing"));
    assertThatThrownBy(() -> new LspStartup(sessions, new ServerConfig(), singleSession("python")).initialize())
      .isInstanceOf(IllegalStateException.class).hasMessageContaining("python")
      .hasMessageContaining(directory.toString()).hasMessageContaining("command missing");
  }

  private MockEnvironment singleSession(String language) {
    return new MockEnvironment().withProperty("LSP_STARTUP_LANGUAGE", language)
      .withProperty("LSP_STARTUP_ROOT", directory.toString());
  }

  private Path writeConfig(String content) throws Exception {
    return Files.writeString(directory.resolve("startup.json"), content);
  }
}
