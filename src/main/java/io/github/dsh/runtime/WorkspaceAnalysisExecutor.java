package io.github.dsh.runtime;

import io.github.dsh.core.Model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 无 API key 的真实执行器：读取明确配置目录中的 Java 源码，生成结构报告。 不执行 shell、不修改源码、不访问网络；符号链接不能越过目录边界。
 * 它不是模型的替身：它提供一项有限、确定且可测试的分析能力。
 */
public final class WorkspaceAnalysisExecutor implements Executor {
  private final Path root;

  public WorkspaceAnalysisExecutor(Path root) throws Exception {
    this.root = root.toRealPath();
  }

  @Override
  public Report execute(Ticket ticket, Assignment assignment) throws Exception {
    List<Path> files;
    try (Stream<Path> paths = Files.walk(root, 20)) {
      files =
          paths
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
              .sorted()
              .limit(501)
              .toList();
    }
    if (files.size() > 500) return new Report(ReportKind.BLOCKED, "超过 500 个 Java 文件，请缩小分析目录。");
    long lines = 0;
    List<String> notes = new ArrayList<>();
    for (Path file : files) {
      if (!file.toRealPath().startsWith(root)) throw new SecurityException("路径越界");
      if (Files.size(file) > 1_000_000)
        return new Report(ReportKind.BLOCKED, "文件过大: " + root.relativize(file));
      List<String> source = Files.readAllLines(file, StandardCharsets.UTF_8);
      lines += source.size();
      for (int n = 0; n < source.size(); n++) {
        String line = source.get(n).strip();
        if ((line.contains("TODO") || line.contains("FIXME")) && notes.size() < 30)
          notes.add(root.relativize(file) + ":" + (n + 1) + " " + line);
      }
    }
    String summary =
        "只读 Java 源码分析："
            + files.size()
            + " 个文件，"
            + lines
            + " 行。\n"
            + "授权范围："
            + ticket.mandate.scope()
            + "\n"
            + "文件：\n"
            + String.join(
                "\n", files.stream().limit(30).map(p -> root.relativize(p).toString()).toList())
            + "\nTODO/FIXME（最多 30 项）：\n"
            + (notes.isEmpty() ? "无" : String.join("\n", notes));
    return new Report(ReportKind.READY, summary);
  }
}
