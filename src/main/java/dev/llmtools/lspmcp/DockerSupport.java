package dev.llmtools.lspmcp;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DockerSupport {
  static final String CONTAINER_ROOT = "/workspace/project";
  private static final String DEFAULT_IMAGE_PREFIX = "lsp-as-mcp/sandbox";
  private static final String LABEL = "dev.llmtools.lsp-as-mcp";

  private final Map<String, ContainerRef> containers = new ConcurrentHashMap<>();
  private DockerClient dockerClient;
  private DockerHttpClient httpClient;

  boolean enabled() {
    return envFlag("LSP_DOCKER_ENABLED", true);
  }

  String imageName(Path root, LspProfile profile) {
    if (profile.dockerImage() != null && !profile.dockerImage().isBlank()) {
      return profile.dockerImage();
    }
    var override = System.getenv("LSP_DOCKER_IMAGE");
    if (override != null && !override.isBlank()) {
      return override;
    }
    var normalizedRoot = root.toAbsolutePath().normalize();
    return DEFAULT_IMAGE_PREFIX
      + ":"
      + safeTagPart(profile.language())
      + "-"
      + digest(normalizedRoot.toString())
      + "-"
      + safeTagPart(buildVariant(profile))
      + "-"
      + dockerfileFingerprint(normalizedRoot);
  }

  DockerExecSession startExec(Path root, LspProfile profile) throws IOException {
    if (!enabled()) {
      throw new IllegalStateException("Docker LSP fallback is disabled by LSP_DOCKER_ENABLED=false");
    }
    var image = imageName(root, profile);
    var container = acquireContainer(root, image, profile);
    try {
      return startExec(container, profile.dockerCommand());
    } catch (RuntimeException | IOException error) {
      releaseContainer(container.key());
      throw error;
    }
  }

  @PreDestroy
  public synchronized void close() {
    for (var ref : containers.values()) {
      stopAndRemove(ref);
    }
    containers.clear();
    if (dockerClient != null) {
      try {
        dockerClient.close();
      } catch (IOException ignored) {
      }
      dockerClient = null;
    }
    if (httpClient != null) {
      try {
        httpClient.close();
      } catch (IOException ignored) {
      }
      httpClient = null;
    }
  }

  private DockerExecSession startExec(ContainerRef container, List<String> command) throws IOException {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("Docker LSP command must not be empty");
    }
    var client = client();
    var stdinForDocker = new PipedInputStream(64 * 1024);
    var stdinFromClient = new PipedOutputStream(stdinForDocker);
    var stdoutForClient = new PipedInputStream(64 * 1024);
    var stdoutFromDocker = new PipedOutputStream(stdoutForClient);

    ExecCreateCmdResponse exec = client.execCreateCmd(container.containerId())
      .withAttachStdin(true)
      .withAttachStdout(true)
      .withAttachStderr(true)
      .withTty(false)
      .withWorkingDir(CONTAINER_ROOT)
      .withCmd(command.toArray(String[]::new))
      .exec();

    ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Frame frame) {
        try {
          if (frame.getStreamType() == StreamType.STDOUT || frame.getStreamType() == StreamType.RAW) {
            stdoutFromDocker.write(frame.getPayload());
            stdoutFromDocker.flush();
          } else if (frame.getStreamType() == StreamType.STDERR) {
            System.err.write(frame.getPayload());
            System.err.flush();
          }
        } catch (IOException error) {
          onError(error);
        }
      }

      @Override
      public void onComplete() {
        closeQuietly(stdoutFromDocker);
        super.onComplete();
      }

      @Override
      public void onError(Throwable throwable) {
        closeQuietly(stdoutFromDocker);
        super.onError(throwable);
      }
    };

    client.execStartCmd(exec.getId())
      .withDetach(false)
      .withTty(false)
      .withStdIn(stdinForDocker)
      .exec(callback);

    return new DockerExecSession(container.key(), stdoutForClient, stdinFromClient, callback, this);
  }

  private synchronized ContainerRef acquireContainer(Path root, String image, LspProfile profile) {
    var normalizedRoot = root.toAbsolutePath().normalize();
    var key = normalizedRoot + "|" + image;
    var existing = containers.get(key);
    if (existing != null && containerRunning(existing.containerId())) {
      existing.increment();
      return existing;
    }

    ensureImage(image, normalizedRoot, profile);
    var name = "lsp-as-mcp-" + digest(key);
    var containerId = findContainerByName(name);
    if (containerId == null) {
      containerId = createContainer(name, normalizedRoot, image);
    }
    startContainerIfNeeded(containerId);
    var ref = new ContainerRef(key, containerId);
    containers.put(key, ref);
    return ref;
  }

  private synchronized void releaseContainer(String key) {
    var ref = containers.get(key);
    if (ref == null || ref.decrement() > 0) {
      return;
    }
    containers.remove(key);
    if (!envFlag("LSP_DOCKER_KEEP_CONTAINERS", false)) {
      stopAndRemove(ref);
    }
  }

  private boolean containerRunning(String containerId) {
    try {
      return Boolean.TRUE.equals(client().inspectContainerCmd(containerId).exec().getState().getRunning());
    } catch (NotFoundException error) {
      return false;
    }
  }

  private void ensureImage(String image, Path root, LspProfile profile) {
    try {
      client().inspectImageCmd(image).exec();
      return;
    } catch (NotFoundException ignored) {
    }
    if (!envFlag("LSP_DOCKER_AUTOBUILD", true)) {
      throw new IllegalStateException("Docker image not found: " + image + ". Enable LSP_DOCKER_AUTOBUILD or build a compatible project sandbox image.");
    }
    var projectDockerfile = root.resolve(".lsp-as-mcp").resolve("Dockerfile").toAbsolutePath().normalize();
    var dockerfile = sandboxDockerfile(root);
    if (!Files.isRegularFile(dockerfile)) {
      throw new IllegalStateException("Docker image " + image + " is missing and no sandbox Dockerfile was found");
    }
    var context = dockerfile.equals(projectDockerfile) ? root : Path.of(".").toAbsolutePath().normalize();
    client().buildImageCmd(context.toFile())
      .withDockerfile(dockerfile.toFile())
      .withTags(java.util.Set.of(image))
      .withBuildArg("LSP_LANGUAGE", profile.language())
      .withBuildArg("JAVA_VERSION", env("LSP_DOCKER_JAVA_VERSION", "21"))
      .withBuildArg("KOTLIN_JAVA_VERSION", env("LSP_DOCKER_KOTLIN_JAVA_VERSION", "25"))
      .withBuildArg("NODE_VERSION", env("LSP_DOCKER_NODE_VERSION", "24.12.0"))
      .exec(new BuildImageResultCallback() {
        @Override
        public void onNext(BuildResponseItem item) {
          if (item.getStream() != null) {
            System.err.print(item.getStream());
          }
          super.onNext(item);
        }
      })
      .awaitImageId();
  }

  private String findContainerByName(String name) {
    var containers = client().listContainersCmd()
      .withShowAll(true)
      .withNameFilter(List.of(name))
      .exec();
    if (containers.isEmpty()) {
      return null;
    }
    return containers.getFirst().getId();
  }

  private String createContainer(String name, Path root, String image) {
    try {
      CreateContainerResponse response = client().createContainerCmd(image)
        .withName(name)
        .withWorkingDir(CONTAINER_ROOT)
        .withCmd("sleep", "infinity")
        .withLabels(Map.of(LABEL, "true", LABEL + ".root", root.toString()))
        .withHostConfig(HostConfig.newHostConfig().withBinds(new Bind(root.toString(), new Volume(CONTAINER_ROOT), AccessMode.rw)))
        .exec();
      return response.getId();
    } catch (ConflictException error) {
      var existing = findContainerByName(name);
      if (existing != null) {
        return existing;
      }
      throw error;
    }
  }

  private void startContainerIfNeeded(String containerId) {
    if (containerRunning(containerId)) {
      return;
    }
    client().startContainerCmd(containerId).exec();
  }

  private void stopAndRemove(ContainerRef ref) {
    try {
      if (containerRunning(ref.containerId())) {
        client().stopContainerCmd(ref.containerId()).withTimeout(2).exec();
      }
    } catch (RuntimeException ignored) {
    }
    try {
      client().removeContainerCmd(ref.containerId()).withForce(true).exec();
    } catch (RuntimeException ignored) {
    }
  }

  private synchronized DockerClient client() {
    if (dockerClient != null) {
      return dockerClient;
    }
    var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    httpClient = new ApacheDockerHttpClient.Builder()
      .dockerHost(config.getDockerHost())
      .sslConfig(config.getSSLConfig())
      .connectionTimeout(Duration.ofSeconds(5))
      .responseTimeout(Duration.ZERO)
      .build();
    dockerClient = DockerClientImpl.getInstance(config, httpClient);
    dockerClient.pingCmd().exec();
    return dockerClient;
  }

  private static String digest(String value) {
    try {
      var bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes, 0, 8);
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private static Path sandboxDockerfile(Path root) {
    var projectDockerfile = root.resolve(".lsp-as-mcp").resolve("Dockerfile").toAbsolutePath().normalize();
    if (Files.isRegularFile(projectDockerfile)) {
      return projectDockerfile;
    }
    return Path.of("Dockerfile.lsp-tools").toAbsolutePath().normalize();
  }

  private static String dockerfileFingerprint(Path root) {
    var dockerfile = sandboxDockerfile(root);
    try {
      return Files.isRegularFile(dockerfile) ? digest(Files.readAllBytes(dockerfile)) : "missing";
    } catch (IOException error) {
      return "unreadable";
    }
  }

  private static String buildVariant(LspProfile profile) {
    return switch (profile.language()) {
      case "java" -> "jdk" + env("LSP_DOCKER_JAVA_VERSION", "21");
      case "kotlin" -> "jdk" + env("LSP_DOCKER_KOTLIN_JAVA_VERSION", "25");
      case "typescript", "javascript", "json", "yaml", "bash", "dockerfile", "python", "php" ->
        "node" + env("LSP_DOCKER_NODE_VERSION", "24.12.0");
      default -> "default";
    };
  }

  private static String safeTagPart(String value) {
    var normalized = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
    return normalized.isBlank() ? "lsp" : normalized;
  }

  private static String env(String name, String defaultValue) {
    var value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static boolean envFlag(String name, boolean defaultValue) {
    var value = System.getenv(name);
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "1", "true", "yes", "on" -> true;
      case "0", "false", "no", "off" -> false;
      default -> defaultValue;
    };
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception ignored) {
    }
  }

  private static final class ContainerRef {
    private final String key;
    private final String containerId;
    private int references = 1;

    private ContainerRef(String key, String containerId) {
      this.key = key;
      this.containerId = containerId;
    }

    String key() {
      return key;
    }

    String containerId() {
      return containerId;
    }

    int increment() {
      references += 1;
      return references;
    }

    int decrement() {
      references -= 1;
      return references;
    }
  }

  record DockerExecSession(
    String containerKey,
    PipedInputStream input,
    PipedOutputStream output,
    ResultCallback.Adapter<Frame> callback,
    DockerSupport owner
  ) implements AutoCloseable {
    @Override
    public void close() {
      closeQuietly(output);
      closeQuietly(input);
      closeQuietly(callback);
      owner.releaseContainer(containerKey);
    }
  }
}
