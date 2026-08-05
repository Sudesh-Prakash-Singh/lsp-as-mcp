package dev.llmtools.lspmcp;

import java.io.IOException;
import java.nio.file.Path;

final class DockerLspTransport extends StreamLspTransport {
  private final DockerSupport dockerSupport;
  private final LspProfile profile;
  private final Path root;
  private DockerSupport.DockerExecSession execSession;

  DockerLspTransport(DockerSupport dockerSupport, LspProfile profile, Path root) {
    this.dockerSupport = dockerSupport;
    this.profile = profile;
    this.root = root;
  }

  @Override
  protected Streams openStreams() throws IOException {
    execSession = dockerSupport.startExec(root, profile);
    return new Streams(execSession.input(), execSession.output());
  }

  @Override
  public void close() {
    if (execSession != null) {
      execSession.close();
      execSession = null;
    }
  }
}
