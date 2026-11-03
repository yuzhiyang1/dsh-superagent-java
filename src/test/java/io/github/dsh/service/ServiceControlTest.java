package io.github.dsh.service;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsh.adapters.HttpJsonTransport;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceControlTest {
  @TempDir Path temp;

  @Test
  void exitCodesMatchRuntimeComponentAndSetupSemantics() throws Exception {
    var json = HttpJsonTransport.JSON;
    assertEquals(1, ServiceControl.outcome(0, null));
    var ready =
        json.readTree(
            "{\"health\":{\"runtime\":{\"state\":\"ready\"}},\"setup\":{\"problem\":null}}");
    assertEquals(0, ServiceControl.outcome(1, ready));
    assertEquals(1, ServiceControl.outcome(2, ready));
    assertEquals(
        2,
        ServiceControl.outcome(
            1,
            json.readTree(
                "{\"health\":{\"runtime\":{\"state\":\"paused\"},\"github\":{\"state\":\"error\"}},\"setup\":{\"problem\":null}}")));
    assertEquals(
        3,
        ServiceControl.outcome(
            1,
            json.readTree(
                "{\"health\":{\"runtime\":{\"state\":\"ready\"}},\"setup\":{\"problem\":{\"code\":\"missing-model\"}}}")));
  }

  @Test
  void processSelectionRequiresExactMainAndStateIdentity() {
    Path state = temp.resolve("state"), jar = temp.resolve("service.jar");
    assertTrue(
        ServiceControl.matches(
            new String[] {
              "-cp", jar.toString(), "io.github.dsh.ServerMain", "--state-dir", state.toString()
            },
            state,
            jar));
    assertFalse(
        ServiceControl.matches(
            new String[] {"io.github.dsh.service.ServiceControl", "--state-dir", state.toString()},
            state,
            jar));
    assertFalse(
        ServiceControl.matches(
            new String[] {
              "io.github.dsh.ServerMain", "--state-dir", temp.resolve("other").toString()
            },
            state,
            jar));
    assertFalse(
        ServiceControl.matches(
            new String[] {
              "-jar", temp.resolve("unrelated.jar").toString(), "--state-dir", state.toString()
            },
            state,
            jar));
  }

  @Test
  void logsAreBoundedBySizeAndFileCount() throws Exception {
    Path logs = temp.resolve("logs");
    try (var out = new ServiceControl.RollingLogs(logs, 3, 128)) {
      byte[] bytes = new byte[1024];
      Arrays.fill(bytes, (byte) 'x');
      out.write(bytes, 0, bytes.length);
    }
    try (var paths = Files.list(logs)) {
      List<Path> files = paths.toList();
      assertEquals(3, files.size());
      for (Path p : files) assertTrue(Files.size(p) <= 128);
    }
  }

  @Test
  void stalePidIsNeverSignalledAndManualStopIsRespected() throws Exception {
    Path control = Files.createDirectories(temp.resolve("state/control"));
    Files.writeString(
        control.resolve("service.pid.json"),
        "{\"pid\":" + ProcessHandle.current().pid() + ",\"started\":\"1970-01-01T00:00:00Z\"}");
    String[] common = {"--state-dir", temp.resolve("state").toString(), "--port", "19991"};
    assertEquals(0, ServiceControl.execute(join("stop", common)));
    assertTrue(ProcessHandle.current().isAlive());
    assertTrue(Files.exists(control.resolve("manually-stopped")));
    assertEquals(0, ServiceControl.execute(join("start", join(common, "--respect-stop"))));
    assertEquals(1, ServiceControl.execute(join("status", common)));
  }

  @Test
  void actualJavaDaemonStartStatusStopAndRespectStop() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    Path state = temp.resolve("daemon");
    String[] common = {
      "--state-dir",
      state.toString(),
      "--port",
      Integer.toString(port),
      "--classpath",
      System.getProperty("java.class.path"),
      "--timeout",
      "15",
      "--stop-timeout",
      "15"
    };
    try {
      assertEquals(3, ServiceControl.execute(join("start", common)));
      assertEquals(3, ServiceControl.execute(join("status", common)));
      assertEquals(0, ServiceControl.execute(join("stop", common)));
      assertEquals(1, ServiceControl.execute(join("status", common)));
      assertEquals(0, ServiceControl.execute(join("start", join(common, "--respect-stop"))));
      assertTrue(Files.exists(state.resolve("control/manually-stopped")));
    } finally {
      ServiceControl.execute(join("stop", common));
    }
  }

  private static String[] join(String head, String[] rest) {
    String[] result = new String[rest.length + 1];
    result[0] = head;
    System.arraycopy(rest, 0, result, 1, rest.length);
    return result;
  }

  private static String[] join(String[] head, String tail) {
    String[] result = Arrays.copyOf(head, head.length + 1);
    result[head.length] = tail;
    return result;
  }
}
