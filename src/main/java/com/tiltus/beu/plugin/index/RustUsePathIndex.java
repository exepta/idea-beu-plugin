package com.tiltus.beu.plugin.index;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RustUsePathIndex {
    public static final class PathVariant {
        private final String insertText;
        private final boolean module;

        private PathVariant(String insertText, boolean module) {
            this.insertText = insertText;
            this.module = module;
        }

        public String insertText() {
            return insertText;
        }

        public boolean module() {
            return module;
        }
    }

    private static final class Node {
        private final Set<String> modules = new LinkedHashSet<>();
        private final Set<String> items = new LinkedHashSet<>();
    }

    private static final Pattern PUBLIC_ITEM_PATTERN = Pattern.compile(
            "(?m)^\\s*pub(?:\\([^)]*\\))?\\s+(?:struct|enum|type|trait|const|static|fn)\\s+([A-Za-z_][\\w]*)\\b"
    );
    private static final Pattern PUBLIC_MOD_PATTERN = Pattern.compile(
            "(?m)^\\s*pub(?:\\([^)]*\\))?\\s+mod\\s+([A-Za-z_][\\w]*)\\s*(?:;|\\{)"
    );
    private static final int MAX_FILES_TO_SCAN = 3000;

    private final Project project;

    private RustUsePathIndex(Project project) {
        this.project = project;
    }

    public static RustUsePathIndex get(Project project) {
        return new RustUsePathIndex(project);
    }

    public List<PathVariant> complete(String typedPrefix) {
        String prefix = typedPrefix == null ? "" : typedPrefix.trim();
        Map<String, Node> tree = buildTree();

        if (prefix.isEmpty()) {
            return List.of(new PathVariant("crate::", true));
        }

        if (!prefix.contains("::")) {
            if ("crate".startsWith(prefix)) {
                return List.of(new PathVariant("crate::", true));
            }
            return List.of();
        }

        String parentPath;
        String segmentPrefix;
        if (prefix.endsWith("::")) {
            parentPath = prefix.substring(0, prefix.length() - 2);
            segmentPrefix = "";
        } else {
            int lastSeparator = prefix.lastIndexOf("::");
            parentPath = prefix.substring(0, lastSeparator);
            segmentPrefix = prefix.substring(lastSeparator + 2);
        }

        Node node = tree.get(parentPath);
        if (node == null) {
            return List.of();
        }

        List<PathVariant> variants = new ArrayList<>();
        List<String> modules = new ArrayList<>(node.modules);
        modules.sort(String::compareTo);
        for (String module : modules) {
            if (!module.startsWith(segmentPrefix)) {
                continue;
            }
            variants.add(new PathVariant(parentPath + "::" + module + "::", true));
        }

        List<String> items = new ArrayList<>(node.items);
        items.sort(String::compareTo);
        for (String item : items) {
            if (!item.startsWith(segmentPrefix)) {
                continue;
            }
            variants.add(new PathVariant(parentPath + "::" + item, false));
        }

        return variants;
    }

    private Map<String, Node> buildTree() {
        Map<String, Node> tree = new LinkedHashMap<>();
        ensureNode(tree, "crate");

        for (VirtualFile file : collectRustFiles(project)) {
            String path = normalizePath(file.getPath());
            if (path == null || path.contains("/target/")) {
                continue;
            }

            List<String> moduleSegments = moduleSegmentsForFile(path);
            addModuleSegments(tree, moduleSegments);

            String modulePath = toModulePath(moduleSegments);
            Node node = ensureNode(tree, modulePath);

            String text = loadText(file);
            if (text == null || text.isBlank()) {
                continue;
            }

            Matcher itemMatcher = PUBLIC_ITEM_PATTERN.matcher(text);
            while (itemMatcher.find()) {
                String itemName = itemMatcher.group(1);
                if (itemName != null && !itemName.isBlank()) {
                    node.items.add(itemName);
                }
            }

            Matcher modMatcher = PUBLIC_MOD_PATTERN.matcher(text);
            while (modMatcher.find()) {
                String moduleName = modMatcher.group(1);
                if (moduleName != null && !moduleName.isBlank()) {
                    node.modules.add(moduleName);
                    ensureNode(tree, modulePath + "::" + moduleName);
                }
            }
        }

        return tree;
    }

    private static List<VirtualFile> collectRustFiles(Project project) {
        Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(
                project,
                "rs",
                GlobalSearchScope.projectScope(project)
        );
        List<VirtualFile> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(VirtualFile::getPath));
        if (sorted.size() <= MAX_FILES_TO_SCAN) {
            return sorted;
        }
        return List.copyOf(sorted.subList(0, MAX_FILES_TO_SCAN));
    }

    private static void addModuleSegments(Map<String, Node> tree, List<String> segments) {
        String parent = "crate";
        for (String segment : segments) {
            Node parentNode = ensureNode(tree, parent);
            parentNode.modules.add(segment);
            parent = parent + "::" + segment;
            ensureNode(tree, parent);
        }
    }

    private static Node ensureNode(Map<String, Node> tree, String path) {
        return tree.computeIfAbsent(path, ignored -> new Node());
    }

    private static String toModulePath(List<String> moduleSegments) {
        if (moduleSegments == null || moduleSegments.isEmpty()) {
            return "crate";
        }
        return "crate::" + String.join("::", moduleSegments);
    }

    private static List<String> moduleSegmentsForFile(String systemIndependentPath) {
        int sourceIndex = systemIndependentPath.indexOf("/src/");
        if (sourceIndex < 0) {
            return List.of();
        }

        String relativePath = systemIndependentPath.substring(sourceIndex + 5);
        if (!relativePath.endsWith(".rs")) {
            return List.of();
        }

        String modulePath;
        if ("main.rs".equals(relativePath) || "lib.rs".equals(relativePath)) {
            return List.of();
        } else if (relativePath.endsWith("/mod.rs")) {
            modulePath = relativePath.substring(0, relativePath.length() - "/mod.rs".length());
        } else {
            modulePath = relativePath.substring(0, relativePath.length() - ".rs".length());
        }

        if (modulePath.isBlank()) {
            return List.of();
        }

        String[] rawSegments = modulePath.split("/");
        List<String> segments = new ArrayList<>(rawSegments.length);
        for (String segment : rawSegments) {
            if (segment.isBlank() || !isRustIdentifier(segment)) {
                return List.of();
            }
            segments.add(segment);
        }
        return segments;
    }

    private static boolean isRustIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!(value.charAt(0) == '_' || Character.isLetter(value.charAt(0)))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!(ch == '_' || Character.isLetterOrDigit(ch))) {
                return false;
            }
        }
        return true;
    }

    private static String loadText(VirtualFile file) {
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        return FileUtil.toSystemIndependentName(path);
    }
}
