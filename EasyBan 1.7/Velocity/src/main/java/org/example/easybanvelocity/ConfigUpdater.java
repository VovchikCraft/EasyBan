package org.example.easybanvelocity;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-destructive configuration updater (EasyBan Velocity 1.7).
 *
 * <p>Same behaviour as the backend plugin: instead of forcing the admin to delete
 * config.yml to receive new options, every key that exists in the bundled file but
 * is missing on disk is inserted - with its original comments, in the correct
 * section and with the correct indentation.</p>
 *
 * <p>Nothing is ever removed, renamed, reordered or overwritten. If the file is
 * already up to date it is not touched at all.</p>
 */
public final class ConfigUpdater {

    /** Matches "  key: value", "key:", quoted keys; list items and comments are ignored. */
    private static final Pattern KEY_LINE = Pattern.compile(
            "^( *)([^\\s:#'\"\\-][^:]*?|'[^']+'|\"[^\"]+\") *: *(.*)$");

    private ConfigUpdater() {
    }

    /** One structural entry of a YAML file. */
    private static final class Node {
        final String path;
        final int line;
        final int indent;
        final boolean section;

        Node(String path, int line, int indent, boolean section) {
            this.path = path;
            this.line = line;
            this.indent = indent;
            this.section = section;
        }
    }

