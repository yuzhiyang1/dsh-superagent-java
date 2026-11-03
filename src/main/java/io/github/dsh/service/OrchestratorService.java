package io.github.dsh.service;

import static io.github.dsh.config.Json.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.dsh.adapters.*;
import io.github.dsh.config.*;
import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import io.github.dsh.execution.*;
import io.github.dsh.management.*;
import io.github.dsh.router.*;
import io.github.dsh.runtime.*;
import io.github.dsh.sources.*;
import java.net.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** 常驻应用组合根：数据库、来源、主会话、worker、Web 管理与周期 pass 共用真实状态。 */
public final class OrchestratorService implements ManagementHost, AutoCloseable {
  private final io.github.dsh.models.ModelProviders modelProviders;
  private final SourceKinds sourceKinds;
  private final ServiceOptions options;
  private final Clock clock;
  private final StateDirectoryLock lock;
  public final SqliteStore store;
  public final Engine engine;
  public final VersionedSettings settings;
  public final PromptService prompts;
  public final SourceRegistry sources = new SourceRegistry();
  private final SourceContext sourceContext;
  private final HealthBoard board;
  private final CodingExecutor executor;
  private final OrchestratorRuntime runtime;
  private final RouterSession router;
  private final RouterWorkTools routerWork;
  private final ManagementServer server;
  private final DomainNoticeRelay notices;
  private final PassCoordinator passes;
  private final SourceScheduler sourceScheduler;
  private final java.util.concurrent.ExecutorService checks =
      Executors.newVirtualThreadPerTaskExecutor();
  private volatile JsonNode setup =
      object("problem", null, "feishuProblem", null, "accounts", object());
  private final Map<String, Set<String>> sourceHealth = new ConcurrentHashMap<>();
  private final Set<String> sourceReady = ConcurrentHashMap.newKeySet();
  private final Set<String> pendingReload = ConcurrentHashMap.newKeySet();
  private volatile boolean started, closed;
  private final Set<String> syncing = ConcurrentHashMap.newKeySet();

  public OrchestratorService(ServiceOptions options) throws Exception {
    this(options, Clock.systemUTC());
  }

  public OrchestratorService(ServiceOptions options, Clock clock) throws Exception {
    this(options, clock, SourceKinds.installed());
  }

  public OrchestratorService(ServiceOptions options, Clock clock, SourceKinds sourceKinds)
      throws Exception {
    this.sourceKinds = Objects.requireNonNull(sourceKinds);
    this.options = options;
    this.modelProviders =
        new io.github.dsh.models.ModelProviders(
            options.modelBase(),
            options.modelKeyEnvironment(),
            options.defaultModel(),
            options.environment());
    this.clock = clock;
    lock = new StateDirectoryLock(options.stateDirectory());
    store = new SqliteStore(options.stateDirectory().resolve("orchestrator.sqlite"));
    engine = new Engine(store, clock);
    sourceContext = new SourceContext(engine, store, clock);
    board = new HealthBoard(clock);
    settings = new VersionedSettings(store, engine, options.stateDirectory(), this, sourceKinds);
    prompts = new PromptService(store, settings, this);
    settings.applyToEngine();
    configureSources();
    executor =
        new CodingExecutor(
            options.modelBase(),
            options.secret(options.modelKeyEnvironment()),
            workerModel(),
            executionSettings());
    executor.setModelProviders(modelProviders, selection("executor"));
    server = new ManagementServer(engine, store, settings, prompts, this, options.port());
    ResourceService resources = server.resources();
    executor.setContext(new WorkerDomainTools(engine, resources, sources, sourceContext));
    runtime = new OrchestratorRuntime(engine, store, executor, this::send, clock);
    runtime.setMaxWorkers(() -> settings.value("executor").path("maxWorkers").asInt(3));
    MainTools tools = new MainTools(engine, store, settings, prompts, resources, this);
    router =
        new RouterSession(
            engine,
            store,
            prompts,
            this,
            tools,
            options.modelBase(),
            options.secret(options.modelKeyEnvironment()),
            this::routerModel,
            100);
    router.setModelProviders(modelProviders, () -> selection("eval"));
    routerWork =
        new RouterWorkTools(executionSettings(), options.stateDirectory().resolve("router-work"));
    router.setWorkTools(routerWork);
    runtime.setMainHandler(router);
    notices = new DomainNoticeRelay(engine, store, clock);
    passes = new PassCoordinator(this::pass, board);
    sourceScheduler = new SourceScheduler(sources, sourceContext, board, this::requestPass, clock);
    sourceScheduler.contextFor(this::sourceContext);
    sourceScheduler.readyWhen(
        source ->
            !Set.of("github", "feishu").contains(source.id()) || sourceReady.contains(source.id()));
  }

