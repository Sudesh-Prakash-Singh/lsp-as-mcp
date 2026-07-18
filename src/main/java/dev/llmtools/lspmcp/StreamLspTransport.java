package dev.llmtools.lspmcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

abstract class StreamLspTransport implements LspTransport {
  private BufferedInputStream input;
  private BufferedOutputStream output;

  protected abstract Streams openStreams() throws IOException;

  @Override
  public synchronized void start() {
    if (input != null && output != null) {
      return;
    }
    try {
      var streams = openStreams();
      input = new BufferedInputStream(streams.input());
      output = new BufferedOutputStream(streams.output());
    } catch (IOException error) {
      throw new IllegalStateException("Failed to start LSP transport", error);
    }
  }

  @Override
  public synchronized void writeMessage(byte[] body) {
    start();
    try {
      output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      output.write(body);
      output.flush();
    } catch (IOException error) {
      throw new IllegalStateException("Failed to write LSP message", error);
    }
  }

  @Override
  public byte[] readMessage() {
    start();
    try {
      var contentLength = readContentLength();
      if (contentLength < 0) {
        return null;
      }
      var body = input.readNBytes(contentLength);
      if (body.length != contentLength) {
        return null;
      }
      return body;
    } catch (IOException error) {
      throw new IllegalStateException("Failed to read LSP message", error);
    }
  }

  private int readContentLength() throws IOException {
    var header = new ByteArrayOutputStream();
    var current = input.read();
    while (current != -1) {
      header.write(current);
      var text = header.toString(StandardCharsets.US_ASCII);
      if (text.endsWith("\r\n\r\n")) {
        for (var line : text.split("\r\n")) {
          var parts = line.split(":", 2);
          if (parts.length == 2 && parts[0].equalsIgnoreCase("Content-Length")) {
            return Integer.parseInt(parts[1].trim());
          }
        }
        throw new IOException("Invalid LSP response header");
      }
      current = input.read();
    }
    return -1;
  }

  protected record Streams(java.io.InputStream input, java.io.OutputStream output) {}
}
