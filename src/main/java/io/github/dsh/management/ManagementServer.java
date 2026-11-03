package io.github.dsh.management;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import io.github.dsh.Manage;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** JDK 21 自带 HttpServer，绑定 127.0.0.1。API 保留上游路径与防跨站约束。 静态控制台不依赖 Node/React/外部 CDN，所有数据取自相同领域实例。 */
public final class ManagementServer implements AutoCloseable {
  private static final String API = "/orchestrator/api/";
  private final HttpServer server;
  private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
  private final Engine engine;
  private final SqliteStore store;
  private final VersionedSettings settings;
  private final PromptService prompts;
  private final ManagementHost host;
  private final ManagementViews views;
  private final ResourceService resources;

  public ManagementServer(
      Engine engine,
      SqliteStore store,
      VersionedSettings settings,
      PromptService prompts,
      ManagementHost host,
      int port)
      throws IOException {
    this.engine = engine;
    this.store = store;
    this.settings = settings;
    this.prompts = prompts;
    this.host = host;
    this.views = new ManagementViews(engine, store, settings, host);
    this.resources = new ResourceService(engine, store, settings, prompts, host);
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 64);
    server.setExecutor(requests);
    server.createContext("/", this::handle);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public String url() {
    return "http://127.0.0.1:" + port() + "/orchestrator/";
  }

  public ResourceService resources() {
    return resources;
  }

  @Override
  public void close() {
    server.stop(1);
    requests.close();
  }

  private boolean localHost(HttpExchange request) {
    String host = request.getRequestHeaders().getFirst("Host");
    return ("127.0.0.1:" + port()).equals(host) || ("localhost:" + port()).equals(host);
  }

  private boolean sameOrigin(HttpExchange request) {
    String host = request.getRequestHeaders().getFirst("Host"),
        origin = request.getRequestHeaders().getFirst("Origin");
    return request.getRemoteAddress().getAddress().isLoopbackAddress()
        && localHost(request)
        && "1".equals(request.getRequestHeaders().getFirst("x-orchestrator-request"))
        && (origin == null || origin.equals("http://" + host));
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String path = exchange.getRequestURI().getRawPath();
      if (path.startsWith(API)) {
        if (!sameOrigin(exchange))
          throw new ApiException(403, "Same-origin management request required");
        String[] segments =
            Arrays.stream(path.substring(API.length()).split("/", -1))
                .map(ManagementServer::decode)
                .toArray(String[]::new);
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        JsonNode answer = route(exchange, exchange.getRequestMethod(), segments, query);
        sendJson(exchange, 200, answer);
      } else {
        if (!localHost(exchange) || !exchange.getRemoteAddress().getAddress().isLoopbackAddress())
          throw new ApiException(403, "Loopback management request required");
        if (!exchange.getRequestMethod().equals("GET")
            && !exchange.getRequestMethod().equals("HEAD"))
          throw new ApiException(405, "Method not allowed");
        String resource =
            switch (path) {
              case "/", "/orchestrator", "/orchestrator/" -> "web/index.html";
              case "/orchestrator/app.js" -> "web/app.js";
              case "/orchestrator/app.css" -> "web/app.css";
              default -> null;
            };
        if (resource == null) throw new ApiException(404, "Not found");
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
          if (input == null) throw new ApiException(404, "Panel asset not packaged");
          byte[] data = input.readAllBytes();
          Headers h = exchange.getResponseHeaders();
          h.set(
              "Content-Type",
              resource.endsWith(".js")
                  ? "text/javascript; charset=utf-8"
                  : resource.endsWith(".css")
                      ? "text/css; charset=utf-8"
                      : "text/html; charset=utf-8");
          secureHeaders(h);
          h.set(
              "Content-Security-Policy",
              "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src"
                  + " 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
          exchange.sendResponseHeaders(
              200, exchange.getRequestMethod().equals("HEAD") ? -1 : data.length);
          if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(data);
        }
      }
    } catch (Throwable failure) {
      Throwable error = failure;
      while (error.getCause() != null && !(error instanceof ApiException)) error = error.getCause();
      int status =
          error instanceof ApiException a
              ? a.status
              : error instanceof IllegalArgumentException || error instanceof IllegalStateException
                  ? 400
                  : 500;
      ObjectNode payload =
          object(
              "error",
              error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
      if (error instanceof ApiException a) payload.setAll((ObjectNode) node(a.details));
      try {
        sendJson(exchange, status, payload);
      } catch (IOException ignored) {
        /* disconnected client */
      }
    } finally {
      exchange.close();
    }
  }

