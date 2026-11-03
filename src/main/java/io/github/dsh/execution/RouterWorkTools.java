package io.github.dsh.execution;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.adapters.HttpJsonTransport;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/** 主会话只能用小型工具集合；不允许 sleep 或长时间占住路由 turn。 */
public final class RouterWorkTools implements AutoCloseable {
  private static final Pattern SLEEP =
      Pattern.compile(
          "(?:^|[;&|({!`\\n"
              + "]|\\b(?:do|then|else|elif|if|while|until|time|exec|nohup|command|env)\\s)\\s*(?:\\S*/)?sleep\\b");
  private volatile ExecutionSettings settings;
  private final Path workspace;
  private final ShellJobs jobs;
  private volatile WorkerEnvironment environment;
  private final Queue<String> notices = new ConcurrentLinkedQueue<>();

  public RouterWorkTools(ExecutionSettings settings, Path workspace) throws IOException {
    this.settings = settings;
    this.workspace = workspace.toAbsolutePath().normalize();
    WorkspaceFiles.rejectSymlinks(this.workspace);
    Files.createDirectories(this.workspace);
    environment = new WorkerEnvironment(settings);
    jobs =
        new ShellJobs(
            settings.stateDirectory.resolve("router"),
            settings.maxOutputBytes,
            settings.shutdownTimeout,
            j -> notices.add("Main session job " + j.id + " " + j.status + ":\n" + j.output));
  }

  public synchronized void reconfigure(ExecutionSettings next) throws IOException {
    if (!next.stateDirectory.equals(settings.stateDirectory))
      throw new IOException("State directory cannot change inside live router tools");
    WorkerEnvironment nextEnvironment = new WorkerEnvironment(next);
    environment = nextEnvironment;
    settings = next;
  }

  public Object invoke(String name, Map<String, Object> arguments) throws Exception {
    JsonNode a = HttpJsonTransport.JSON.valueToTree(arguments);
    WorkspaceFiles files = new WorkspaceFiles(workspace, settings.maxFileBytes);
    return switch (name) {
      case "read", "read_file" ->
          files.read(
              required(a, "path"),
              a.path("offset").asInt(0),
              Math.min(settings.maxOutputBytes, a.path("limit").asInt(settings.maxOutputBytes)));
      case "read_image" -> {
        Path p = files.resolve(required(a, "path"));
        if (Files.size(p) > settings.maxFileBytes)
          throw new IOException("Image exceeds file limit");
        String mime = Files.probeContentType(p);
        if (mime == null || !mime.startsWith("image/")) throw new IOException("Image required");
        yield Map.of(
            "mime_type", mime, "base64", Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
      }
      case "bash" -> {
        if (!settings.allowShell) throw new IOException("Router shell disabled");
        String command = required(a, "command");
        if (SLEEP.matcher(command).find()
            || a.path("timeoutMs").asLong(0) > 30000
            || a.path("timeout_ms").asLong(0) > 30000)
          throw new IOException(
              "End the turn instead of waiting: results arrive as notices. Router shell cannot run"
                  + " sleep or request waits over 30 seconds.");
        for (String blocked : settings.blockedCommands)
          if (Pattern.compile("(^|[;&|\\s])" + Pattern.quote(blocked) + "(?=$|[\\s;&|])")
              .matcher(command)
              .find()) throw new IOException("Blocked command: " + blocked);
        ShellJobs.Job j =
            jobs.start(
                "main",
                "main",
                command,
                workspace,
                environment.process(settings.environment),
                null);
        boolean background = a.path("run_in_background").asBoolean(false);
        if (!background)
          jobs.waitFor(
              "main", j.id, Duration.ofMillis(Math.min(5000, a.path("timeoutMs").asLong(5000))));
        if (background || j.running()) jobs.notifyWhenComplete("main", j.id);
        yield jobs.view(j);
      }
      case "job_list" -> jobs.list("main").stream().map(jobs::view).toList();
      case "job_output" -> {
        if (a.path("timeout_ms").asLong(0) > 5000)
          throw new IOException("Router job wait cannot exceed five seconds");
        yield jobs.view(
            jobs.waitFor(
                "main",
                required(a, "job_id"),
                a.path("wait").asBoolean(false)
                    ? Duration.ofMillis(Math.max(0, a.path("timeout_ms").asLong(5000)))
                    : Duration.ZERO));
      }
      case "job_kill" -> {
        jobs.kill("main", required(a, "job_id"), "Router cancelled job");
        yield jobs.view(jobs.get("main", a.path("job_id").asText()));
      }
      default -> throw new IOException("Work tool not allowed for router: " + name);
    };
  }

  public List<String> pollNotices() {
    List<String> out = new ArrayList<>();
    String n;
    while ((n = notices.poll()) != null) out.add(n);
    return out;
  }

  public List<Map<String, Object>> schemas() {
    List<Map<String, Object>> out = new ArrayList<>();
    out.add(
        schema(
            "read",
            "Read a file from main workspace",
            Map.of("path", type("string"), "offset", type("integer"), "limit", type("integer")),
            "path"));
    out.add(
        schema(
            "read_image",
            "Read an image from main workspace",
            Map.of("path", type("string")),
            "path"));
    if (settings.allowShell)
      out.add(
          schema(
              "bash",
              "Run bounded router shell; do not sleep or wait for future events",
              Map.of(
                  "command",
                  type("string"),
                  "timeoutMs",
                  type("integer"),
                  "run_in_background",
                  type("boolean")),
              "command"));
    out.add(schema("job_list", "List main-session jobs", Map.of()));
    out.add(
        schema(
            "job_output",
            "Read a job; wait at most five seconds",
            Map.of(
                "job_id", type("string"), "wait", type("boolean"), "timeout_ms", type("integer")),
            "job_id"));
    out.add(
        schema(
            "job_kill",
            "Stop an owned main-session job",
            Map.of("job_id", type("string")),
            "job_id"));
    return out;
  }

  private static Map<String, Object> type(String t) {
    return Map.of("type", t);
  }

  private static Map<String, Object> schema(
      String n, String d, Map<String, Object> props, String... required) {
    return Map.of(
        "type",
        "function",
        "function",
        Map.of(
            "name",
            n,
            "description",
            d,
            "parameters",
            Map.of(
                "type",
                "object",
                "properties",
                props,
                "required",
                List.of(required),
                "additionalProperties",
                false)));
  }

  private static String required(JsonNode a, String key) throws IOException {
    if (!a.path(key).isTextual() || a.path(key).asText().isBlank())
      throw new IOException(key + " required");
    return a.path(key).asText();
  }

  @Override
  public void close() throws Exception {
    jobs.close();
  }
}
