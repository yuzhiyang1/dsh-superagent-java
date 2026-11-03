package io.github.dsh.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PrepareDiagnosticsTest {
  @Test
  void preservesUsefulOutputAndRedactsKnownSecretsLiterally() {
    String output =
        PrepareDiagnostics.sanitize(
            "exit 17\nstdout: cloning repo\nstderr: denied abc$\\123-long and abc$\\123",
            Map.of("API_TOKEN", "abc$\\123", "PASSWORD", "abc$\\123-long", "EMPTY_KEY", ""));
    assertEquals("exit 17\nstdout: cloning repo\nstderr: denied [REDACTED] and [REDACTED]", output);
  }

  @Test
  void filtersAssignmentsHeadersUrlsAndCommonTokens() {
    String output =
        PrepareDiagnostics.sanitize(
            "password='two words' api_key=key-value\n"
                + "{\"access_token\":\"json-value\"}\nAuthorization: Bearer bearer-value\n"
                + "Proxy-Authorization: Basic basic-value\n"
                + "https://user:pass@example.com/repo?token=query-value\n"
                + "ghp_abcdefgh123456 sk-abcdefghijklmnop\nexit 128",
            Map.of());
    for (String secret :
        new String[] {
          "two words",
          "key-value",
          "json-value",
          "bearer-value",
          "basic-value",
          "user:pass",
          "query-value",
          "ghp_abcdefgh123456",
          "sk-abcdefghijklmnop"
        }) assertFalse(output.contains(secret), output);
    assertTrue(output.contains("example.com/repo"));
    assertTrue(output.contains("exit 128"));
  }

  @Test
  void removesPrivateKeysIncludingIncompleteBlocks() {
    assertEquals(
        "before\n[REDACTED]\nafter",
        PrepareDiagnostics.sanitize(
            "before\n-----BEGIN RSA PRIVATE KEY-----\nprivate-material\n"
                + "-----END RSA PRIVATE KEY-----\nafter",
            Map.of()));
    assertEquals(
        "before\n[REDACTED]",
        PrepareDiagnostics.sanitize(
            "before\n-----BEGIN OPENSSH PRIVATE KEY-----\nincomplete-material", Map.of()));
  }

  @Test
  void stripsTerminalEscapesAndControlsBeforeMatchingSecrets() {
    String output =
        PrepareDiagnostics.sanitize(
            "\u001b[31mexit 2\u001b[0m\r\npass\u0000word=hidden\n"
                + "\u001b]0;window title\u0007safe\u202e\tend",
            Map.of());
    assertEquals("exit 2\npassword=[REDACTED]\nsafe\tend", output);
  }

  @Test
  void redactsBeforeTruncatingAndBoundsFinalLength() {
    String secret = "a-secret-crossing-the-limit";
    String output =
        PrepareDiagnostics.sanitize(
            "x".repeat(4080) + secret + "y".repeat(200), Map.of("SERVICE_TOKEN", secret));
    assertTrue(output.length() <= 4096);
    assertTrue(output.endsWith("[diagnostic truncated]"));
    assertFalse(output.contains("a-secret"));
  }

  @Test
  void handlesNullAndDoesNotRedactOrdinaryEnvironmentValues() {
    assertEquals("Preparation failed", PrepareDiagnostics.sanitize(null, null));
    assertEquals("exit 2", PrepareDiagnostics.sanitize("exit 2", Map.of("EXIT", "2")));
  }

  @Test
  void boundsLargeBenignOutputWithoutQuadraticPatternMatching() {
    long started = System.nanoTime();
    for (String text : new String[] {".".repeat(65536), "a".repeat(65536)}) {
      String output = PrepareDiagnostics.sanitize(text, Map.of());
      assertEquals(4096, output.length());
      assertTrue(output.endsWith("[diagnostic truncated]"));
    }
    assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
  }
}