    /**
     * Adds every missing key of the bundled resource to the file on disk.
     *
     * @param owner    class used to read the resource from the jar
     * @param resource resource name, e.g. "config.yml"
     * @param file     the file on disk (created from the resource when missing)
     * @return amount of added keys (0 = already up to date)
     */
    public static int update(Class<?> owner, Logger logger, String resource, Path file) {
        byte[] bundled;
        try (InputStream in = owner.getResourceAsStream("/" + resource)) {
            if (in == null) {
                return 0;
            }
            bundled = in.readAllBytes();
        } catch (IOException e) {
            logger.warn("Could not read bundled {}: {}", resource, e.getMessage());
            return 0;
        }

        try {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(file)) {
                Files.write(file, bundled);
                return 0;
            }

            List<String> defLines = splitLines(new String(bundled, StandardCharsets.UTF_8));
            Map<String, Node> defIndex = index(defLines);

            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            if (!lines.isEmpty() && lines.get(0).startsWith("\uFEFF")) {
                lines.set(0, lines.get(0).substring(1)); // strip UTF-8 BOM
            }
            Map<String, Node> userIndex = index(lines);

            List<String> added = new ArrayList<>();
            String skipPrefix = null;

            for (Node def : defIndex.values()) {
                if (skipPrefix != null) {
                    if (def.path.startsWith(skipPrefix + ".")) {
                        continue; // the whole section was already copied
                    }
                    skipPrefix = null;
                }
                // Structural presence check: a key the admin left empty (e.g. "password:")
                // still counts as present and is never duplicated.
                if (userIndex.containsKey(def.path)) {
                    continue;
                }

                String parentPath = parentOf(def.path);
                int newIndent;
                int insertAt;

                if (parentPath.isEmpty()) {
                    newIndent = 0;
                    insertAt = rootInsertPoint(lines);
                    if (insertAt > 0) {
                        lines.add(insertAt, "");
                        insertAt++;
                    }
                } else {
                    Node p = userIndex.get(parentPath);
                    if (p == null) {
                        logger.warn("Could not add '{}' to {}: '{}' exists but is not a section. Keeping your file as it is.",
                                def.path, file.getFileName(), parentPath);
                        skipPrefix = def.path;
                        continue;
                    }
                    newIndent = childIndent(userIndex, p);
                    insertAt = insertionPoint(lines, p);
                }

                List<String> block = reindent(extractBlock(defLines, def), newIndent - def.indent);
                lines.addAll(insertAt, block);
                userIndex = index(lines); // line numbers shifted - rebuild
                added.add(def.path);

                if (def.section) {
                    skipPrefix = def.path;
                }
            }

            if (added.isEmpty()) {
                return 0;
            }

            // Write to a temp file first so a crash can never leave a half-written config.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return added.size();
        } catch (Exception e) {
            logger.warn("Could not update {}: {}", file.getFileName(), e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------ //
    //  Structural parsing                                                 //
    // ------------------------------------------------------------------ //

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\r?\n", -1)) {
            out.add(l);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** Builds path -> node for every key of a YAML document (document order preserved). */
    private static Map<String, Node> index(List<String> lines) {
        Map<String, Node> map = new LinkedHashMap<>();
        List<String> pathStack = new ArrayList<>();
        List<Integer> indentStack = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("---")) {
                continue;
            }
            Matcher m = KEY_LINE.matcher(raw);
            if (!m.matches()) {
                continue; // list items, block scalars, etc.
            }
            int indent = m.group(1).length();
            String key = unquote(m.group(2).trim());
            String value = m.group(3).trim();
            boolean section = value.isEmpty() || value.startsWith("#");

            while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) >= indent) {
                indentStack.remove(indentStack.size() - 1);
                pathStack.remove(pathStack.size() - 1);
            }
            String path = pathStack.isEmpty() ? key : pathStack.get(pathStack.size() - 1) + "." + key;
            map.put(path, new Node(path, i, indent, section));
            pathStack.add(path);
            indentStack.add(indent);
        }
        return map;
    }

    private static String unquote(String key) {
        if (key.length() >= 2
                && ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'")))) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static String parentOf(String path) {
        int idx = path.lastIndexOf('.');
        return idx < 0 ? "" : path.substring(0, idx);
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    // ------------------------------------------------------------------ //
    //  Block extraction / insertion                                       //
    // ------------------------------------------------------------------ //

    /** Copies a key (with its comments and, for sections, all children) from the template. */
    private static List<String> extractBlock(List<String> defLines, Node node) {
        List<String> comments = new ArrayList<>();
        int c = node.line - 1;
        while (c >= 0) {
            String l = defLines.get(c);
            if (l.trim().startsWith("#") && indentOf(l) == node.indent) {
                comments.add(0, l);
                c--;
            } else {
                break;
            }
        }

        int end = node.line + 1;
        if (node.section) {
            while (end < defLines.size()) {
                String l = defLines.get(end);
                if (l.trim().isEmpty()) {
                    end++;
                    continue;
                }
                if (indentOf(l) > node.indent) {
                    end++;
                } else {
                    break;
                }
            }
        }
        while (end > node.line + 1 && defLines.get(end - 1).trim().isEmpty()) {
            end--;
        }

        List<String> block = new ArrayList<>(comments);
        block.addAll(defLines.subList(node.line, end));
        return block;
    }

    private static List<String> reindent(List<String> block, int delta) {
        List<String> out = new ArrayList<>(block.size());
        for (String l : block) {
            if (l.trim().isEmpty()) {
                out.add("");
            } else if (delta >= 0) {
                out.add(" ".repeat(delta) + l);
            } else {
                out.add(l.substring(Math.min(-delta, indentOf(l))));
            }
        }
        return out;
    }

    /** Indent used by the existing children of a section (falls back to parent + 2). */
    private static int childIndent(Map<String, Node> userIndex, Node parent) {
        for (Node n : userIndex.values()) {
            if (parentOf(n.path).equals(parent.path)) {
                return n.indent;
            }
        }
        return parent.indent + 2;
    }

    /** Last line that still belongs to the given section. */
    private static int insertionPoint(List<String> lines, Node parent) {
        int i = parent.line + 1;
        int last = i;
        while (i < lines.size()) {
            String l = lines.get(i);
            if (l.trim().isEmpty()) {
                i++;
                continue;
            }
            if (indentOf(l) > parent.indent) {
                last = i + 1;
                i++;
            } else {
                break;
            }
        }
        return last;
    }

    private static int rootInsertPoint(List<String> lines) {
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).trim().isEmpty()) {
            end--;
        }
        return end;
    }
}