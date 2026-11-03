package io.github.dsh.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsh.core.*;
import io.github.dsh.core.Model.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainNoticeRelayTest {
  @TempDir Path root;

  @Test
  void ownMainActionsAreNotEchoedAndChatPrOutcomeWakesExactlyOnce() throws Exception {
    try (SqliteStore store = new SqliteStore(root.resolve("db.sqlite"))) {
      Engine engine = new Engine(store, Clock.systemUTC());
      engine.setPolicy(
          new Policy(
              "p",
              AcceptMode.AUTO,
              Map.of("feishu", new SourcePolicy(Set.of("alice"), Set.of(), Map.of())),
              Set.of()));
      engine.receive(
          new Trigger(
              "request",
              "feishu",
              List.of(new ThreadRef("feishu", "chat")),
              "alice",
              true,
              true,
              true,
              "chat",
              Instant.now().toString(),
              "Fix typo",
              new Channel("feishu", "chat", Map.of())));
      Evaluation eval = engine.evaluateDue().getFirst();
      String ticket =
          engine
              .withActor(
                  "main",
                  () -> engine.decide(eval.id(), Outcome.accept("Fix typo", "Typo only", Set.of())))
              .ticketId();
      long own =
          store.events(0, 1000).stream()
              .filter(e -> e.type().equals("ticket.created"))
              .findFirst()
              .orElseThrow()
              .sequence();
      store.appendEvent(
          "ticket.ended",
          Map.of("ticketId", ticket, "by", "executor", "outcome", "merged"),
          System.currentTimeMillis());
      DomainNoticeRelay relay = new DomainNoticeRelay(engine, store, Clock.systemUTC());
      relay.advance();
      assertTrue(store.effects().stream().noneMatch(e -> e.key().equals("event-" + own)));
      var outcomes =
          store.effects().stream()
              .filter(
                  e ->
                      e.kind() == EffectKind.RELAY
                          && e.payload().getOrDefault("text", "").contains("final PR outcome"))
              .toList();
      assertEquals(1, outcomes.size());
      relay.advance();
      assertEquals(
          1,
          store.effects().stream()
              .filter(
                  e ->
                      e.kind() == EffectKind.RELAY
                          && e.payload().getOrDefault("text", "").contains("final PR outcome"))
              .count());
    }
  }

  @Test
  void githubPrClosureDoesNotWakeRedundantResultReply() throws Exception {
    try (SqliteStore store = new SqliteStore(root.resolve("db.sqlite"))) {
      Engine engine = new Engine(store, Clock.systemUTC());
      engine.setPolicy(
          new Policy(
              "p",
              AcceptMode.AUTO,
              Map.of("github", new SourcePolicy(Set.of("alice"), Set.of(), Map.of())),
              Set.of()));
      engine.receive(
          new Trigger(
              "request",
              "github",
              List.of(new ThreadRef("github", "1")),
              "alice",
              true,
              true,
              true,
              "issues",
              Instant.now().toString(),
              "Fix typo",
              new Channel("github", "1", Map.of())));
      String ticket =
          engine
              .decide(
                  engine.evaluateDue().getFirst().id(),
                  Outcome.accept("Fix typo", "Typo", Set.of()))
              .ticketId();
      store.appendEvent(
          "ticket.ended",
          Map.of("ticketId", ticket, "by", "executor", "outcome", "merged"),
          System.currentTimeMillis());
      new DomainNoticeRelay(engine, store, Clock.systemUTC()).advance();
      assertTrue(
          store.effects().stream()
              .noneMatch(
                  e ->
                      e.kind() == EffectKind.RELAY
                          && e.payload().getOrDefault("text", "").contains("final PR outcome")));
    }
  }
}
