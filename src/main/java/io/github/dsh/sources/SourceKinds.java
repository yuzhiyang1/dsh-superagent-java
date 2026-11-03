package io.github.dsh.sources;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 安装时注册 Java 来源插件；实例热重载不会改变已声明的种类/健康命名空间。 */
public final class SourceKinds {
  private static final Set<String> RESERVED =
      Set.of(
          "runtime",
          "reply",
          "worker",
          "executor",
          "main",
          "eval",
          "setup",
          "configuration",
          "launch",
          "input",
          "outbox",
          "relay",
          "notice",
          "stop",
          "cleanup",
          "ticket",
          "trigger",
          "thread",
          "decision",
          "settings",
          "prompts",
          "models",
          "prompt-preview",
          "event",
          "task",
          "observation");
  private final Map<String, SourceKind> kinds = new LinkedHashMap<>();
  private final Map<String, String> healthOwners = new HashMap<>();

  public static SourceKinds builtins() {
    return new SourceKinds()
        .register(BuiltinSourceKinds.github())
        .register(BuiltinSourceKinds.feishu());
  }

  /** 只加载部署者已放入 classpath 的 Java SPI；不下载、不执行远程插件。 */
  public SourceKinds discover(ClassLoader loader) {
    ServiceLoader.load(SourceKind.class, loader).forEach(this::register);
    return this;
  }

  public static SourceKinds installed() {
    return builtins().discover(Thread.currentThread().getContextClassLoader());
  }

  public synchronized SourceKinds register(SourceKind kind) {
    Objects.requireNonNull(kind);
    String id = kind.id();
    if (id == null || !id.matches("[a-z][a-z0-9-]*") || RESERVED.contains(id))
      throw new IllegalArgumentException("Invalid or reserved source kind");
    if (kinds.containsKey(id)) throw new IllegalArgumentException("Duplicate source kind: " + id);
    for (String name : kind.health()) {
      if (name == null
          || name.isBlank()
          || RESERVED.contains(name)
          || name.startsWith("sync:")
          || healthOwners.containsKey(name))
        throw new IllegalArgumentException("Health name already claimed or reserved: " + name);
    }
    kind.validateConfig(kind.defaultConfig());
    kind.validatePolicy(kind.defaultPolicy());
    kinds.put(id, kind);
    kind.health().forEach(name -> healthOwners.put(name, id));
    return this;
  }

  public synchronized SourceKind unregister(String id) {
    SourceKind removed = kinds.remove(id);
    if (removed != null) healthOwners.entrySet().removeIf(entry -> entry.getValue().equals(id));
    return removed;
  }

  public synchronized SourceKind get(String id) {
    return kinds.get(id);
  }

  public synchronized Set<String> ids() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(kinds.keySet()));
  }

  public synchronized List<SourceKind> all() {
    return List.copyOf(kinds.values());
  }

  public JsonNode descriptors(JsonNode configured, JsonNode setup) {
    var values = array(List.of());
    for (SourceKind kind : all()) {
      JsonNode value = configured.path(kind.id());
      values.add(
          kind.descriptor(
              value.has("config") ? value.path("config") : kind.defaultConfig(), setup));
    }
    return values;
  }
}
