package io.github.dsh.management;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.util.*;

/** 分页资源和最小可读范围：Executor 不能借共享 Thread 读取别人的 Ticket/Decision。 */
public final class ResourceService {
  private final Engine engine;
  private final SqliteStore store;
  private final VersionedSettings settings;
  private final PromptService prompts;
  private final ManagementHost host;
  private final ManagementViews views;
  private final Map<String, String> cache =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            protected boolean removeEldestEntry(Map.Entry<String, String> e) {
              return size() > 256;
            }
          });

  public ResourceService(
      Engine engine,
      SqliteStore store,
      VersionedSettings settings,
      PromptService prompts,
      ManagementHost host) {
    this.engine = engine;
    this.store = store;
    this.settings = settings;
    this.prompts = prompts;
    this.host = host;
    this.views = new ManagementViews(engine, store, settings, host);
  }

  public static int defaultLimit(String resource) {
    return Set.of("tickets", "triggers", "threads", "decisions", "prompts", "models")
                .contains(resource)
            || resource.startsWith("thread/")
            || resource.startsWith("models/")
            || resource.matches("ticket/[^/]+/(assignments|inputs)")
        ? 20
        : 4000;
  }

  public JsonNode read(String resource, int offset, int limit, String ticketId) {
    if (offset < 0 || limit < 1 || limit > 8000)
      throw new ApiException(400, "Invalid resource page");
    String name =
        resource
            .replaceFirst("^event/", "trigger/")
            .replaceFirst("^task/", "ticket/")
            .replaceFirst("^observation/", "decision/");
    String session = ticketId == null ? host.masterId() : sessionId(views.ticket(ticketId));
    int count = Math.min(limit, 50);
    switch (name) {
      case "tickets":
        main(ticketId);
        return ManagementViews.page(
            engine.tickets().stream().map(t -> (JsonNode) views.ticketLine(t)).toList(),
            offset,
            count);
      case "triggers":
        main(ticketId);
        return ManagementViews.page(
            views.triggers().stream().map(t -> (JsonNode) views.triggerView(t)).toList(),
            offset,
            count);
      case "threads":
        main(ticketId);
        return views.threads("subscribed", offset, count);
      case "decisions":
        main(ticketId);
        return views.decisions("", false, offset, count);
      case "settings":
        main(ticketId);
        return views.settings();
      case "prompts":
        main(ticketId);
        ObjectNode view = prompts.view(null, offset);
        view.set(
            "default_changed",
            node(
                prompts.defaultChanged(prompts.current().path("config")).stream()
                    .map(c -> c.path("part").asText())
                    .toList()));
        return view;
      case "models":
        main(ticketId);
        return host.models();
    }
    if (name.startsWith("thread/")) {
      String key = name.substring(7);
      ThreadState thread = views.thread(key);
      if (ticketId != null && !follows(views.ticket(ticketId), thread))
        throw new ApiException(403, "Unknown or inaccessible thread");
      List<JsonNode> rows =
          views.triggers().stream()
              .filter(t -> t.threads().stream().anyMatch(ref -> ref.key().equals(key)))
              .map(
                  t ->
                      object(
                          "trigger_id",
                          t.id(),
                          "actor",
                          t.actor(),
                          "at",
                          t.at(),
                          "preview",
                          t.text().substring(0, Math.min(180, t.text().length())),
                          "chars",
                          t.text().length(),
                          "read",
                          "trigger/" + t.id()))
              .map(n -> (JsonNode) n)
              .toList();
      ObjectNode out = views.threadLine(thread);
      if (ticketId != null) {
        out.remove("subscriptions");
        out.remove("tickets");
        out.remove("evaluating");
        out.remove("pending");
      } else
        out.set(
            "tickets",
            node(
                thread.subscriptions.stream()
                    .map(Subscription::ticketId)
                    .distinct()
                    .map(engine::ticket)
                    .filter(Objects::nonNull)
                    .map(views::ticketLine)
                    .toList()));
      out.setAll(ManagementViews.page(rows, offset, count));
      return out;
    }
    if (name.startsWith("trigger/")) {
      Trigger trigger = views.trigger(name.substring(8));
      if (ticketId != null && !readable(views.ticket(ticketId), trigger))
        throw new ApiException(403, "Trigger has not reached this ticket");
      ObjectNode out = views.triggerView(trigger);
      out.remove("text");
      if (ticketId != null) {
        out.remove("decisions");
        Disposition d = engine.triggerDisposition(trigger.id());
        out.set(
            "disposition",
            object(
                "owners",
                d.owners.contains(ticketId) ? List.of(ticketId) : List.of(),
                "acknowledged",
                d.acked.contains(ticketId)));
      }
      out.setAll(textPage(name, trigger.text(), offset, limit));
      return out;
    }
    if (name.startsWith("decision/")) {
      main(ticketId);
      String id = name.substring(9);
      return engine.decisions().stream()
          .filter(d -> d.id().equals(id))
          .map(views::decision)
          .findFirst()
          .orElseThrow(() -> new ApiException(404, "Decision not found"));
    }
    if (name.startsWith("settings/")) {
      main(ticketId);
      return textPage(name, settings.block(name.substring(9)).toString(), offset, limit);
    }
    if (name.startsWith("prompts/")) {
      String revision = name.substring(8);
      JsonNode found;
      if (revision.equals("defaults")) {
        main(ticketId);
        found =
            object(
                "revision",
                0,
                "config",
                prompts.defaults(),
                "parts",
                prompts.view(null, 0).path("parts"));
      } else {
        int number;
        if (revision.equals("current"))
          number = ticketId == null ? prompts.revision() : prompts.boundRevision(session);
        else
          try {
            number = Integer.parseInt(revision);
          } catch (NumberFormatException e) {
            throw new ApiException(404, "Unknown prompt revision");
          }
        if (ticketId != null && !prompts.mayRead(session, number))
          throw new ApiException(403, "Unknown or inaccessible prompt version");
        found = prompts.version(number);
      }
      return textPage(name, found.toString(), offset, limit);
    }
    if (name.startsWith("prompt-preview/")) {
      main(ticketId);
      String text = cache.get(session + ":" + name);
      if (text == null) throw new ApiException(404, "Unknown preview for this session");
      return textPage(name, text, offset, limit);
    }
    if (name.startsWith("models/")) {
      main(ticketId);
      String provider = name.substring(7);
      for (JsonNode group : host.models().path("groups"))
        if (group.path("id").asText().equals(provider)) {
          List<JsonNode> models = new ArrayList<>();
          group.path("models").forEach(models::add);
          return ManagementViews.page(models, offset, count);
        }
      throw new ApiException(404, "Unknown model provider");
    }
    if (name.contains("/") && settings.sourceKind(name.substring(0, name.indexOf('/'))) != null) {
      host.authorizeSourceRead(name, ticketId);
      String key = session + ":" + name;
      if (offset == 0) {
        JsonNode source = host.readSource(name, ticketId);
        if (source.isObject() && source.path("dataUrl").isTextual()) return image(source);
        cache.put(key, source.toString());
      }
      String text = cache.get(key);
      if (text == null)
        throw new ApiException(400, "Read this source resource from offset 0 first");
      return textPage(name, text, offset, limit);
    }
    if (name.startsWith("ticket/")) return ticketResource(name, offset, limit, ticketId);
    throw new ApiException(404, "Unknown context resource");
  }

  private JsonNode ticketResource(String name, int offset, int limit, String workerTicket) {
    String[] parts = name.split("/", -1);
    if (parts.length < 2 || parts.length > 4)
      throw new ApiException(404, "Unknown ticket resource");
    String id = parts[1];
    if (workerTicket != null && !workerTicket.equals(id))
      throw new ApiException(403, "Ticket does not belong to this worker");
    Ticket ticket = views.ticket(id);
    if (parts.length == 2) {
      ObjectNode detail = views.ticketDetail(id);
      if (workerTicket != null) {
        detail.remove("decision");
        detail.remove("origin");
        detail.remove("environment");
        for (JsonNode row : detail.path("threads")) {
          if (row.isObject()) {
            ((ObjectNode) row).remove("subscriptions");
            ((ObjectNode) row).remove("tickets");
            ((ObjectNode) row).remove("evaluating");
            ((ObjectNode) row).remove("pending");
          }
        }
      }
      return detail;
    }
    String section = parts[2], part = parts.length == 4 ? parts[3] : null;
    if (section.equals("assignments") || section.equals("inputs")) {
      if (part != null) throw new ApiException(404, "Unknown ticket resource");
      List<JsonNode> items = new ArrayList<>();
      if (section.equals("assignments")) ticket.assignments.forEach(a -> items.add(node(a)));
      else ticket.inputs.forEach(i -> items.add(node(i)));
      Collections.reverse(items);
      return ManagementViews.page(items, offset, Math.min(limit, 50));
    }
    String text;
    switch (section) {
      case "assignment":
        text = ticket.mandate == null ? "" : ticket.mandate.scope();
        break;
      case "result":
        text =
            ticket.latestAssignment() == null || ticket.latestAssignment().report == null
                ? ""
                : ticket.latestAssignment().report.summary();
        break;
      case "report":
        text =
            ticket.assignments.stream()
                .filter(a -> a.id.equals(part) && a.report != null)
                .map(a -> a.report.summary())
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "Unknown report"));
        break;
      case "input":
        text =
            ticket.inputs.stream()
                .filter(i -> i.id.equals(part))
                .map(i -> i.text == null ? "" : i.text)
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "Unknown input"));
        break;
      case "transcript":
        text = host.transcript(id).toString();
        break;
      case "message":
        JsonNode transcript = host.transcript(id);
        JsonNode events =
            transcript.isArray()
                ? transcript
                : transcript.has("events")
                    ? transcript.path("events")
                    : transcript.path("messages");
        int index;
        try {
          index = Integer.parseInt(part);
        } catch (Exception e) {
          throw new ApiException(400, "Invalid message index");
        }
        if (index < 0 || index >= events.size())
          throw new ApiException(404, "Unknown transcript entry");
        text = events.get(index).toString();
        break;
      default:
        throw new ApiException(404, "Unknown ticket resource");
    }
    if (part != null && !Set.of("report", "input", "message").contains(section))
      throw new ApiException(404, "Unexpected resource suffix");
    return textPage(name, text, offset, limit);
  }

  private static JsonNode image(JsonNode source) {
    String url = source.path("dataUrl").asText(),
        type = source.path("mediaType").asText(source.path("mimeType").asText());
    if (!Set.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(type)
        || !url.startsWith("data:" + type + ";base64,")
        || url.length() > 32 * 1024 * 1024)
      throw new ApiException(400, "Invalid source image resource");
    try {
      byte[] bytes = Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
      if (bytes.length == 0 || bytes.length > 20 * 1024 * 1024)
        throw new ApiException(400, "Source image size is invalid");
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "Invalid source image encoding");
    }
    return source;
  }

  /** 主会话预览仅返回引用，正文走相同会话的有界资源分页。 */
  public String savePreviewText(String role, String text) {
    if (!Set.of("orchestrator", "worker").contains(role))
      throw new IllegalArgumentException("Unknown prompt role");
    String name = "prompt-preview/" + UUID.randomUUID();
    cache.put(host.masterId() + ":" + name, text);
    return name;
  }

  public String savePreview(JsonNode config) {
    String name = "prompt-preview/" + UUID.randomUUID();
    cache.put(host.masterId() + ":" + name, prompts.preview(config).toString());
    return name;
  }

  public boolean readable(Ticket ticket, Trigger trigger) {
    if (ticket.originTriggerId.equals(trigger.id())
        || ticket.inputs.stream().anyMatch(i -> trigger.id().equals(i.triggerId))) return true;
    return trigger.threads().stream()
        .anyMatch(
            ref ->
                engine.threads().stream()
                    .anyMatch(th -> th.key.equals(ref.key()) && follows(ticket, th)));
  }

  private boolean follows(Ticket ticket, ThreadState thread) {
    return thread.subscriptions.stream().anyMatch(s -> s.ticketId().equals(ticket.id))
        || ticket.follows.stream()
            .anyMatch(f -> node(f).path("thread").asText().equals(thread.key));
  }

  private static void main(String ticket) {
    if (ticket != null) throw new ApiException(403, "This resource belongs to the main Session");
  }

  private static String sessionId(Ticket ticket) {
    return ticket.sessionId == null ? "worker:" + ticket.id : ticket.sessionId;
  }

  public static ObjectNode textPage(String resource, String text, int offset, int limit) {
    int from = Math.min(offset, text.length()),
        to = Math.min(from + Math.min(limit, 8000), text.length());
    return object(
        "resource",
        resource,
        "offset",
        from,
        "text",
        text.substring(from, to),
        "chars",
        text.length(),
        "next_offset",
        to < text.length() ? to : null);
  }
}
