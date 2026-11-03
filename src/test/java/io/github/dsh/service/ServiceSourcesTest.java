package io.github.dsh.service;

import static io.github.dsh.config.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.dsh.core.Model.*;
import io.github.dsh.sources.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 正式组合根的来源配置、身份验证与隔离；所有外部协议只连接回环fixture。 */
class ServiceSourcesTest {
  @TempDir Path root;
  HttpServer server;
  String base;
  List<String> requests = new CopyOnWriteArrayList<>();
  AtomicBoolean repoAvailable = new AtomicBoolean(true);
  String login = "helper", bot = "ou_bot";

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          requests.add(path);
          int status = 200;
          Object result;
          if (path.equals("/user")) result = Map.of("login", login);
          else if (path.equals("/repos/team/repo")) {
            status = repoAvailable.get() ? 200 : 404;
            result = Map.of("full_name", "team/repo", "permissions", Map.of("push", true));
          } else if (path.equals("/bot/v3/info"))
            result = Map.of("code", 0, "bot", Map.of("open_id", bot, "app_name", "fixture"));
          else if (path.endsWith("/members"))
            result =
                Map.of(
                    "code",
                    0,
                    "data",
                    Map.of(
                        "items",
                        List.of(Map.of("member_id", "ou_alice", "name", "Alice")),
                        "has_more",
                        false));
          else if (path.contains("/resources/")) {
            byte[] image = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
            exchange.sendResponseHeaders(200, image.length);
            exchange.getResponseBody().write(image);
            exchange.close();
            return;
          } else if (path.equals("/im/v1/messages"))
            result =
                Map.of(
                    "code",
                    0,
                    "data",
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "message_id",
                                "om_neighbor",
                                "chat_id",
                                "oc_chat",
                                "msg_type",
                                "text",
                                "create_time",
                                "1793664000000",
                                "sender",
                                Map.of("id", "ou_alice", "sender_type", "user"),
                                "body",
                                Map.of("content", "{\"text\":\"neighbor img_key\"}"))),
                        "has_more",
                        false));
          else if (path.startsWith("/im/v1/messages/")) {
            String id = path.substring(path.lastIndexOf('/') + 1);
            result =
                Map.of(
                    "code",
                    0,
                    "data",
                    Map.of(
                        "items",
                        List.of(
                            Map.of(
                                "message_id",
                                id,
                                "chat_id",
                                id.equals("om_foreign") ? "oc_other" : "oc_chat",
                                "msg_type",
                                "text",
                                "create_time",
                                "1793664000000",
                                "sender",
                                Map.of("id", "ou_alice", "sender_type", "user"),
                                "body",
                                Map.of("content", "{\"text\":\"hello img_key\"}")))));
          } else result = Map.of("code", 0, "data", Map.of("items", List.of(), "has_more", false));
          byte[] bytes = MAPPER.writeValueAsBytes(result);
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  OrchestratorService service(boolean checks) throws Exception {
    var service =
        new OrchestratorService(
            new ServiceOptions(
                root,
                0,
                URI.create(base),
                "TEST_KEY",
                "fixture",
                checks,
                Map.of("TEST_KEY", "fixture")));
    ObjectNode gh = (ObjectNode) service.settings.value("source:github");
    gh.put("enabled", false);
    save(service, "source:github", gh);
    return service;
  }

  void save(OrchestratorService service, String block, JsonNode value) {
    service.settings.save(block, value, service.settings.revision(block), "panel");
  }

  ObjectNode github(OrchestratorService service) {
    ObjectNode value = (ObjectNode) service.settings.value("source:github");
    value.put("enabled", true);
    ObjectNode c = (ObjectNode) value.path("config");
    c.put("repository", "team/repo");
    c.put("botLogin", "helper");
    c.put("apiBase", base);
    return value;
  }

  ObjectNode feishu(OrchestratorService service) {
    ObjectNode value = (ObjectNode) service.settings.value("source:feishu");
    value.put("enabled", true);
    ObjectNode c = (ObjectNode) value.path("config");
    c.put("botId", "ou_bot");
    c.put("appId", "cli_app");
    c.put("apiBase", base);
    c.put("directMessages", false);
    c.set("chats", array(List.of(object("chatId", "oc_chat", "name", "Chat", "listen", "all"))));
    return value;
  }

  @Test
  void sourceClearRemovesOldInstanceAndDisablesScopeImmediately() throws Exception {
    try (var service = service(false)) {
      save(service, "source:github", github(service));
      assertNotNull(service.sources.get("github"));
      ObjectNode cleared = github(service);
      ((ObjectNode) cleared.path("config")).put("repository", "");
      save(service, "source:github", cleared);
      assertNull(service.sources.get("github"));
      assertFalse(service.store.getState("sourceScope", "github", SourceScope.class).enabled());
    }
  }

  @Test
  void feishuScopeRemovalIsSynchronousAndPolicyOnlyChangeKeepsLiveInstance() throws Exception {
    try (var service = service(false)) {
      save(service, "source:feishu", feishu(service));
      SourceScope initial = service.store.getState("sourceScope", "feishu", SourceScope.class);
      assertTrue(initial.scopes().contains("oc_chat"));
      SourceInstance before = service.sources.get("feishu");
      ObjectNode policy = (ObjectNode) service.settings.value("policy");
      save(service, "policy", policy);
      assertSame(before, service.sources.get("feishu"));
      ObjectNode changed = feishu(service);
      ((ObjectNode) changed.path("config")).set("chats", array(List.of()));
      save(service, "source:feishu", changed);
      SourceScope scope = service.store.getState("sourceScope", "feishu", SourceScope.class);
      assertFalse(scope.scopes().isEmpty());
      assertFalse(scope.scopes().contains("oc_chat"));
    }
  }

  @Test
  void setupChecksAuthenticatedGithubAndRepositoryOnEveryRecheck() throws Exception {
    try (var service = service(true)) {
      save(service, "source:github", github(service));
      assertNull(service.setup().get("problem").textValue());
      assertTrue(requests.contains("/user"));
      assertTrue(requests.contains("/repos/team/repo"));
      repoAvailable.set(false);
      service.checkSetup();
      assertFalse(service.setup().path("problem").isNull());
    }
  }

  @Test
  void mismatchedSourceAccountCannotCommitSettings() throws Exception {
    try (var service = service(true)) {
      int revision = service.settings.revision("source:github");
      login = "other";
      assertThrows(RuntimeException.class, () -> save(service, "source:github", github(service)));
      assertEquals(revision, service.settings.revision("source:github"));
      bot = "ou_other";
      int feishuRevision = service.settings.revision("source:feishu");
      assertThrows(RuntimeException.class, () -> save(service, "source:feishu", feishu(service)));
      assertEquals(feishuRevision, service.settings.revision("source:feishu"));
    }
  }

  @Test
  void membershipSyncUsesPolicySyncChatAndDoesNotWritePolicyDuringRead() throws Exception {
    try (var service = service(false)) {
      ObjectNode cfg = feishu(service);
      cfg.put("enabled", false);
      save(service, "source:feishu", cfg);
      ObjectNode policy = (ObjectNode) service.settings.value("policy");
      ObjectNode fs = object("allowed", List.of());
      ((ObjectNode) policy.path("sources")).set("feishu", fs);
      fs.put("syncChat", "oc_sync");
      save(service, "policy", policy);
      int revision = service.settings.revision("policy");
      assertEquals(
          "oc_sync", ((FeishuSource) service.sources.get("feishu")).configuration().syncChat());
      JsonNode sync = service.synchronizePolicy("feishu");
      assertEquals("ou_alice", sync.path("allowed").get(0).path("id").asText());
      assertEquals(revision, service.settings.revision("policy"));
      assertTrue(requests.contains("/im/v1/chats/oc_sync/members"));
    }
  }

  @Test
  void sourceOriginalReadsRequireReachedTriggerForWorker() throws Exception {
    try (var service = service(false)) {
      save(service, "source:feishu", feishu(service));
      service.engine.setPolicy(
          new Policy(
              "fixture",
              AcceptMode.OBSERVE,
              Map.of("feishu", new SourcePolicy(Set.of("ou_alice"), Set.of(), Map.of())),
              Set.of()));
      Trigger origin =
          new Trigger(
              "origin",
              "feishu",
              List.of(new ThreadRef("feishu", "oc_chat:om_a")),
              "ou_alice",
              true,
              true,
              true,
              "oc_chat",
              Instant.now().toString(),
              "work",
              new Channel("feishu", "oc_chat:om_a", Map.of()),
              Map.of("chatId", "oc_chat", "messageId", "om_a"),
              null,
              null);
      service.engine.receive(origin);
      String ticket =
          service
              .engine
              .decide(
                  service.engine.evaluateDue().getFirst().id(),
                  Outcome.accept("work", "scope", Set.of()))
              .ticketId();
      assertDoesNotThrow(
          () -> service.authorizeSourceRead("feishu/context/origin/message", ticket));
      assertThrows(
          RuntimeException.class,
          () -> service.authorizeSourceRead("feishu/context/foreign/message", ticket));
      assertThrows(
          RuntimeException.class,
          () -> service.authorizeSourceRead("feishu/foreign/message", ticket));
      assertThrows(
          RuntimeException.class,
          () ->
              service.authorizeSourceRead(
                  "feishu/context/origin/image?message_id=om_unknown&image_id=img_key", ticket));
      assertEquals(
          "om_a",
          service.readSource("feishu/context/origin/message").path("message").path("id").asText());
      // 已验证的主会话把已读取邻居ID交给内部来源端口；worker授权仍单独拒绝任意猜ID。
      JsonNode image =
          service.readSource("feishu/context/origin/image?message_id=om_neighbor&image_id=img_key");
      assertEquals("om_neighbor", image.path("messageId").asText());
      assertTrue(image.path("dataUrl").asText().startsWith("data:image/png;base64,"));
      assertThrows(
          RuntimeException.class,
          () ->
              service.readSource(
                  "feishu/context/origin/image?message_id=om_foreign&image_id=img_key"));
      service.readSource("feishu/context/origin/around", ticket);
      assertDoesNotThrow(
          () ->
              service.authorizeSourceRead(
                  "feishu/context/origin/image?message_id=om_neighbor&image_id=img_key", ticket));
      assertEquals(
          "om_neighbor",
          service
              .readSource(
                  "feishu/context/origin/image?message_id=om_neighbor&image_id=img_key", ticket)
              .path("messageId")
              .asText());
      assertThrows(
          RuntimeException.class,
          () ->
              service.readSource(
                  "feishu/context/origin/image?message_id=om_unknown&image_id=img_key", ticket));
    }
  }

  @Test
  void configuredGhWrapperWorksWithoutGithubToken() throws Exception {
    Path wrapper = root.resolve("gh-wrapper");
    Files.writeString(
        wrapper,
        "#!/bin/sh\n"
            + "case \"$2\" in\n"
            + "user) printf 'HTTP/2.0 200 OK\\n"
            + "\\n"
            + "{\"login\":\"helper\"}' ;;\n"
            + "*) printf 'HTTP/2.0 200 OK\\n"
            + "\\n"
            + "{\"full_name\":\"team/repo\",\"permissions\":{\"push\":true}}' ;;\n"
            + "esac\n");
    Files.setPosixFilePermissions(
        wrapper, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    try (var service = service(true)) {
      ObjectNode value = github(service);
      ((ObjectNode) value.path("config")).put("cli", wrapper.toString());
      save(service, "source:github", value);
      assertEquals(
          "helper", service.setup().path("accounts").path("github").path("login").asText());
      assertTrue(requests.isEmpty());
    }
  }

  @Test
  void configuredFeishuWrapperMustBeBotOnlyAndMatchesApplication() throws Exception {
    Path mode = root.resolve("strict-mode.txt"), wrapper = root.resolve("lark-wrapper");
    Files.writeString(mode, "bot");
    Files.writeString(
        wrapper,
        "#!/bin/sh\n"
            + "case \"$1\" in\n"
            + "auth) printf '%s'"
            + " '{\"appId\":\"cli_app\",\"identities\":{\"bot\":{\"status\":\"ready\"}}}' ;;\n"
            + "config) printf 'strict-mode: '; cat '"
            + mode
            + "' ;;\n"
            + "api) case \"$*\" in *ndjson*) printf '%s'"
            + " '{\"bot\":{\"open_id\":\"ou_bot\",\"app_name\":\"fixture\"}}' ;; *) exit 1 ;; esac"
            + " ;;\n"
            + "esac\n");
    Files.setPosixFilePermissions(
        wrapper, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    try (var service = service(true)) {
      ObjectNode candidate = feishu(service);
      ((ObjectNode) candidate.path("config")).put("cli", wrapper.toString());
      assertDoesNotThrow(() -> service.validateSettings("source:feishu", candidate));
      Files.writeString(mode, "user");
      assertThrows(
          RuntimeException.class, () -> service.validateSettings("source:feishu", candidate));
      Files.writeString(mode, "bot");
      ((ObjectNode) candidate.path("config")).put("appId", "cli_other");
      assertThrows(
          RuntimeException.class, () -> service.validateSettings("source:feishu", candidate));
      assertTrue(requests.isEmpty());
    }
  }
}
