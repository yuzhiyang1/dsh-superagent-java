package io.github.dsh.models;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.dsh.adapters.HttpJsonTransport;
import io.github.dsh.core.Model.*;
import io.github.dsh.execution.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelProvidersTest {
  @TempDir Path temp;

  @Test
  void realDiscoveryRoutingReasoningAndSessionPinning() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<JsonNode> calls = new CopyOnWriteArrayList<>();
    List<String> auth = new CopyOnWriteArrayList<>();
    server.createContext(
        "/secondary/models",
        e -> {
          byte[] b =
              "{\"data\":[{\"id\":\"model-b\",\"name\":\"Provider model B\",\"reasoningEfforts\":[\"low\",\"high\"]}]}"
                  .getBytes();
          e.sendResponseHeaders(200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.createContext(
        "/models",
        e -> {
          e.sendResponseHeaders(503, -1);
          e.close();
        });
    server.createContext(
        "/secondary/chat/completions",
        e -> {
          calls.add(HttpJsonTransport.JSON.readTree(e.getRequestBody()));
          auth.add(e.getRequestHeaders().getFirst("Authorization"));
          byte[] b =
              "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Waiting\"}}]}"
                  .getBytes();
          e.sendResponseHeaders(200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.start();
    try {
      URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
      String config =
          "[{\"id\":\"second\",\"name\":\"Second provider\",\"baseUrl\":\""
              + base
              + "secondary/\",\"apiKeyEnv\":\"SECOND_KEY\"}]";
      var providers =
          new ModelProviders(
              base,
              "FIRST_KEY",
              "model-a",
              Map.of("DSH_MODEL_PROVIDERS", config, "SECOND_KEY", "fixture-secret"));
      var catalog = providers.catalog();
      assertTrue(catalog.get("groups").toString().contains("model-b"));
      assertTrue(catalog.get("failures").toString().contains("default"));
      assertFalse(catalog.toString().contains("fixture-secret"));
      var selection =
          providers.selection(
              HttpJsonTransport.JSON.readTree(
                  "{\"provider\":\"second\",\"model\":\"model-b\",\"reasoningEffort\":\"high\"}"));
      Ticket t = new Ticket();
      t.id = "provider-test";
      t.title = "Inspect";
      t.mandate =
          new Mandate(
              "p1", Strength.EXPLICIT, "inspect", "local", "alice", "origin", false, Set.of());
      t.inputs.add(new Input("in", "origin", "Inspect", false));
      var settings = ExecutionSettings.builder(temp.resolve("state"), temp.resolve("work")).build();
      try (var host = new JavaExecutionHost(base, "", "model-a", settings)) {
        host.setModelProviders(providers, selection);
        host.execute(t, new Assignment("a1", t.id, 0));
        host.setModelProviders(
            providers, new ModelProviders.Selection("second", "new-model", "low"));
        host.execute(t, new Assignment("a2", t.id, 0));
        assertEquals("model-b", calls.getLast().path("model").asText());
        assertEquals("high", calls.getLast().path("reasoning_effort").asText());
      }
      try (var restarted = new JavaExecutionHost(base, "", "unused", settings)) {
        restarted.setModelProviders(providers, providers.fallback());
        restarted.execute(t, new Assignment("a3", t.id, 0));
        assertEquals("model-b", calls.getLast().path("model").asText());
        assertEquals("high", calls.getLast().path("reasoning_effort").asText());
      }
      assertTrue(auth.stream().allMatch("Bearer fixture-secret"::equals));
      assertFalse(
          java.nio.file.Files.readString(
                  new SessionStore(settings.stateDirectory.resolve("sessions"))
                      .path("worker-" + t.id))
              .contains("fixture-secret"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void setupChecksOnlySelectedProviderAndSupportsExplicitNoAuth() throws Exception {
    String config =
        "[{\"id\":\"private\",\"baseUrl\":\"https://private.example.invalid/v1\",\"apiKeyEnv\":\"PRIVATE_KEY\"},{\"id\":\"open\",\"baseUrl\":\"https://open.example.invalid/v1\",\"apiKeyEnv\":\"OPTIONAL_KEY\",\"authRequired\":false}]";
    var registry =
        new ModelProviders(
            URI.create("https://default.example.invalid/v1"),
            "DEFAULT_KEY",
            "model",
            Map.of("DSH_MODEL_PROVIDERS", config, "PRIVATE_KEY", "secret"));
    assertNotNull(registry.readyProblem(registry.fallback()));
    assertNull(registry.readyProblem(new ModelProviders.Selection("private", "model", "high")));
    assertNull(registry.readyProblem(new ModelProviders.Selection("open", "model", "")));
  }

  @Test
  void configurationCannotContainInlineCredentials() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelProviders(
                URI.create("https://example.invalid/v1"),
                "KEY",
                "model",
                Map.of("DSH_MODEL_PROVIDERS", "[{\"id\":\"evil\",\"apiKey\":\"secret\"}]")));
  }
}