  private JsonNode route(HttpExchange exchange, String method, String[] p, Map<String, String> q)
      throws IOException {
    String path = String.join("/", p);
    int offset = number(q, "offset", 0, 0, Integer.MAX_VALUE),
        limit = number(q, "limit", 20, 1, 1000);
    if (method.equals("GET")) {
      switch (path) {
        case "state":
          return views.state();
        case "overview":
          return views.overview();
        case "search":
          return views.search(q.getOrDefault("q", ""));
        case "sources":
          return node(views.sources());
        case "settings":
          return views.settings();
        case "threads":
          return views.threads(
              option(q, "filter", "subscribed", "subscribed", "evaluating", "recent", "all"),
              offset,
              limit);
        case "tickets":
          return views.tickets(
              option(q, "state", "open", "open", "person", "working", "idle", "ended"),
              offset,
              limit);
        case "decisions":
          return views.decisions(
              q.getOrDefault("kind", ""),
              q.getOrDefault("window", "").equals("24h"),
              offset,
              Math.min(limit, 200));
        case "policy/history":
        case "settings/history":
          return settings.history(offset, Math.min(limit, 100), q.get("block"));
        case "models":
          return host.models();
        case "prompts":
          return prompts.view(
              q.containsKey("revision") ? requiredNumber(q.get("revision"), "revision") : null,
              offset);
        case "deployment":
          return object("value", settings.machine(), "revision", settings.revision("deployment"));
        case "resources":
          return resources.read(
              q.getOrDefault("resource", "tickets"),
              offset,
              number(q, "limit", 4000, 1, 8000),
              null);
      }
      if (p.length == 2)
        switch (p[0]) {
          case "threads":
            return views.threadDetail(
                p[1], q.get("before"), q.get("around"), number(q, "limit", 50, 1, 100));
          case "tickets":
            return views.ticketDetail(p[1]);
          case "triggers":
            return views.triggerView(views.trigger(p[1]));
          case "settings":
            return settings.block(p[1]);
        }
      if (p.length == 3 && p[0].equals("tickets") && p[2].equals("transcript")) {
        views.ticket(p[1]);
        return host.transcript(p[1]);
      }
    }
    if (method.equals("PUT")) {
      if (path.equals("prompts")) {
        JsonNode body = body(exchange, 4 * settings.promptMaxChars() + 4096);
        Json.keys(body, "", "config", "baseRevision");
        return prompts.save(
            body.get("config"),
            integer(body.get("baseRevision"), "baseRevision", 0, Integer.MAX_VALUE),
            "panel");
      }
      if (p.length == 2 && p[0].equals("settings") || path.equals("deployment")) {
        JsonNode body = body(exchange, 128 * 1024);
        Json.keys(body, "", "value", "revision");
        return settings.save(
            path.equals("deployment") ? "deployment" : p[1],
            body.get("value"),
            integer(body.get("revision"), "revision", 0, Integer.MAX_VALUE),
            "panel");
      }
    }
    if (method.equals("POST")) {
      switch (path) {
        case "executor/pause":
          JsonNode pause = body(exchange, 128 * 1024);
          Json.keys(pause, "", "paused");
          return settings.pause(bool(pause.get("paused"), "paused"), "panel");
        case "setup/check":
          host.checkSetup();
          return object("restarting", true);
        case "prompts/preview":
          return prompts.preview(body(exchange, 4 * settings.promptMaxChars() + 4096));
      }
      if (p.length == 2 && p[0].equals("policy-sync")) {
        if (settings.sourceKind(p[1]) == null)
          throw new ApiException(404, "Source kind not installed");
        int syncRevision = settings.revision("policy");
        JsonNode policy = host.synchronizePolicy(p[1]);
        ObjectNode next = (ObjectNode) settings.value("policy").deepCopy();
        ((ObjectNode) next.path("sources")).set(p[1], policy);
        settings.save("policy", next, syncRevision, "sync");
        return object("policy", policy);
      }
      if (p.length == 3) {
        String id = p[1], action = p[2];
        switch (p[0]) {
          case "sources":
            if (action.equals("labels")) return object("added", host.refreshLabels(id));
            break;
          case "threads":
            if (action.equals("unsubscribe")) {
              views.thread(id);
              int split = id.indexOf(':');
              if (split < 1) throw new ApiException(400, "Invalid thread key");
              boolean removed =
                  engine.unsubscribe(
                      new ThreadRef(id.substring(0, split), id.substring(split + 1)));
              host.requestPass();
              return object("removed", removed, "thread", id, "context_delivery", "pending");
            }
            break;
          case "triggers":
            if (action.equals("requeue")) {
              views.trigger(id);
              boolean queued =
                  engine.triggerDisposition(id).owners.isEmpty()
                      ? engine.reevaluate(id)
                      : engine.sendFollowupToEval(id);
              host.requestPass();
              return object("queued", queued);
            }
            break;
          case "decisions":
            if (action.equals("dismiss")) {
              if (engine.decisions().stream().noneMatch(d -> d.id().equals(id)))
                throw new ApiException(404, "Decision not found");
              engine.dismissDecision(id);
              return object("dismissed", true);
            }
            break;
          case "settings":
            if (action.equals("restore")) {
              JsonNode body = body(exchange, 128 * 1024);
              Json.keys(body, "", "revision", "baseRevision");
              return settings.restore(
                  id,
                  integer(body.get("revision"), "revision", 1, Integer.MAX_VALUE),
                  integer(body.get("baseRevision"), "baseRevision", 0, Integer.MAX_VALUE),
                  "panel");
            }
            break;
          case "tickets":
            views.ticket(id);
            switch (action) {
              case "retry":
                engine.retryTicket(id);
                host.requestPass();
                return object("retried", true);
              case "stop":
                engine.stopTicket(id);
                host.requestPass();
                return object("stopped", true);
              case "answer":
                JsonNode answer = body(exchange, 128 * 1024);
                Json.keys(answer, "", "text");
                Manage.answer(engine, id, string(answer.get("text"), "text", 1, 20000));
                host.requestPass();
                return object("answered", true);
              case "attach":
                JsonNode attach = body(exchange, 128 * 1024);
                Json.keys(attach, "", "triggerId", "note");
                engine.attach(
                    id,
                    string(attach.get("triggerId"), "triggerId", 1, 300),
                    attach.path("note").asText(""));
                host.requestPass();
                return object("attached", true);
              case "subscribe":
                JsonNode follow = body(exchange, 128 * 1024);
                Json.keys(follow, "", "source", "thread", "contextOnly");
                engine.followThread(
                    id,
                    new ThreadRef(
                        string(follow.get("source"), "source", 1, 100),
                        string(follow.get("thread"), "thread", 1, 1000)),
                    !follow.has("contextOnly") || bool(follow.get("contextOnly"), "contextOnly"));
                host.requestPass();
                return object("subscribed", true);
            }
            break;
        }
      }
    }
    throw new ApiException(404, "Unknown operation");
  }

