package io.github.dsh.router;

import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.execution.*;
import io.github.dsh.management.*;
import io.github.dsh.models.ModelProviders;
import java.net.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RouterSessionTest {
  @TempDir Path root;

  @Test
  void interruptedCallsAreRecordedUnknownNeverReplayedAndFinishedEffectIsIdempotent()
      throws Exception {
    List<JsonNode> seen = new ArrayList<>();
    try (Fixture f =
        new Fixture(
            request -> {
              seen.add(request);
              return object("role", "assistant", "content", "Inspected state.");
            })) {
      RouterSession.State state = new RouterSession.State();
      state.model = "initial";
      state.messages.add(
          MAPPER.convertValue(
              object(
                  "role",
                  "assistant",
                  "content",
                  null,
                  "tool_calls",
                  List.of(
                      object(
                          "id",
                          "dangerous",
                          "type",
                          "function",
                          "function",
                          object(
                              "name",
                              "bash",
                              "arguments",
                              "{\"command\":\"touch must-not-run\"}")))),
              Map.class));
      f.store.putState("router", "orchestrator", state);
      f.router.handle(effect("one", EffectKind.RELAY, Map.of("text", "continue")));
      f.router.handle(effect("one", EffectKind.RELAY, Map.of("text", "continue")));
      assertEquals(1, seen.size());
      assertTrue(seen.getFirst().path("messages").toString().contains("outcome\\\":\\\"unknown"));
      assertFalse(Files.exists(root.resolve("main/must-not-run")));
    }
  }

  @Test
  void imageToolProducesVisionContentAndModelSelectionRemainsSessionBound() throws Exception {
    List<JsonNode> seen = new ArrayList<>();
    AtomicInteger step = new AtomicInteger();
    try (Fixture f =
        new Fixture(
            request -> {
              seen.add(request);
              return step.getAndIncrement() == 0
                  ? object(
                      "role",
                      "assistant",
                      "content",
                      null,
                      "tool_calls",
                      List.of(
                          object(
                              "id",
                              "image1",
                              "type",
                              "function",
                              "function",
                              object(
                                  "name", "read_image", "arguments", "{\"path\":\"pixel.png\"}"))))
                  : object("role", "assistant", "content", "Image inspected");
            })) {
      Files.createDirectories(root.resolve("main"));
      Files.write(
          root.resolve("main/pixel.png"),
          Base64.getDecoder()
              .decode(
                  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jgXsAAAAASUVORK5CYII="));
      f.router.handle(effect("one", EffectKind.RELAY, Map.of("text", "inspect")));
      f.selection.set(new ModelProviders.Selection("default", "different-model", "low"));
      f.router.handle(effect("two", EffectKind.RELAY, Map.of("text", "continue")));
      assertEquals(3, seen.size());
      assertTrue(seen.get(1).path("messages").toString().contains("image_url"));
      for (JsonNode req : seen) {
        assertEquals("initial", req.path("model").asText());
        assertEquals("high", req.path("reasoning_effort").asText());
      }
    }
  }

  @Test
  void newNoticeRevisionDeliveredOnceWithoutModelWake() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (Fixture f =
        new Fixture(
            request -> {
              calls.incrementAndGet();
              return object("role", "assistant", "content", "unused");
            })) {
      f.router.handle(
          effect("settings:executor", EffectKind.NOTICE, Map.of("text", "first", "revision", "1")));
      f.router.handle(
          effect("settings:executor", EffectKind.NOTICE, Map.of("text", "first", "revision", "1")));
      f.router.handle(
          effect(
              "settings:executor", EffectKind.NOTICE, Map.of("text", "second", "revision", "2")));
      assertEquals(0, calls.get());
      RouterSession.State state = (RouterSession.State) f.router.view();
      assertEquals(2, state.messages.size());
    }
  }

  static Effect effect(String key, EffectKind kind, Map<String, String> payload) {
    return new Effect(key, kind, key, payload, 0, 0, null, 0);
  }

  class Fixture implements AutoCloseable {
    final SqliteStore store;
    final RouterSession router;
    final HttpServer server;
    final RouterWorkTools work;
    final AtomicReference<ModelProviders.Selection> selection =
        new AtomicReference<>(new ModelProviders.Selection("default", "initial", "high"));

    Fixture(java.util.function.Function<JsonNode, JsonNode> responder) throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/chat/completions",
          exchange -> {
            JsonNode req = MAPPER.readTree(exchange.getRequestBody());
            byte[] bytes =
                MAPPER.writeValueAsBytes(
                    object("choices", List.of(object("message", responder.apply(req)))));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      store = new SqliteStore(root.resolve("db.sqlite"));
      Engine engine = new Engine(store, Clock.systemUTC());
      ManagementHost host = new ManagementHost() {};
      VersionedSettings settings = new VersionedSettings(store, engine, root, host);
      settings.applyToEngine();
      PromptService prompts = new PromptService(store, settings, host);
      ResourceService resources = new ResourceService(engine, store, settings, prompts, host);
      URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
      router =
          new RouterSession(
              engine,
              store,
              prompts,
              host,
              new MainTools(engine, store, settings, prompts, resources, host),
              base,
              "key",
              () -> "initial",
              10);
      router.setModelProviders(
          new ModelProviders(base, "KEY", "initial", Map.of("KEY", "fixture")), selection::get);
      work =
          new RouterWorkTools(
              ExecutionSettings.builder(root, root.resolve("work")).build(), root.resolve("main"));
      router.setWorkTools(work);
    }

    public void close() throws Exception {
      work.close();
      store.close();
      server.stop(0);
    }
  }
}
