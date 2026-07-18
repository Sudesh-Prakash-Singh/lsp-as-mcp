package dev.llmtools.lspmcp;

import java.io.IOException;
import java.util.List;

final class StdioLspTransport extends StreamLspTransport {
  private final List<String> command;
  private final java.nio.file.Path workingDirectory;
  private Process process;

  StdioLspTransport(List<String> command, java.nio.file.Path workingDirectory) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("LSP stdio command must not be empty");
    }
    this.command = List.copyOf(command);
    this.workingDirectory = workingDirectory;
  }

  @Override
  protected Streams openStreams() throws IOException {
    var builder = new ProcessBuilder(command);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    process = builder.start();
    return new Streams(process.getInputStream(), process.getOutputStream());
  }

  @Override
  public void close() {
    if (process == null) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }
}