  private SourceContext sourceContext(SourceInstance source) {
    SourceKind owner = sourceKinds.get(source.id());
    return sourceContext.withHealthReporter(
        (name, error, detail) -> {
          if (sources.get(source.id()) != source || sourceKinds.get(source.id()) != owner) return;
          if (owner == null || !owner.health().contains(name))
            throw new IllegalArgumentException(
                "Source may only report its declared health entries");
          if (error) board.error(name, detail);
          else board.ready(name);
        });
  }

  public void start() throws Exception {
    if (started) return;
    started = true;
    store.recoverEffects();
    // 仅忙碌派工恢复；waiting/ready/blocked/failed 且没有更新输入的派工保留。
    for (Ticket ticket :
        engine.tickets().stream().sorted(Comparator.comparingLong(t -> t.createdAt)).toList()) {
      Assignment assignment = ticket.latestAssignment();
      if (ticket.status != TicketStatus.OPEN
          || assignment == null
          || assignment.status != AssignmentStatus.RUNNING) continue;
      boolean interrupted =
          assignment.report == null
              || assignment.report.kind() == ReportKind.PROGRESS
              || ticket.inputs.stream().anyMatch(i -> i.at > assignment.reportAt);
      if (interrupted) engine.assignmentInterrupted(ticket.id, assignment.id);
    }
    server.start();
    checkSetup();
    passes.start();
  }

  public String url() {
    return server.url();
  }

  public int port() {
    return server.port();
  }

  private void pass() {
    sourceScheduler.tick();
    if (configured()) {
      engine.evaluateDue();
      notices.advance();
      runtime.pump();
      for (String text : routerWork.pollNotices())
        store.enqueue(
            EffectKind.RELAY,
            "router-job:" + UUID.randomUUID(),
            Map.of("text", text),
            clock.millis());
    }
  }

  private boolean configured() {
    return setup.path("problem").isNull();
  }

  private String send(OutboxMessage message) throws Exception {
    if (Set.of("github", "feishu").contains(message.channel().source())
        && !sourceReady.contains(message.channel().source()))
      throw new OrchestratorRuntime.Later("source setup not ready");
    SourceKind kind = sourceKinds.get(message.channel().source());
    if (kind != null) {
      JsonNode config = sourceSettings(kind.id());
      if (!config.path("enabled").asBoolean())
        throw new OrchestratorRuntime.Later("source disabled");
      if (!kind.channelAllowed(message.channel().address(), config.path("config")))
        throw new OrchestratorRuntime.ChannelGone("source channel no longer listened");
    }
    SourceInstance.Delivery result = sources.send(sourceContext, message);
    return switch (result.status()) {
      case "sent" -> result.receipt();
      case "gone" -> throw new OrchestratorRuntime.ChannelGone("channelGone");
      default -> throw new OrchestratorRuntime.Later("source off or not available");
    };
  }

  /** 仅为损坏配置保留管理入口；checkSetup 会拒绝该配置并停止派工，绝不调用回退供应商。 */
  private io.github.dsh.models.ModelProviders.Selection selection(String role) {
    try {
      return modelProviders.selection(settings.value(role).path("model"));
    } catch (IllegalArgumentException invalid) {
      return modelProviders.fallback();
    }
  }

  private JsonNode sourceSettings(String id) {
    return settings.sourceIds().contains(id)
        ? settings.value("source:" + id)
        : object("enabled", false, "config", object());
  }

  private String workerModel() {
    return model(settings.value("executor").path("model"));
  }

  private String routerModel() {
    return model(settings.value("eval").path("model"));
  }

