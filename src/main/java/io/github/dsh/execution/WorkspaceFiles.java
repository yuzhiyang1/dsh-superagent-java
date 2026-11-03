package io.github.dsh.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** 文件工具只访问当前工作目录。符号链接、git 元数据和典型凭据文件不对模型开放。 */
public final class WorkspaceFiles {
  private final Path root;
  private final int maxBytes;
  private static final Set<String> PRIVATE =
      Set.of(
          ".git",
          ".ssh",
          ".aws",
          ".env",
          "credentials",
          "credentials.json",
          "id_rsa",
          "id_ed25519");

  public WorkspaceFiles(Path root, int maxBytes) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    this.maxBytes = maxBytes;
    rejectSymlinks(root);
  }

  public Path root() {
    return root;
  }

  public Path resolve(String relative) throws IOException {
    if (relative == null || relative.isBlank() || relative.contains("\\"))
      throw new IOException("Relative workspace path required");
    Path p = Path.of(relative);
    if (p.isAbsolute()) throw new IOException("Absolute paths refused");
    for (Path part : p)
      if (part.toString().equals("..")
          || PRIVATE.contains(part.toString())
          || part.toString().startsWith(".env.")
          || part.toString().startsWith(".dsh-"))
        throw new IOException("Private/traversal path refused");
    p = root.resolve(p).normalize();
    if (!p.startsWith(root)) throw new IOException("Path escapes workspace");
    rejectSymlinks(p);
    return p;
  }

  public String read(String relative, int offset, int limit) throws IOException {
    Path p = resolve(relative);
    if (!Files.isRegularFile(p) || Files.size(p) > maxBytes)
      throw new IOException("File absent or exceeds " + maxBytes + " bytes");
    String s = Files.readString(p);
    if (offset < 0 || offset > s.length() || limit < 1) throw new IOException("Invalid read range");
    return s.substring(offset, Math.min(s.length(), offset + limit));
  }

  public void write(String relative, String content) throws IOException {
    write(relative, content, () -> true);
  }

  public void write(String relative, String content, java.util.function.BooleanSupplier authorized)
      throws IOException {
    if (!authorized.getAsBoolean()) throw new IOException("Execution stopped before file mutation");
    Path p = resolve(relative);
    byte[] b = content.getBytes(StandardCharsets.UTF_8);
    if (b.length > maxBytes) throw new IOException("File exceeds " + maxBytes + " bytes");
    if (!authorized.getAsBoolean()) throw new IOException("Execution stopped before file mutation");
    Files.createDirectories(p.getParent());
    rejectSymlinks(p);
    atomicWrite(p, b, authorized);
  }

  public String edit(String relative, String old, String replacement, boolean all)
      throws IOException {
    return edit(relative, old, replacement, all, () -> true);
  }

  public String edit(
      String relative,
      String old,
      String replacement,
      boolean all,
      java.util.function.BooleanSupplier authorized)
      throws IOException {
    if (old.isEmpty()) throw new IOException("Empty match refused");
    String text = read(relative, 0, maxBytes);
    int first = text.indexOf(old);
    if (first < 0) throw new IOException("Exact text not found");
    if (!all && text.indexOf(old, first + old.length()) >= 0)
      throw new IOException("Match is ambiguous; use replace_all");
    write(
        relative,
        all
            ? text.replace(old, replacement)
            : text.substring(0, first) + replacement + text.substring(first + old.length()),
        authorized);
    return "Updated " + relative;
  }

  public List<String> list(String glob) throws IOException {
    var matcher =
        root.getFileSystem()
            .getPathMatcher("glob:" + (glob == null || glob.isBlank() ? "**" : glob));
    List<String> out = new ArrayList<>();
    Files.walkFileTree(
        root,
        EnumSet.noneOf(FileVisitOption.class),
        64,
        new SimpleFileVisitor<>() {
          int visited;

          private void bound() throws IOException {
            if (++visited > 100000) throw new IOException("Workspace traversal limit exceeded");
          }

          @Override
          public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a)
              throws IOException {
            bound();
            if (!p.equals(root)
                && (PRIVATE.contains(p.getFileName().toString())
                    || Set.of("node_modules", "target", "build")
                        .contains(p.getFileName().toString()))) return FileVisitResult.SKIP_SUBTREE;
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
            bound();
            if (a.isSymbolicLink()) return FileVisitResult.CONTINUE;
            Path r = root.relativize(p);
            try {
              resolve(r.toString());
            } catch (IOException e) {
              return FileVisitResult.CONTINUE;
            }
            if (matcher.matches(r) || "**".equals(glob) || glob == null) out.add(r.toString());
            return out.size() < 10000 ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
          }
        });
    Collections.sort(out);
    return out;
  }

  public List<String> search(String literal, String glob, int limit) throws IOException {
    if (literal.isEmpty() || limit < 1 || limit > 1000)
      throw new IOException("Invalid search parameters");
    List<String> out = new ArrayList<>();
    for (String p : list(glob)) {
      Path file = resolve(p);
      if (Files.size(file) > maxBytes) continue;
      List<String> lines;
      try {
        lines = Files.readAllLines(file);
      } catch (IOException e) {
        continue;
      }
      for (int i = 0; i < lines.size(); i++)
        if (lines.get(i).contains(literal)) {
          out.add(p + ":" + (i + 1) + ":" + lines.get(i));
          if (out.size() >= limit) return out;
        }
    }
    return out;
  }

  public static void rejectSymlinks(Path path) throws IOException {
    Path current = path.toAbsolutePath().getRoot();
    for (Path part : path.toAbsolutePath()) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IOException("Symlink refused: " + current);
    }
  }

  public static void atomicWrite(Path path, byte[] bytes) throws IOException {
    atomicWrite(path, bytes, () -> true);
  }

  public static void atomicWrite(
      Path path, byte[] bytes, java.util.function.BooleanSupplier authorized) throws IOException {
    rejectSymlinks(path);
    Files.createDirectories(path.getParent());
    Path tmp = Files.createTempFile(path.getParent(), ".dsh-atomic-", ".tmp");
    try {
      if (!authorized.getAsBoolean()) throw new IOException("Execution stopped before file write");
      Files.write(tmp, bytes);
      if (!authorized.getAsBoolean())
        throw new IOException("Execution stopped before file replacement");
      try {
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  public static void copyTemplate(Path source, Path target, int maxBytes) throws IOException {
    rejectSymlinks(source);
    if (source.startsWith(target) || target.startsWith(source))
      throw new IOException("Template and workspaces must be disjoint");
    WorkspaceFiles from = new WorkspaceFiles(source, maxBytes);
    WorkspaceFiles to = new WorkspaceFiles(target, maxBytes);
    Files.createDirectories(target);
    for (String relative : from.list("**")) {
      Path p = from.resolve(relative);
      if (Files.size(p) > maxBytes) continue;
      Path dest = to.resolve(relative);
      Files.createDirectories(dest.getParent());
      Files.copy(p, dest);
    }
  }
}
