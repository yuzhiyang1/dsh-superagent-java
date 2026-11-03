package io.github.dsh.models;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.adapters.HttpJsonTransport;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/** 模型供应商注册表：端点和密钥环境变量名可配置，实际密钥只驻留内存，不写入会话。 */
public final class ModelProviders {
  public record Selection(String provider, String model, String reasoningEffort) {
    public Selection {
      if (provider == null || provider.isBlank() || model == null || model.isBlank())
        throw new IllegalArgumentException("Provider and model are required");
      reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
    }
  }

  private record Provider(
      String id,
      String name,
      URI endpoint,
      String keyEnvironment,
      boolean authRequired,
      boolean credentialPresent,
      HttpJsonTransport client) {}

  private final Map<String, Provider> providers = new LinkedHashMap<>();
  private final Selection fallback;

  public ModelProviders(
      URI defaultBase, String keyEnvironment, String defaultModel, Map<String, String> environment)
      throws IOException {
    add(
        "default",
        "Configured model provider",
        defaultBase,
        keyEnvironment,
        environment,
        !loopback(defaultBase));
    String configured = environment.get("DSH_MODEL_PROVIDERS");
    if (configured != null && !configured.isBlank()) {
      JsonNode values = HttpJsonTransport.JSON.readTree(configured);
      if (!values.isArray() || values.size() > 16)
        throw new IllegalArgumentException(
            "DSH_MODEL_PROVIDERS must be an array of at most 16 providers");
      for (JsonNode node : values) {
        if (!node.isObject()) throw new IllegalArgumentException("Provider must be an object");
        node.fieldNames()
            .forEachRemaining(
                field -> {
                  if (!Set.of("id", "name", "baseUrl", "apiKeyEnv", "authRequired").contains(field))
                    throw new IllegalArgumentException(
                        "Unsupported provider field; credentials must use apiKeyEnv: " + field);
                });
        String id = required(node, "id"),
            base = required(node, "baseUrl"),
            key = required(node, "apiKeyEnv");
        if (id.equals("default")) providers.remove("default");
        URI endpoint = URI.create(base);
        if (node.has("authRequired") && !node.path("authRequired").isBoolean())
          throw new IllegalArgumentException("authRequired must be boolean");
        add(
            id,
            node.path("name").asText(id),
            endpoint,
            key,
            environment,
            node.path("authRequired").asBoolean(!loopback(endpoint)));
      }
    }
    fallback =
        new Selection(
            "default",
            defaultModel == null || defaultModel.isBlank() ? "unconfigured" : defaultModel,
            "");
  }

  private void add(
      String id,
      String name,
      URI endpoint,
      String key,
      Map<String, String> env,
      boolean authRequired) {
    if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}") || !key.matches("[A-Za-z_][A-Za-z0-9_]*"))
      throw new IllegalArgumentException(
          "Invalid provider identifier or credential environment variable");
    if (providers.containsKey(id))
      throw new IllegalArgumentException("Duplicate provider id: " + id);
    providers.put(
        id,
        new Provider(
            id,
            name,
            endpoint,
            key,
            authRequired,
            !env.getOrDefault(key, "").isBlank(),
            new HttpJsonTransport(endpoint, env.getOrDefault(key, ""))));
  }

  public Selection selection(JsonNode value) {
    if (value == null || value.isMissingNode() || value.isNull()) return fallback;
    if (value.isTextual()) return new Selection(fallback.provider(), value.asText(), "");
    String provider = required(value, "provider"), model = required(value, "model");
    if (!providers.containsKey(provider))
      throw new IllegalArgumentException("Unknown configured model provider: " + provider);
    String reasoning = value.path("reasoningEffort").asText("");
    if (reasoning.length() > 100 || reasoning.contains("\n"))
      throw new IllegalArgumentException("Invalid reasoning effort");
    return new Selection(provider, model, reasoning);
  }

  public Selection fallback() {
    return fallback;
  }

  /** 返回可向操作员显示的配置问题；永远不返回凭据值。null 表示所选路由配置完整。 */
  public String readyProblem(Selection selection) {
    Provider p = providers.get(selection.provider());
    if (p == null) return "Unknown model provider: " + selection.provider();
    if (selection.model().isBlank() || selection.model().equals("unconfigured"))
      return "Select a model for provider " + p.id;
    if (p.authRequired && !p.credentialPresent)
      return "Set credential environment variable " + p.keyEnvironment + " for provider " + p.id;
    return null;
  }

  public boolean credentialsPresent(Selection selection) {
    Provider p = providers.get(selection.provider());
    return p != null && (!p.authRequired || p.credentialPresent);
  }

  private static boolean loopback(URI uri) {
    return Set.of("localhost", "127.0.0.1", "[::1]").contains(Objects.toString(uri.getHost(), ""));
  }

  public JsonNode complete(
      Selection selection, List<Map<String, Object>> messages, List<Map<String, Object>> tools)
      throws IOException, InterruptedException {
    Provider provider = providers.get(selection.provider());
    if (provider == null)
      throw new IOException("Selected model provider is not configured: " + selection.provider());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", selection.model());
    body.put("messages", messages);
    body.put("stream", false);
    if (tools != null && !tools.isEmpty()) {
      body.put("tools", tools);
      body.put("tool_choice", "auto");
    }
    if (!selection.reasoningEffort().isBlank())
      body.put("reasoning_effort", selection.reasoningEffort());
    return provider.client.request("POST", "chat/completions", body, Map.of());
  }

  /** 目录来自每个供应商的真实 GET /models；失败单独显示，绝不把配置值伪装成发现的模型。 */
  public Map<String, Object> catalog() {
    List<Map<String, Object>> groups = new ArrayList<>(), failures = new ArrayList<>();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Map<Provider, Future<List<Map<String, Object>>>> requests = new LinkedHashMap<>();
      for (Provider provider : providers.values())
        requests.put(provider, executor.submit(() -> readModels(provider)));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      for (var entry : requests.entrySet())
        try {
          List<Map<String, Object>> models =
              entry.getValue().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
          groups.add(
              Map.of("id", entry.getKey().id, "name", entry.getKey().name, "models", models));
        } catch (Exception e) {
          entry.getValue().cancel(true);
          Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
          failures.add(
              Map.of(
                  "provider",
                  entry.getKey().id,
                  "error",
                  cause instanceof HttpJsonTransport.ApiException
                      ? cause.getMessage()
                      : cause.getClass().getSimpleName()));
          if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    } finally {
      executor.shutdownNow();
    }
    return Map.of("default", fallback, "groups", groups, "failures", failures);
  }

  private List<Map<String, Object>> readModels(Provider provider)
      throws IOException, InterruptedException {
    JsonNode response = provider.client.request("GET", "models", null, Map.of());
    JsonNode data = response.path("data");
    if (!data.isArray()) throw new IOException("Provider model catalog has no data array");
    List<Map<String, Object>> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (JsonNode model : data) {
      String id = model.path("id").asText("");
      if (id.isBlank() || !seen.add(id)) continue;
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", id);
      entry.put("name", model.path("name").asText(id));
      if (model.path("reasoningEfforts").isArray())
        entry.put(
            "reasoningEfforts",
            HttpJsonTransport.JSON.convertValue(model.path("reasoningEfforts"), List.class));
      out.add(entry);
    }
    out.sort(Comparator.comparing(m -> m.get("id").toString()));
    return out;
  }

  private static String required(JsonNode node, String name) {
    if (!node.path(name).isTextual() || node.path(name).asText().isBlank())
      throw new IllegalArgumentException(name + " is required");
    return node.path(name).asText();
  }
}