  private String model(JsonNode role) {
    if (role.isTextual() && !role.asText().isBlank()) return role.asText();
    if (role.isObject() && !role.path("model").asText().isBlank())
      return role.path("model").asText();
    return options.defaultModel().isBlank() ? "unconfigured" : options.defaultModel();
  }

  private ExecutionSettings executionSettings() {
    JsonNode config = settings.value("executor");
    String directory = config.path("directory").asText();
    Path root = directory.isBlank() ? options.stateDirectory().resolve("work") : Path.of(directory);
    ExecutionSettings.Builder builder =
        ExecutionSettings.builder(options.stateDirectory(), root)
            .prepareCommand(config.path("prepare").asText(""))
            .contextWindowTokens(
                Integer.parseInt(
                    options.environment().getOrDefault("DSH_CONTEXT_WINDOW_TOKENS", "128000")))
            .allowWrites(config.path("allowWrites").asBoolean())
            .allowShell(config.path("allowShell").asBoolean())
            .allowNetwork(config.path("allowNetwork").asBoolean())
            .allowSubagents(config.path("allowSubagents").asBoolean())
            .allowPublish(config.path("allowPublish").asBoolean())
            .blockedCommands(strings(config.path("blockedCommands")))
            .environment(environmentMap(null))
            .prompts(
                (ticket, role, env) -> {
                  String session =
                      ticket.sessionId == null ? "worker:" + ticket.id : ticket.sessionId;
                  int revision = prompts.revision();
                  prompts.recordUse(session, revision);
                  List<Map<String, String>> vars =
                      env.entrySet().stream()
                          .map(
                              e ->
                                  Map.of(
                                      "key",
                                      e.getKey(),
                                      "value",
                                      e.getValue(),
                                      "description",
                                      e.getKey()))
                          .toList();
                  return new ExecutionSettings.Prompt(
                      prompts.compose(role.equals("subagent") ? "worker" : role, vars),
                      revision,
                      prompts.skills());
                })
            .web(
                new ConfiguredWebTools(
                    options
                        .environment()
                        .getOrDefault(
                            "DSH_WEB_SEARCH_URL", "https://html.duckduckgo.com/html/?q=")));
    String git = config.path("gitRepository").asText();
    if (!git.isBlank()) builder.gitRepository(Path.of(git));
    JsonNode github = sourceSettings("github").path("config");
    String repository = github.path("repository").asText();
    builder.pullRequests(
        new ExecutionSettings.PullRequests() {
          private GitHubPullRequests current() {
            if (!(sources.get("github") instanceof GitHubSource source))
              throw new IllegalStateException("GitHub source not configured");
            return new GitHubPullRequests(source);
          }

          public ExecutionSettings.PullRequest create(
              String title, String body, String head, String base, boolean draft) throws Exception {
            return current().create(title, body, head, base, draft);
          }

          public ExecutionSettings.PullRequest read(int number) throws Exception {
            return current().read(number);
          }
        });
    return builder.build();
  }

  public synchronized void reloadSources() throws Exception {
    configureSources();
    checkSetup();
    if (sourceScheduler != null) sourceKinds.ids().forEach(sourceScheduler::refresh);
  }

  private synchronized void configureSources() throws Exception {
    for (SourceInstance previous : sources.instances())
      if (sourceKinds.get(previous.id()) == null) {
        sources.remove(previous.id());
        sourceReady.remove(previous.id());
        Set<String> removed = sourceHealth.remove(previous.id());
        if (removed != null) removed.forEach(board::remove);
        engine.configureSourceScope(previous.id(), new SourceScope(false, Set.of(), null));
        engine.registerSourceScope(previous.id(), null);
      }

    for (String id : sourceKinds.ids()) {
      JsonNode value = settings.value("source:" + id);
      SourceInstance source =
          SourceFactory.create(id, value, options, settings.value("policy"), sourceKinds);
      SourceInstance previous = sources.get(id);
      if (source != null
          && previous != null
          && previous.enabled() == source.enabled()
          && previous.kind().equals(source.kind())
          && Objects.equals(previous.configuration(), source.configuration())) {
        source.close();
        source = previous;
      } else if (source != null) sources.upsert(source);
      else sources.remove(id);
      SourceKind kind = sourceKinds.get(id);
      sourceHealth.put(id, kind.health());
      engine.registerSourceScope(
          id,
          trigger -> {
            SourceKind.Scope scope = kind.scope(trigger, value.path("config"));
            return new ScopeDecision(
                value.path("enabled").asBoolean() && scope.listened(),
                scope.observed(),
                scope.row(),
                scope.reason());
          });
      if (source != null) {
        source.installScope(sourceContext(source));
        for (String health : kind.health())
          if (!board.view().containsKey(health)) board.starting(health);
      } else
        engine.configureSourceScope(
            id,
            new SourceScope(
                false,
                Set.of(),
                value.path("config").path(id.equals("github") ? "botLogin" : "botId").asText()));
    }
  }

