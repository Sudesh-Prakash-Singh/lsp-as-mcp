package dev.llmtools.lspmcp;

interface LspTransport extends AutoCloseable {
  void start();

  void writeMessage(byte[] body);

  byte[] readMessage();

  @Override
  void close();
}