  private static JsonNode body(HttpExchange exchange, int max) throws IOException {
    byte[] data = exchange.getRequestBody().readNBytes(max + 1);
    if (data.length > max) throw new ApiException(400, "Request exceeds " + max + " bytes");
    JsonNode result = parse(new String(data, StandardCharsets.UTF_8));
    if (result == null) throw new ApiException(400, "The request body is not JSON");
    return result;
  }

  private static int number(
      Map<String, String> query, String name, int fallback, int min, int max) {
    try {
      int value = Integer.parseInt(query.getOrDefault(name, Integer.toString(fallback)));
      return value < min ? fallback : Math.min(value, max);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static int requiredNumber(String value, String name) {
    try {
      int n = Integer.parseInt(value);
      if (n < 0) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new ApiException(400, "Invalid " + name);
    }
  }

  private static String option(
      Map<String, String> query, String name, String fallback, String... choices) {
    String value = query.getOrDefault(name, fallback);
    return Set.of(choices).contains(value) ? value : fallback;
  }

  private static String decode(String segment) {
    try {
      return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "A path segment has a malformed escape");
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> result = new LinkedHashMap<>();
    if (raw != null)
      for (String item : raw.split("&")) {
        String[] pair = item.split("=", 2);
        try {
          result.put(
              URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
              pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        } catch (IllegalArgumentException e) {
          throw new ApiException(400, "Malformed query");
        }
      }
    return result;
  }

  private static void secureHeaders(Headers headers) {
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    headers.set("Referrer-Policy", "no-referrer");
    headers.set("X-Frame-Options", "DENY");
  }

  private static void sendJson(HttpExchange exchange, int status, JsonNode value)
      throws IOException {
    byte[] data = MAPPER.writeValueAsBytes(value);
    secureHeaders(exchange.getResponseHeaders());
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, data.length);
    exchange.getResponseBody().write(data);
  }
}
