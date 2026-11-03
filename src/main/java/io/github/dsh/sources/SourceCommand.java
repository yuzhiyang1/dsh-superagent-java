package io.github.dsh.sources;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 仅执行操作者配置的来源 CLI，不经过 shell；输出/时间有界，错误不回显凭据。 */
final class SourceCommand {
  record Result(int exit, byte[] output, byte[] error) {}

  static Result run(
      String executable, List<String> args, Path directory, int maxBytes, Duration timeout)
      throws Exception {
    List<String> command = new ArrayList<>();
    command.add(executable);
    command.addAll(args);
    ProcessBuilder builder = new ProcessBuilder(command);
    if (directory != null) builder.directory(directory.toFile());
    builder.environment().put("LARKSUITE_CLI_NO_UPDATE_NOTIFIER", "1");
    builder.environment().put("LARKSUITE_CLI_NO_SKILLS_NOTIFIER", "1");
    Process process = builder.start();
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<byte[]> stdout = threads.submit(() -> bounded(process.getInputStream(), maxBytes));
      Future<byte[]> stderr = threads.submit(() -> bounded(process.getErrorStream(), 65536));
      try {
        process.getOutputStream().close();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          throw new IOException("Source CLI timed out");
        }
        return new Result(
            process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS));
      } finally {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.getInputStream().close();
        process.getErrorStream().close();
        stdout.cancel(true);
        stderr.cancel(true);
      }
    }
  }

  private static byte[] bounded(InputStream input, int maximum) throws IOException {
    try (input) {
      byte[] data = input.readNBytes(maximum + 1);
      if (data.length > maximum) throw new IOException("Source CLI output exceeds limit");
      return data;
    }
  }

  static String text(byte[] data) {
    return new String(data, StandardCharsets.UTF_8);
  }
}