  @Override
  public String masterId() {
    return "orchestrator-"
        + Integer.toUnsignedString(options.stateDirectory().toString().hashCode(), 36);
  }

  @Override
  public int activeCount() {
    return runtime == null ? 0 : ((Number) runtime.load().get("running")).intValue();
  }

  @Override
  public JsonNode setup() {
    return setup.deepCopy();
  }

  @Override
  public JsonNode health() {
    ObjectNode all = MAPPER.valueToTree(board.view());
    if (runtime != null) all.setAll((ObjectNode) MAPPER.valueToTree(runtime.health().view()));
    return all;
  }

  @Override
  public JsonNode models() {
    return MAPPER.valueToTree(modelProviders.catalog());
  }

  @Override
  public JsonNode channelLink(Channel channel) {
    SourceKind kind = sourceKinds.get(channel.source());
    return kind == null
        ? object()
        : kind.channelLink(channel.address(), sourceSettings(kind.id()).path("config"));
  }

  @Override
  public JsonNode sourceLinks() {
    ObjectNode links = object();
    for (Ticket ticket : engine.tickets())
      for (Channel channel : ticket.channels) {
        String url = channelLink(channel).path("url").asText();
        if (!url.isBlank())
          links.put(
              channel.thread().startsWith(channel.source() + ":")
                  ? channel.thread()
                  : channel.source() + ":" + channel.thread(),
              url);
      }
    return links;
  }

  @Override
  public JsonNode findSource(String query) {
    return MAPPER.valueToTree(sources.find(query));
  }

  @Override
  public JsonNode sourceDetails(String id) {
    SourceInstance source = sources.get(id);
    JsonNode health = health().path(id);
    return object(
        "installed",
        sourceKinds.get(id) != null,
        "enabled",
        source != null && source.enabled(),
        "health",
        health,
        "state",
        source == null
            ? "setup"
            : !source.enabled() ? "off" : health.path("state").asText("starting"),
        "configuration",
        source == null ? null : source.configuration());
  }

  @Override
  public List<Map<String, String>> environment(String ticketId) {
    return environmentMap(ticketId).entrySet().stream()
        .map(e -> Map.of("key", e.getKey(), "value", e.getValue(), "description", e.getKey()))
        .toList();
  }

