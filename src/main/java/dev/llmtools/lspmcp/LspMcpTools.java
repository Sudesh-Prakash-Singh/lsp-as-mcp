package dev.llmtools.lspmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LspMcpTools {
  private final ObjectMapper mapper = new ObjectMapper();
  private final LspSessionManager sessions;
  private final ServerConfig config;

  public LspMcpTools(LspSessionManager sessions, ServerConfig config) {
    this.sessions = sessions;
    this.config = config;
  }

  @McpTool(name = "profiles", description = "List configured LSP profiles and their declared features.")
  public String profiles() {
    var result = mapper.createObjectNode();
    config.profiles().entrySet().stream()
      .sorted(Map.Entry.comparingByKey())
      .forEach(entry -> {
        var profile = entry.getValue();
        result.set(entry.getKey(), object(
          "languageId", profile.languageId(),
          "transport", profile.transport().name().toLowerCase(),
          "command", profile.command(),
          "dockerCommand", profile.dockerCommand(),
          "dockerImage", profile.dockerImage(),
          "host", profile.host(),
          "port", profile.port(),
          "features", profile.features()
        ));
      });
    return pretty(result);
  }

  @McpTool(name = "connect", description = "Register or update an LSP profile at runtime and initialize a session for a project root.")
  public String connect(
    @McpToolParam(description = "Language/profile key to register or connect.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Profile JSON object. Use {} to connect with the existing profile.", required = true) String profileJson
  ) {
    if (language == null || language.isBlank()) {
      throw new IllegalArgumentException("language must not be blank");
    }
    try {
      var profileNode = mapper.readTree(profileJson == null || profileJson.isBlank() ? "{}" : profileJson);
      if (!profileNode.isObject()) {
        throw new IllegalArgumentException("profileJson must be a JSON object");
      }
      sessions.disconnect(language, root);
      var profile = config.upsertProfile(language, profileNode);
      sessions.get(language, root);
      return pretty(object(
        "ok", true,
        "language", profile.language(),
        "languageId", profile.languageId(),
        "transport", profile.transport().name().toLowerCase()
      ));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("profileJson must be valid JSON", error);
    }
  }

  @McpTool(name = "disconnect", description = "Close an initialized LSP session for a language/profile and project root.")
  public String disconnect(
    @McpToolParam(description = "Configured language/profile key.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root
  ) {
    sessions.disconnect(language, root);
    return pretty(object("ok", true));
  }

  @McpTool(name = "definition", description = "Find definitions for a symbol position using a configured language server.")
  public String definition(
    @McpToolParam(description = "Language key such as typescript, python, go, rust, java, c, or cpp.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Source file path or file URI.", required = true) String file,
    @McpToolParam(description = "Zero-based line number.", required = true) int line,
    @McpToolParam(description = "Zero-based character offset.", required = true) int character
  ) {
    return pretty(textDocumentPositionRequest("textDocument/definition", language, root, file, line, character, null));
  }

  @McpTool(name = "references", description = "Find references for a symbol position using a configured language server.")
  public String references(
    @McpToolParam(description = "Language key such as typescript, python, go, rust, java, c, or cpp.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Source file path or file URI.", required = true) String file,
    @McpToolParam(description = "Zero-based line number.", required = true) int line,
    @McpToolParam(description = "Zero-based character offset.", required = true) int character,
    @McpToolParam(description = "Whether to include the symbol declaration in the reference result.", required = false) Boolean includeDeclaration
  ) {
    var extra = object("context", object("includeDeclaration", Boolean.TRUE.equals(includeDeclaration)));
    return pretty(textDocumentPositionRequest("textDocument/references", language, root, file, line, character, extra));
  }

  @McpTool(name = "hover", description = "Get hover/type information for a symbol position.")
  public String hover(
    @McpToolParam(description = "Language key such as typescript, python, go, rust, java, c, or cpp.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Source file path or file URI.", required = true) String file,
    @McpToolParam(description = "Zero-based line number.", required = true) int line,
    @McpToolParam(description = "Zero-based character offset.", required = true) int character
  ) {
    return pretty(textDocumentPositionRequest("textDocument/hover", language, root, file, line, character, null));
  }

  @McpTool(name = "symbols", description = "List symbols in a document.")
  public String documentSymbols(
    @McpToolParam(description = "Language key such as typescript, python, go, rust, java, c, or cpp.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Source file path or file URI.", required = true) String file
  ) {
    var client = clientFor(language, root);
    return pretty(client.request("textDocument/documentSymbol", object(
      "textDocument", object("uri", client.fileUri(file))
    )));
  }

  @McpTool(name = "workspace_symbols", description = "Search workspace symbols.")
  public String workspaceSymbols(
    @McpToolParam(description = "Language key such as typescript, python, go, rust, java, c, or cpp.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Workspace symbol query.", required = true) String query
  ) {
    return pretty(clientFor(language, root).request("workspace/symbol", object("query", query)));
  }

  @McpTool(name = "open", description = "Open or refresh a document in the language server. Useful before diagnostics.")
  public String openDocument(
    @McpToolParam(description = "Language key such as typescript, python, go, rust, java, c, or cpp.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Source file path or file URI.", required = true) String file,
    @McpToolParam(description = "Full document text.", required = true) String text,
    @McpToolParam(description = "Document version.", required = false) Integer version,
    @McpToolParam(description = "Language-server language id.", required = false) String languageId
  ) {
    var client = clientFor(language, root);
    client.notify("textDocument/didOpen", object(
      "textDocument", object(
        "uri", client.fileUri(file),
        "languageId", languageId == null || languageId.isBlank() ? sessions.languageId(language) : languageId,
        "version", version == null ? 1 : version,
        "text", text
      )
    ));
    return pretty(object("ok", true));
  }

  @McpTool(name = "diagnostics", description = "Return diagnostics most recently published by the language server.")
  public String diagnostics(
    @McpToolParam(description = "Language key such as typescript, python, go, rust, java, c, or cpp.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Optional source file path or file URI.", required = false) String file
  ) {
    var client = clientFor(language, root);
    var uri = file == null || file.isBlank() ? null : UriUtils.toFileUri(file);
    return pretty(client.getDiagnostics(uri));
  }

  @McpTool(name = "request", description = "Send a raw LSP request for profile-specific features not covered by typed tools.")
  public String request(
    @McpToolParam(description = "Configured language/profile key.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "LSP method name, such as textDocument/implementation.", required = true) String method,
    @McpToolParam(description = "JSON object params for the LSP request. Use {} when the method has no params.", required = true) String paramsJson
  ) {
    if (method == null || method.isBlank()) {
      throw new IllegalArgumentException("method must not be blank");
    }
    try {
      var params = mapper.readTree(paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
      return pretty(clientFor(language, root).request(method, params));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("paramsJson must be valid JSON", error);
    }
  }

  @McpTool(name = "notify", description = "Send a raw LSP notification for profile-specific features not covered by typed tools.")
  public String notify(
    @McpToolParam(description = "Configured language/profile key.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "LSP notification method name, such as textDocument/didChange.", required = true) String method,
    @McpToolParam(description = "JSON object params for the LSP notification. Use {} when the method has no params.", required = true) String paramsJson
  ) {
    if (method == null || method.isBlank()) {
      throw new IllegalArgumentException("method must not be blank");
    }
    try {
      var params = mapper.readTree(paramsJson == null || paramsJson.isBlank() ? "{}" : paramsJson);
      clientFor(language, root).notify(method, params);
      return pretty(object("ok", true));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("paramsJson must be valid JSON", error);
    }
  }

  @McpTool(name = "raw", description = "Send a full JSON-RPC message to an LSP server. Messages with an id wait for a response; messages without an id are notifications.")
  public String rawMessage(
    @McpToolParam(description = "Configured language/profile key.", required = true) String language,
    @McpToolParam(description = "Project root path or file URI.", required = true) String root,
    @McpToolParam(description = "Full JSON-RPC message object as JSON.", required = true) String messageJson
  ) {
    try {
      var message = mapper.readTree(messageJson);
      if (!message.isObject()) {
        throw new IllegalArgumentException("messageJson must be a JSON object");
      }
      return pretty(clientFor(language, root).rawMessage((ObjectNode) message));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("messageJson must be valid JSON", error);
    }
  }

  private JsonNode textDocumentPositionRequest(
    String method,
    String language,
    String root,
    String file,
    int line,
    int character,
    ObjectNode extra
  ) {
    requireNonNegative("line", line);
    requireNonNegative("character", character);
    var client = clientFor(language, root);
    var params = object(
      "textDocument", object("uri", client.fileUri(file)),
      "position", object("line", line, "character", character)
    );
    if (extra != null) {
      extra.properties().forEach(entry -> params.set(entry.getKey(), entry.getValue()));
    }
    return client.request(method, params);
  }

  private LspClient clientFor(String language, String root) {
    return sessions.get(language, root);
  }

  private void requireNonNegative(String name, int value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
  }

  private String pretty(JsonNode node) {
    return node.toPrettyString();
  }

  private ObjectNode object(Object... fields) {
    var node = mapper.createObjectNode();
    for (var i = 0; i < fields.length; i += 2) {
      var key = (String) fields[i];
      var value = fields[i + 1];
      if (value instanceof JsonNode jsonNode) {
        node.set(key, jsonNode);
      } else if (value instanceof String string) {
        node.put(key, string);
      } else if (value instanceof Integer integer) {
        node.put(key, integer);
      } else if (value instanceof Long longValue) {
        node.put(key, longValue);
      } else if (value instanceof Boolean bool) {
        node.put(key, bool);
      } else if (value == null) {
        node.set(key, mapper.nullNode());
      } else {
        node.set(key, mapper.valueToTree(value));
      }
    }
    return node;
  }
}
