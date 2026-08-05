package dev.llmtools.lspmcp;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

@Component
public class CommandAvailability {
  public boolean isAvailable(List<String> command) {
    if (command == null || command.isEmpty() || command.getFirst() == null || command.getFirst().isBlank()) {
      return false;
    }
    return isExecutable(command.getFirst());
  }

  boolean isExecutable(String command) {
    var path = Path.of(command);
    if (path.isAbsolute() || command.contains("/") || command.contains("\\")) {
      return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    var pathEnv = System.getenv("PATH");
    if (pathEnv == null || pathEnv.isBlank()) {
      return false;
    }

    for (var directory : pathEnv.split(java.io.File.pathSeparator)) {
      if (directory.isBlank()) {
        continue;
      }
      for (var candidate : candidateNames(command)) {
        var executable = Path.of(directory, candidate);
        if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
          return true;
        }
      }
    }
    return false;
  }

  private List<String> candidateNames(String command) {
    if (!isWindows() || command.contains(".")) {
      return List.of(command);
    }
    var pathExt = System.getenv("PATHEXT");
    if (pathExt == null || pathExt.isBlank()) {
      pathExt = ".COM;.EXE;.BAT;.CMD";
    }
    return java.util.Arrays.stream(pathExt.split(";"))
      .filter(extension -> !extension.isBlank())
      .map(extension -> command + extension.toLowerCase(Locale.ROOT))
      .collect(java.util.stream.Collectors.toCollection(() -> new java.util.ArrayList<>(List.of(command))));
  }

  private boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