  private Map<String, String> environmentMap(String ticketId) {
    Map<String, String> env = new LinkedHashMap<>();
    JsonNode github = sourceSettings("github").path("config"),
        feishu = sourceSettings("feishu").path("config");
    env.put("DSH_SUPERAGENT_GITHUB_REPOSITORY", github.path("repository").asText());
    env.put("DSH_SUPERAGENT_GITHUB_ACCOUNT", github.path("botLogin").asText());
    env.put("DSH_SUPERAGENT_GITHUB_COMMIT_EMAIL", github.path("commitEmail").asText());
    env.put("DSH_SUPERAGENT_FEISHU_BOT", feishu.path("botId").asText());
    if (ticketId != null) {
      Ticket ticket = engine.ticket(ticketId);
      if (ticket != null) {
        Trigger trigger = engine.trigger(ticket.originTriggerId);
        env.put("DSH_SUPERAGENT_TICKET", ticket.id);
        env.put("DSH_SUPERAGENT_NAME", ticket.shortName == null ? ticket.id : ticket.shortName);
        env.put("DSH_SUPERAGENT_DIR", Objects.toString(ticket.directory, ""));
        env.put(
            "DSH_SUPERAGENT_GITHUB_NUMBER", Objects.toString(trigger.metadata().get("number"), ""));
        env.put(
            "DSH_SUPERAGENT_GITHUB_PR_HEAD", Objects.toString(trigger.metadata().get("head"), ""));
        env.put(
            "DSH_SUPERAGENT_FEISHU_CHAT", Objects.toString(trigger.metadata().get("chatId"), ""));
      }
    }
    Ticket originTicket = ticketId == null ? null : engine.ticket(ticketId);
    Trigger origin = originTicket == null ? null : engine.trigger(originTicket.originTriggerId);
    if (!sourceSettings("github").path("enabled").asBoolean())
      env.keySet().removeIf(key -> key.startsWith("DSH_SUPERAGENT_GITHUB_"));
    if (!sourceSettings("feishu").path("enabled").asBoolean())
      env.keySet().removeIf(key -> key.startsWith("DSH_SUPERAGENT_FEISHU_"));
    for (SourceKind kind : sourceKinds.all()) {
      JsonNode configured = settings.value("source:" + kind.id());
      if (configured.path("enabled").asBoolean())
        env.putAll(kind.environmentValues(configured.path("config"), setup, origin));
    }
    return env;
  }

