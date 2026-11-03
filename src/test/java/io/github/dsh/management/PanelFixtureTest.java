package io.github.dsh.management;

import static io.github.dsh.config.Json.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsh.config.PromptService;
import io.github.dsh.config.VersionedSettings;
import io.github.dsh.core.Engine;
import io.github.dsh.core.SqliteStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PanelFixtureTest {
  @TempDir Path directory;

  @Test
  void sourceAuditContainsScopeFilteredTriggerWithoutChangingFourTicketFixture() throws Exception {
    try (SqliteStore store = new SqliteStore(directory.resolve("fixture.sqlite"))) {
      Engine engine = new Engine(store);
      ManagementHost host = new ManagementHost() {};
      VersionedSettings settings = new VersionedSettings(store, engine, directory, host);
      PanelFixture.seed(engine, settings);
      assertEquals(4, engine.tickets().size());
      assertEquals("outsideScope", engine.triggerDisposition("qa-filtered").filtered);
      try (ManagementServer server =
          new ManagementServer(
              engine, store, settings, new PromptService(store, settings, host), host, 0)) {
        server.start();
        HttpResponse<String> response =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(
                            URI.create(
                                "http://127.0.0.1:" + server.port() + "/orchestrator/api/sources"))
                        .header("x-orchestrator-request", "1")
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode github = null;
        for (JsonNode source : parse(response.body())) {
          if (source.path("id").asText().equals("github")) github = source;
        }
        org.junit.jupiter.api.Assertions.assertNotNull(github);
        assertEquals(1, github.path("filtered").size());
        assertEquals(1, github.path("stats").path("filtered").asInt());
        assertEquals(
            "Not an allowed sender", github.path("filtered").get(0).path("preview").asText());
        assertEquals("outsideScope", github.path("filtered").get(0).path("reason").asText());
      }
    }
  }
}
