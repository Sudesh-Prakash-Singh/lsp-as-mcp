package dev.llmtools.lspmcp;

import java.io.IOException;
import java.net.Socket;

final class TcpLspTransport extends StreamLspTransport {
  private final String host;
  private final int port;
  private Socket socket;

  TcpLspTransport(String host, int port) {
    if (host == null || host.isBlank()) {
      host = "127.0.0.1";
    }
    if (port <= 0) {
      throw new IllegalArgumentException("LSP TCP port must be positive");
    }
    this.host = host;
    this.port = port;
  }

  @Override
  protected Streams openStreams() throws IOException {
    socket = new Socket(host, port);
    return new Streams(socket.getInputStream(), socket.getOutputStream());
  }

  @Override
  public void close() {
    if (socket == null) {
      return;
    }
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