  @Override
  public void validateSettings(String block, JsonNode candidate) {
    if (block.equals("executor") || block.equals("eval")) {
      modelProviders.selection(candidate.path("model"));
      return;
    }
    if (!block.startsWith("source:")) return;
    JsonNode config = candidate.path("config");
    String base = config.path("apiBase").asText();
    if (!base.isBlank()) new HttpJsonTransport(URI.create(base), "");
    String variable = config.path("tokenEnvironment").asText();
    if (!variable.isBlank() && !variable.matches("[A-Za-z_][A-Za-z0-9_]*"))
      throw new ApiException(400, "Token environment must be an environment variable name");
    if (!options.setupChecks()
        || !candidate.path("enabled").asBoolean()
        || !Set.of("github", "feishu").contains(block.substring(7))) return;
    String id = block.substring(7);
    SourceInstance prospective = null;
    try {
      prospective =
          SourceFactory.create(id, candidate, options, settings.value("policy"), sourceKinds);
      if (prospective == null)
        throw new IllegalArgumentException("Configure source repository/account identities");
      JsonNode identity =
          prospective instanceof GitHubSource github
              ? github.checkIdentity()
              : ((FeishuSource) prospective).checkIdentity();
      if (id.equals("github")) {
        if (!identity.path("login").asText().equalsIgnoreCase(config.path("botLogin").asText()))
          throw new IllegalArgumentException("Authenticated GitHub account differs from botLogin");
        prospective.read(sourceContext, "repository");
      } else validateFeishuIdentity(identity, config);
    } catch (Exception failure) {
      throw new ApiException(
          400,
          "Source account validation failed: "
              + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
    } finally {
      if (prospective != null)
        try {
          prospective.close();
        } catch (Exception ignored) {
        }
    }
  }

  @Override
  public boolean settingsChanged(String block, JsonNode value) {
    try {
      if (block.equals("policy")) {
        configureSources();
        if (sourceScheduler != null) sourceKinds.ids().forEach(sourceScheduler::refresh);
        checkSetup();
      }
      if (block.startsWith("source:")) {
        configureSources();
        if (sourceScheduler != null) sourceScheduler.refresh(block.substring(7));
        checkSetup();
      }
      if (block.equals("executor") && executor != null) {
        ExecutionSettings changed = executionSettings();
        executor.reconfigure(workerModel(), changed);
        executor.setModelProviders(modelProviders, selection("executor"));
        if (routerWork != null) routerWork.reconfigure(changed);
      }
      pendingReload.remove(block);
      board.ready("configuration");
      requestPass();
      return false;
    } catch (Exception error) {
      // 配置事务已提交，不能再返回“未保存”。保留可见重载错误，让重新检查重试。
      pendingReload.add(block);
      board.error(
          "configuration", "Saved settings could not reload: " + error.getClass().getSimpleName());
      setup =
          object(
              "problem",
              problem(
                  block,
                  "reloadFailed",
                  "Settings were saved; runtime reload failed. Recheck setup to retry."),
              "feishuProblem",
              null,
              "accounts",
              setup.path("accounts"));
      return false;
    }
  }

  @Override
  public void checkSetup() {
    for (String block : List.copyOf(pendingReload)) {
      pendingReload.remove(block);
      settingsChanged(block, settings.value(block));
    }
    if (!pendingReload.isEmpty()) return;
    JsonNode problem = null, feishuProblem = null;
    ObjectNode accounts = object();
    try {
      modelProviders.selection(settings.value("executor").path("model"));
      modelProviders.selection(settings.value("eval").path("model"));
    } catch (IllegalArgumentException invalid) {
      problem = problem("executor", "modelProviderRequired", invalid.getMessage());
    }
    if (routerModel().equals("unconfigured") || workerModel().equals("unconfigured"))
      problem =
          problem("executor", "modelRequired", "Set OPENAI_MODEL or a role model before dispatch");
    if (options.setupChecks())
      for (String role : List.of("eval", "executor")) {
        String unavailable = modelProviders.readyProblem(selection(role));
        if (unavailable != null) problem = problem(role, "modelCredentialRequired", unavailable);
      }
    for (String id :
        sourceKinds.ids().stream().filter(id -> Set.of("github", "feishu").contains(id)).toList()) {
      JsonNode value = settings.value("source:" + id);
      if (!value.path("enabled").asBoolean()) continue;
      SourceInstance source = sources.get(id);
      JsonNode issue = null;
      if (source == null)
        issue =
            problem(
                "source:" + id,
                "sourceConfigurationRequired",
                "Configure source repository/account identities");
      else if (options.setupChecks()) {
        try {
          JsonNode c = value.path("config");
          JsonNode identity =
              source instanceof GitHubSource github
                  ? github.checkIdentity()
                  : ((FeishuSource) source).checkIdentity();
          accounts.set(id, identity);
          if (id.equals("github")) {
            if (!identity.path("login").asText().equalsIgnoreCase(c.path("botLogin").asText()))
              issue =
                  problem(
                      "source:github",
                      "accountMismatch",
                      "Token identity differs from configured botLogin");
            else source.read(sourceContext, "repository");
          } else validateFeishuIdentity(identity, c);
        } catch (Exception error) {
          issue = problem("source:" + id, "accountCheckFailed", error.getClass().getSimpleName());
        }
      }
      if (issue == null) sourceReady.add(id);
      else sourceReady.remove(id);
      if (id.equals("github") && issue != null) problem = issue;
      if (id.equals("feishu")) feishuProblem = issue;
    }
    for (String id : sourceKinds.ids())
      if (!Set.of("github", "feishu").contains(id) && sources.get(id) != null) sourceReady.add(id);
    setup = object("problem", problem, "feishuProblem", feishuProblem, "accounts", accounts);
    if (problem == null) board.ready("setup");
    else board.error("setup", problem.path("detail").asText());
    if (problem == null)
      for (SourceInstance source : sources.instances())
        if (source.enabled() && sourceReady.contains(source.id()) && syncing.add(source.id()))
          checks.submit(
              () -> {
                try {
                  source.start(sourceContext(source));
                  if (sourceKinds
                      .get(source.id())
                      .syncReady(
                          settings.value("source:" + source.id()).path("config"),
                          settings.value("policy").path("sources").path(source.id())))
                    syncAndSave(source.id());
                } catch (Exception failure) {
                  board.error("sync:" + source.id(), failure.getClass().getSimpleName());
                } finally {
                  syncing.remove(source.id());
                }
              });
  }

  private static void validateFeishuIdentity(JsonNode identity, JsonNode config) {
    JsonNode bot =
        identity.has("bot")
            ? identity.path("bot")
            : identity.path("data").has("bot") ? identity.path("data").path("bot") : identity;
    if (!bot.path("open_id").asText().equals(config.path("botId").asText()))
      throw new IllegalArgumentException("Authenticated Feishu bot differs from configured botId");
    String app = bot.path("app_id").asText();
    if (!app.isBlank() && !app.equals(config.path("appId").asText()))
      throw new IllegalArgumentException(
          "Authenticated Feishu application differs from configured appId");
  }

  private static JsonNode problem(String place, String code, String detail) {
    return object("place", place, "code", code, "values", object(), "detail", detail);
  }

  private void syncAndSave(String id) {
    int revision = settings.revision("policy");
    JsonNode part = synchronizePolicy(id);
    ObjectNode policy = (ObjectNode) settings.value("policy").deepCopy();
    ((ObjectNode) policy.path("sources")).set(id, part);
    settings.save("policy", policy, revision, "sync");
  }

  @Override
  public JsonNode synchronizePolicy(String id) {
    SourceInstance source = sources.get(id);
    SourceKind kind = sourceKinds.get(id);
    if (source == null || kind == null) throw new ApiException(400, "Source is not configured");
    try {
      JsonNode part =
          kind.synchronizePolicy(
              sourceContext(source), source, settings.value("policy").path("sources").path(id));
      board.ready("sync:" + id);
      return part;
    } catch (Exception error) {
      board.error("sync:" + id, error.getClass().getSimpleName());
      throw new ApiException(
          400, "Policy synchronization failed: " + error.getClass().getSimpleName());
    }
  }

  @Override
  public List<String> refreshLabels(String id) {
    try {
      SourceKind kind = sourceKinds.get(id);
      SourceInstance source = sources.get(id);
      if (kind == null || source == null) throw new ApiException(404, "Source missing");
      return kind.refreshLabels(sourceContext(source), source);
    } catch (Exception error) {
      throw new ApiException(400, "Label refresh failed: " + error.getClass().getSimpleName());
    }
  }

  @Override
  public void requestPass() {
    if (passes != null && !closed) passes.request();
  }

  @Override
  public JsonNode transcript(String ticketId) {
    try {
      return MAPPER.valueToTree(executor.sessionView(ticketId));
    } catch (Exception error) {
      throw new ApiException(500, "Session read failed");
    }
  }

  @Override
  public JsonNode sessions() {
    return object(
        "master",
        router == null ? null : router.view(),
        "load",
        runtime == null ? Map.of() : runtime.load());
  }

  /** Worker读取实际返回的邻居后才取得对应图片引用；缓存按工单和来源隔离且持久化。 */
  @Override
  public JsonNode readSource(String resource, String ticketId) {
    authorizeSourceRead(resource, ticketId);
    JsonNode result = readSource(resource);
    if (ticketId != null) {
      String source = resource.substring(0, resource.indexOf('/'));
      Set<String> reached = new LinkedHashSet<>();
      String[] old = store.getState("sourceReached", ticketId + ":" + source, String[].class);
      if (old != null) Collections.addAll(reached, old);
      collectReachedMessages(result, reached);
      while (reached.size() > 2000) reached.remove(reached.iterator().next());
      store.putState("sourceReached", ticketId + ":" + source, reached);
    }
    return result;
  }

  private static void collectReachedMessages(JsonNode node, Set<String> reached) {
    if (node.isArray()) {
      node.forEach(item -> collectReachedMessages(item, reached));
      return;
    }
    if (!node.isObject()) return;
    for (String key : List.of("id", "message_id", "messageId"))
      if (node.path(key).isTextual()) reached.add(node.path(key).asText());
    for (String key : List.of("messages", "message", "root", "parent", "items"))
      if (node.has(key)) collectReachedMessages(node.path(key), reached);
  }

  private static Map<String, String> query(String resource) {
    Map<String, String> result = new LinkedHashMap<>();
    int q = resource.indexOf('?');
    if (q >= 0)
      for (String item : resource.substring(q + 1).split("&")) {
        String[] pair = item.split("=", 2);
        if (pair.length == 2)
          result.put(
              java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8),
              java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
      }
    return result;
  }

  @Override
  public JsonNode readSource(String resource) {
    try {
      int slash = resource.indexOf('/');
      if (slash < 1) throw new IllegalArgumentException("Expected source/resource");
      String id = resource.substring(0, slash), path = resource.substring(slash + 1);
      if (path.startsWith("context/")) {
        String[] pieces = path.substring(8).split("/", 2);
        if (pieces.length != 2) throw new IllegalArgumentException("Invalid context reference");
        Trigger trigger = engine.trigger(pieces[0]);
        if (trigger == null || !trigger.source().equals(id))
          throw new IllegalArgumentException("Unknown source trigger");
        String[] part = pieces[1].split("\\?", 2);
        Map<String, String> query = new LinkedHashMap<>();
        if (part.length > 1)
          for (String item : part[1].split("&")) {
            String[] pair = item.split("=", 2);
            if (pair.length == 2)
              query.put(
                  java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8),
                  java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8));
          }
        if (part[0].equals("image") && query.containsKey("message_id"))
          return MAPPER.valueToTree(
              sources.readContextImage(
                  sourceContext, trigger, query.get("message_id"), query.get("image_id")));
        String cursor =
            query.getOrDefault("cursor", query.getOrDefault("path", query.get("image_id")));
        return MAPPER.valueToTree(sources.readContext(sourceContext, trigger, part[0], cursor));
      }
      return MAPPER.valueToTree(sources.read(sourceContext, id, resource));
    } catch (Exception error) {
      throw new ApiException(400, "Source read failed: " + error.getMessage());
    }
  }

  @Override
  public void authorizeSourceRead(String resource, String ticketId) {
    if (ticketId == null) return;
    Ticket ticket = engine.ticket(ticketId);
    if (ticket == null) throw new ApiException(403, "Unknown ticket");
    String[] parts = resource.split("/", 4);
    String source = parts[0];
    Set<String> reached = new HashSet<>();
    ticket.inputs.stream().map(i -> i.triggerId).filter(Objects::nonNull).forEach(reached::add);
    reached.add(ticket.originTriggerId);
    if (parts.length > 2 && parts[1].equals("context")) {
      Trigger anchor = engine.trigger(parts[2]);
      if (!reached.contains(parts[2]) || anchor == null || !anchor.source().equals(source))
        throw new ApiException(403, "Context trigger did not reach this ticket");
      String message = query(resource).get("message_id");
      if (message != null) {
        Set<String> nativeIds = new HashSet<>();
        for (String id : reached) {
          Trigger t = engine.trigger(id);
          if (t != null && t.source().equals(source) && t.metadata().get("messageId") != null)
            nativeIds.add(t.metadata().get("messageId").toString());
        }
        String[] cached = store.getState("sourceReached", ticketId + ":" + source, String[].class);
        if (cached != null) Collections.addAll(nativeIds, cached);
        if (!nativeIds.contains(message))
          throw new ApiException(403, "Source image message has not been read by this ticket");
      }
      return;
    }
    if (source.equals("feishu")) {
      if (parts.length < 2
          || !reached.contains(
              java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8)))
        throw new ApiException(403, "Feishu original did not reach this ticket");
      return;
    }
    if (source.equals("github")
        && parts.length > 2
        && Set.of("issues", "pulls").contains(parts[1])) {
      String key = source + ":" + parts[2];
      boolean subscribed =
          engine.threads().stream()
              .filter(t -> t.key.equals(key))
              .anyMatch(t -> t.subscriptions.stream().anyMatch(s -> s.ticketId().equals(ticketId)));
      if (!subscribed)
        throw new ApiException(403, "GitHub thread is not subscribed by this ticket");
      return;
    }
    boolean sourceReached =
        reached.stream()
            .map(engine::trigger)
            .filter(Objects::nonNull)
            .anyMatch(t -> t.source().equals(source));
    if (!sourceReached
        || parts.length < 2
        || !Set.of("file", "directory", "commits", "repository").contains(parts[1]))
      throw new ApiException(403, "Source resource not authorized for this ticket");
  }

  private static Set<String> strings(JsonNode values) {
    Set<String> result = new LinkedHashSet<>();
    values.forEach(v -> result.add(v.asText()));
    return result;
  }

  @Override
  public void close() throws Exception {
    if (closed) return;
    closed = true;
    passes.close();
    sourceScheduler.close();
    sources.close();
    runtime.close();
    routerWork.close();
    server.close();
    checks.shutdownNow();
    store.close();
    lock.close();
  }
}
