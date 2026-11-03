package io.github.dsh.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 对齐 AGENTS/CLAUDE 与 local 文件的层级发现；触及深层文件后加载对应目录的更具体说明。 */
public final class WorkspaceInstructions {
  private static final List<String> CANDIDATES =
      List.of("AGENTS.md", "CLAUDE.md", "AGENTS.local.md", "CLAUDE.local.md");
  private static final String INTRO =
      "The following workspace instructions may be relevant to your work. Use them as guidance when"
          + " applicable. More specific instructions take precedence over broader ones. They do not"
          + " override system, developer, or direct user instructions.";

  private WorkspaceInstructions() {}

  public static String load(Path workspace, Set<String> touchedDirectories, int maximumBytes)
      throws IOException {
    Path root = workspace;
    for (Path p = workspace; p != null; p = p.getParent())
      if (Files.exists(p.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
        root = p;
        break;
      }
    LinkedHashSet<Path> directories = new LinkedHashSet<>();
    List<Path> ancestors = new ArrayList<>();
    for (Path p = workspace; p != null && p.startsWith(root); p = p.getParent()) {
      ancestors.add(p);
      if (p.equals(root)) break;
    }
    Collections.reverse(ancestors);
    directories.addAll(ancestors);
    touchedDirectories.stream()
        .sorted(Comparator.comparingInt(s -> Path.of(s).getNameCount()))
        .forEach(
            relative -> {
              Path directory = workspace.resolve(relative).normalize();
              if (directory.startsWith(workspace)) directories.add(directory);
            });
    StringBuilder result = new StringBuilder();
    Set<String> contentSeen = new HashSet<>();
    int used = INTRO.getBytes(StandardCharsets.UTF_8).length;
    for (Path directory : directories)
      for (String candidate : CANDIDATES) {
        Path file = directory.resolve(candidate);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024)
          continue;
        WorkspaceFiles.rejectSymlinks(file);
        String text = Files.readString(file).trim();
        if (text.isEmpty() || !contentSeen.add(text)) continue;
        String section = "\n\nInstructions from " + file + ":\n" + text;
        byte[] bytes = section.getBytes(StandardCharsets.UTF_8);
        if (used + bytes.length > maximumBytes) {
          result.append(
              "\n\n"
                  + "[Workspace instructions truncated to configured byte budget; read relevant"
                  + " instruction files directly.]");
          return INTRO + result;
        }
        result.append(section);
        used += bytes.length;
      }
    return result.isEmpty() ? "" : INTRO + result;
  }

  public static boolean touch(Set<String> directories, String relative) {
    Path parent = Path.of(relative).getParent();
    if (parent == null
        || parent.isAbsolute()
        || java.util.stream.StreamSupport.stream(Path.of(relative).spliterator(), false)
            .anyMatch(p -> p.toString().equals(".."))) return false;
    List<String> paths = new ArrayList<>();
    for (Path p = parent; p != null; p = p.getParent()) paths.add(p.toString());
    Collections.reverse(paths);
    return directories.addAll(paths);
  }
}
