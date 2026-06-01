package com.tiltus.beu.plugin.index;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RustBeuIndex {
    public static final class HtmlFunctionTarget {
        private final VirtualFile file;
        private final int offset;

        private HtmlFunctionTarget(VirtualFile file, int offset) {
            this.file = file;
            this.offset = offset;
        }

        public VirtualFile file() {
            return file;
        }

        public int offset() {
            return offset;
        }
    }

    private static final class StructInfo {
        private final String doc;
        private final List<String> fields;
        private final Map<String, String> fieldDocs;

        private StructInfo(String doc, List<String> fields, Map<String, String> fieldDocs) {
            this.doc = doc;
            this.fields = fields;
            this.fieldDocs = fieldDocs;
        }
    }

    private static final class Snapshot {
        private final List<String> componentTags;
        private final Map<String, List<HtmlFunctionTarget>> htmlFunctions;
        private final Map<String, List<String>> exposedTypesByAlias;
        private final Map<String, StructInfo> exposedStructInfoByName;

        private Snapshot(
                List<String> componentTags,
                Map<String, List<HtmlFunctionTarget>> htmlFunctions,
                Map<String, List<String>> exposedTypesByAlias,
                Map<String, StructInfo> exposedStructInfoByName
        ) {
            this.componentTags = componentTags;
            this.htmlFunctions = htmlFunctions;
            this.exposedTypesByAlias = exposedTypesByAlias;
            this.exposedStructInfoByName = exposedStructInfoByName;
        }
    }

    private static final class ProjectCache {
        private final AtomicLong rustChangeVersion = new AtomicLong();
        private final AtomicBoolean listenerRegistered = new AtomicBoolean();
        private final Object lock = new Object();
        private final Map<String, StructInfo> structInfoByName = new LinkedHashMap<>();
        private volatile long indexedVersion = -1L;
        private volatile long structInfoVersion = -1L;
        private volatile long lastRebuildNanos = Long.MIN_VALUE;
        private volatile Snapshot snapshot;
    }

    private static final Pattern STRUCT_PATTERN = Pattern.compile(
            "(?ms)(?:(?<docs>(?:\\s*///[^\\r\\n]*\\R)+)\\s*)?(?<attrs>(?:\\s*#\\[[^\\r\\n]*]\\s*\\R)*)\\s*(?:pub\\s+)?struct\\s+(?<name>[A-Za-z_][\\w]*)\\s*\\{(?<body>.*?)\\}"
    );
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(?ms)(?:(?<docs>(?:\\s*///[^\\r\\n]*\\R)+)\\s*)?(?:pub(?:\\([^)]*\\))?\\s+)?(?<name>[A-Za-z_][\\w]*)\\s*:"
    );
    private static final Pattern UI_COMPONENT_STRUCT_PATTERN = Pattern.compile("(?s)#\\[ui_component\\]\\s*(?:pub\\s+)?struct\\s+([A-Za-z_][\\w]*)\\b");
    private static final Pattern TEMPLATE_NAME_PATTERN = Pattern.compile("template_name\\s*:\\s*\"([A-Za-z_][A-Za-z0-9_-]*)\"");
    private static final Pattern HTML_FN_PATTERN = Pattern.compile(
            "(?ms)#\\[html_fn\\((?<html>[A-Za-z_][\\w]*)\\)\\]\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+(?<fn>[A-Za-z_][\\w]*)"
    );
    private static final Pattern HTML_EXPOSED_TYPE_PATTERN = Pattern.compile(
            "(?ms)#\\[(?:html_use|html_shared)(?:\\([^\\]]*\\))?\\]\\s*(?:\\s*#\\[[^\\n]*]\\s*\\R)*\\s*(?:pub\\s+)?(?:struct|enum|type)\\s+([A-Za-z_][\\w]*)\\b"
    );
    private static final Pattern EXPOSED_ATTR_PATTERN = Pattern.compile("#\\[(?:html_use|html_shared)(?:\\([^\\]]*\\))?\\]");
    private static final StructInfo MISSING_STRUCT = new StructInfo(null, List.of(), Map.of());
    private static final Snapshot EMPTY_SNAPSHOT = new Snapshot(List.of(), Map.of(), Map.of(), Map.of());
    private static final long REBUILD_DEBOUNCE_NANOS = 30_000_000_000L;
    private static final int MAX_FILES_TO_SCAN = 1500;

    private static final Map<Project, ProjectCache> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private final Project project;
    private final ProjectCache cache;
    private final Snapshot snapshot;

    private RustBeuIndex(Project project, ProjectCache cache, Snapshot snapshot) {
        this.project = project;
        this.cache = cache;
        this.snapshot = snapshot;
    }

    public static RustBeuIndex get(Project project) {
        ProjectCache projectCache;
        synchronized (CACHE) {
            projectCache = CACHE.get(project);
            if (projectCache == null) {
                projectCache = new ProjectCache();
                CACHE.put(project, projectCache);
            }
        }

        ensureListener(project, projectCache);

        if (DumbService.isDumb(project)) {
            Snapshot cachedSnapshot = projectCache.snapshot;
            return new RustBeuIndex(project, projectCache, cachedSnapshot == null ? EMPTY_SNAPSHOT : cachedSnapshot);
        }

        long currentRustChangeVersion = projectCache.rustChangeVersion.get();
        Snapshot cachedSnapshot = projectCache.snapshot;
        long now = System.nanoTime();
        if (cachedSnapshot != null) {
            if (projectCache.indexedVersion == currentRustChangeVersion) {
                return new RustBeuIndex(project, projectCache, cachedSnapshot);
            }
            if ((now - projectCache.lastRebuildNanos) < REBUILD_DEBOUNCE_NANOS) {
                return new RustBeuIndex(project, projectCache, cachedSnapshot);
            }
        }

        synchronized (projectCache.lock) {
            currentRustChangeVersion = projectCache.rustChangeVersion.get();
            cachedSnapshot = projectCache.snapshot;
            now = System.nanoTime();
            if (cachedSnapshot != null) {
                if (projectCache.indexedVersion == currentRustChangeVersion) {
                    return new RustBeuIndex(project, projectCache, cachedSnapshot);
                }
                if ((now - projectCache.lastRebuildNanos) < REBUILD_DEBOUNCE_NANOS) {
                    return new RustBeuIndex(project, projectCache, cachedSnapshot);
                }
            }

            Snapshot rebuiltSnapshot = buildSnapshot(collectSnapshotFiles(project));
            projectCache.snapshot = rebuiltSnapshot;
            projectCache.indexedVersion = currentRustChangeVersion;
            projectCache.lastRebuildNanos = System.nanoTime();
            return new RustBeuIndex(project, projectCache, rebuiltSnapshot);
        }
    }

    private static void ensureListener(Project project, ProjectCache cache) {
        if (!cache.listenerRegistered.compareAndSet(false, true)) {
            return;
        }

        String basePath = normalizePath(project.getBasePath());
        project.getMessageBus().connect(project).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override
            public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) {
                    String path = normalizePath(event.getPath());
                    if (path == null || !path.endsWith(".rs")) {
                        continue;
                    }
                    if (basePath != null && !FileUtil.startsWith(path, basePath + "/")) {
                        continue;
                    }
                    if (path.contains("/target/")) {
                        continue;
                    }
                    cache.rustChangeVersion.incrementAndGet();
                    return;
                }
            }
        });
    }

    public List<String> fieldsForStructName(String structName) {
        StructInfo info = resolveStructInfo(structName, true);
        return info == null ? List.of() : info.fields;
    }

    public String structDocForStructName(String structName) {
        StructInfo info = resolveStructInfo(structName, false);
        return info == null ? null : info.doc;
    }

    public String fieldDocForStructAndField(String structName, String fieldName) {
        StructInfo info = resolveStructInfo(structName, false);
        if (info == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        return info.fieldDocs.get(fieldName.toLowerCase(Locale.ROOT));
    }

    public String resolveStructNameForObject(String objectName, String preferredStructName, boolean allowDeepPreferredLookup) {
        if (preferredStructName != null && !preferredStructName.isBlank()) {
            if (resolveStructInfo(preferredStructName, allowDeepPreferredLookup) != null) {
                return preferredStructName;
            }
        }
        if (objectName == null || objectName.isBlank()) {
            return null;
        }

        List<String> exposedTypes = snapshot.exposedTypesByAlias.get(objectName.toLowerCase(Locale.ROOT));
        if (exposedTypes == null || exposedTypes.isEmpty()) {
            return null;
        }

        for (String typeName : exposedTypes) {
            if (resolveStructInfo(typeName, false) != null) {
                return typeName;
            }
        }
        return null;
    }

    public List<String> componentTags() {
        return snapshot.componentTags;
    }

    public List<String> htmlFunctionNames() {
        return List.copyOf(snapshot.htmlFunctions.keySet());
    }

    public List<HtmlFunctionTarget> htmlFunctionTargets(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return List.of();
        }
        List<HtmlFunctionTarget> targets = snapshot.htmlFunctions.get(functionName.toLowerCase(Locale.ROOT));
        return targets == null ? List.of() : targets;
    }

    private StructInfo resolveStructInfo(String structName, boolean allowDeepSearch) {
        if (structName == null || structName.isBlank()) {
            return null;
        }

        String key = structName.toLowerCase(Locale.ROOT);
        long currentVersion = cache.rustChangeVersion.get();
        synchronized (cache.lock) {
            if (cache.structInfoVersion != currentVersion) {
                cache.structInfoByName.clear();
                cache.structInfoVersion = currentVersion;
            }

            StructInfo cachedStructInfo = cache.structInfoByName.get(key);
            if (cachedStructInfo != null) {
                return cachedStructInfo == MISSING_STRUCT ? null : cachedStructInfo;
            }

            StructInfo snapshotStructInfo = snapshot.exposedStructInfoByName.get(key);
            if (snapshotStructInfo != null) {
                cache.structInfoByName.put(key, snapshotStructInfo);
                return snapshotStructInfo;
            }

            if (!allowDeepSearch) {
                cache.structInfoByName.put(key, MISSING_STRUCT);
                return null;
            }

            if (DumbService.isDumb(project)) {
                return null;
            }

            StructInfo resolvedStructInfo = findStructInfo(structName);
            cache.structInfoByName.put(key, resolvedStructInfo == null ? MISSING_STRUCT : resolvedStructInfo);
            return resolvedStructInfo;
        }
    }

    private static Snapshot buildSnapshot(Collection<VirtualFile> files) {
        Set<String> componentTags = new LinkedHashSet<>();
        Map<String, List<HtmlFunctionTarget>> htmlFunctionTargets = new LinkedHashMap<>();
        Map<String, Set<String>> exposedTypeNamesByAlias = new LinkedHashMap<>();
        Map<String, StructInfo> exposedStructInfosByName = new LinkedHashMap<>();

        for (VirtualFile file : files) {
            String text = loadText(file);
            if (text == null || text.isEmpty()) {
                continue;
            }
            if (text.contains("#[ui_component]")) {
                collectUiComponents(text, componentTags);
            }
            if (text.contains("#[html_fn(")) {
                collectHtmlFunctions(text, file, htmlFunctionTargets);
            }
            if (text.contains("#[html_use") || text.contains("#[html_shared")) {
                collectExposedTypes(text, exposedTypeNamesByAlias);
                collectExposedStructInfo(text, exposedStructInfosByName);
            }
        }

        Map<String, List<HtmlFunctionTarget>> immutableFunctionTargets = new LinkedHashMap<>();
        for (Map.Entry<String, List<HtmlFunctionTarget>> entry : htmlFunctionTargets.entrySet()) {
            immutableFunctionTargets.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        Map<String, List<String>> immutableExposedTypesByAlias = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : exposedTypeNamesByAlias.entrySet()) {
            immutableExposedTypesByAlias.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return new Snapshot(
                List.copyOf(componentTags),
                Map.copyOf(immutableFunctionTargets),
                Map.copyOf(immutableExposedTypesByAlias),
                Map.copyOf(exposedStructInfosByName)
        );
    }

    private static List<VirtualFile> collectSnapshotFiles(Project project) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Set<VirtualFile> files = new LinkedHashSet<>();
        addFilesWithWord(project, "html_use", scope, files);
        addFilesWithWord(project, "html_shared", scope, files);
        addFilesWithWord(project, "html_fn", scope, files);
        addFilesWithWord(project, "ui_component", scope, files);

        List<VirtualFile> sortedFiles = new ArrayList<>(files);
        sortedFiles.sort(Comparator.comparing(VirtualFile::getPath));
        if (sortedFiles.size() <= MAX_FILES_TO_SCAN) {
            return sortedFiles;
        }
        return List.copyOf(sortedFiles.subList(0, MAX_FILES_TO_SCAN));
    }

    private static void addFilesWithWord(Project project, String searchToken, GlobalSearchScope scope, Set<VirtualFile> files) {
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(searchToken, scope, psiFile -> {
            VirtualFile virtualFile = psiFile.getVirtualFile();
            if (virtualFile != null && "rs".equalsIgnoreCase(virtualFile.getExtension())) {
                files.add(virtualFile);
            }
            return true;
        }, true);
    }

    private StructInfo findStructInfo(String structName) {
        List<VirtualFile> candidateFiles = findCandidateFiles(structName);
        if (candidateFiles.isEmpty()) {
            return null;
        }

        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isEmpty() || !text.contains("struct") || !text.contains(structName)) {
                continue;
            }
            StructInfo info = findStructInText(text, structName);
            if (info != null) {
                return info;
            }
        }
        return null;
    }

    private List<VirtualFile> findCandidateFiles(String structName) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        List<VirtualFile> candidateFiles = new ArrayList<>();
        Processor<PsiFile> rustFileCollector = psiFile -> {
            VirtualFile virtualFile = psiFile.getVirtualFile();
            if (virtualFile != null && "rs".equalsIgnoreCase(virtualFile.getExtension())) {
                candidateFiles.add(virtualFile);
            }
            return true;
        };
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(structName, scope, rustFileCollector, true);

        if (!candidateFiles.isEmpty()) {
            candidateFiles.sort(Comparator.comparing(VirtualFile::getPath));
            return candidateFiles;
        }

        List<VirtualFile> snapshotFiles = collectSnapshotFiles(project);
        if (snapshotFiles.isEmpty()) {
            return List.of();
        }

        List<VirtualFile> filtered = new ArrayList<>();
        for (VirtualFile file : snapshotFiles) {
            String path = normalizePath(file.getPath());
            if (path != null && !path.contains("/target/")) {
                filtered.add(file);
            }
        }
        filtered.sort(Comparator.comparing(VirtualFile::getPath));
        return filtered;
    }

    private static StructInfo findStructInText(String text, String structName) {
        Matcher structMatcher = STRUCT_PATTERN.matcher(text);
        while (structMatcher.find()) {
            String matchedStructName = structMatcher.group("name");
            if (matchedStructName == null || !matchedStructName.equals(structName)) {
                continue;
            }
            return buildStructInfo(structMatcher.group("docs"), structMatcher.group("body"));
        }
        return null;
    }

    private static StructInfo buildStructInfo(String docs, String body) {
        String structDoc = normalizeRustDoc(docs);
        Set<String> fields = new LinkedHashSet<>();
        Map<String, String> fieldDocs = new LinkedHashMap<>();

        Matcher fieldMatcher = FIELD_PATTERN.matcher(body == null ? "" : body);
        while (fieldMatcher.find()) {
            String fieldName = fieldMatcher.group("name");
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            fields.add(fieldName);

            String fieldDoc = normalizeRustDoc(fieldMatcher.group("docs"));
            if (fieldDoc != null && !fieldDoc.isBlank()) {
                fieldDocs.put(fieldName.toLowerCase(Locale.ROOT), fieldDoc);
            }
        }

        return new StructInfo(structDoc, List.copyOf(fields), Map.copyOf(fieldDocs));
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

    private static void collectUiComponents(String text, Set<String> tags) {
        Matcher structMatcher = UI_COMPONENT_STRUCT_PATTERN.matcher(text);
        while (structMatcher.find()) {
            String structName = structMatcher.group(1);

            Pattern constPattern = Pattern.compile(
                    "(?s)(?:pub\\s+)?const\\s+[A-Za-z_][\\w]*\\s*:\\s*"
                            + Pattern.quote(structName)
                            + "\\s*=\\s*"
                            + Pattern.quote(structName)
                            + "\\s*\\{(.*?)\\};"
            );
            Matcher constMatcher = constPattern.matcher(text);
            while (constMatcher.find()) {
                String body = constMatcher.group(1);
                Matcher templateMatcher = TEMPLATE_NAME_PATTERN.matcher(body);
                if (templateMatcher.find()) {
                    tags.add(templateMatcher.group(1));
                }
            }
        }
    }

    private static void collectHtmlFunctions(String text, VirtualFile file, Map<String, List<HtmlFunctionTarget>> targetMap) {
        Matcher htmlFnMatcher = HTML_FN_PATTERN.matcher(text);
        while (htmlFnMatcher.find()) {
            String htmlName = htmlFnMatcher.group("html").toLowerCase(Locale.ROOT);
            int fnNameOffset = htmlFnMatcher.start("fn");
            targetMap.computeIfAbsent(htmlName, ignored -> new ArrayList<>()).add(new HtmlFunctionTarget(file, fnNameOffset));
        }
    }

    private static void collectExposedTypes(String text, Map<String, Set<String>> targetMap) {
        Matcher matcher = HTML_EXPOSED_TYPE_PATTERN.matcher(text);
        while (matcher.find()) {
            String typeName = matcher.group(1);
            if (typeName == null || typeName.isBlank()) {
                continue;
            }

            addExposedAlias(targetMap, typeName, typeName);
            addExposedAlias(targetMap, toLowerCamel(typeName), typeName);
            addExposedAlias(targetMap, toSnakeCase(typeName), typeName);
            addExposedAlias(targetMap, typeName.toLowerCase(Locale.ROOT), typeName);
        }
    }

    private static void collectExposedStructInfo(String text, Map<String, StructInfo> targetMap) {
        Matcher structMatcher = STRUCT_PATTERN.matcher(text);
        while (structMatcher.find()) {
            String structName = structMatcher.group("name");
            if (structName == null || structName.isBlank()) {
                continue;
            }

            String attrs = structMatcher.group("attrs");
            if (attrs == null || !EXPOSED_ATTR_PATTERN.matcher(attrs).find()) {
                continue;
            }

            StructInfo info = buildStructInfo(structMatcher.group("docs"), structMatcher.group("body"));
            targetMap.put(structName.toLowerCase(Locale.ROOT), info);
        }
    }

    private static void addExposedAlias(Map<String, Set<String>> targetMap, String alias, String typeName) {
        if (alias == null || alias.isBlank() || typeName == null || typeName.isBlank()) {
            return;
        }
        String normalizedAlias = alias.toLowerCase(Locale.ROOT);
        targetMap.computeIfAbsent(normalizedAlias, ignored -> new LinkedHashSet<>()).add(typeName);
    }

    private static String toLowerCamel(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return typeName;
        }
        if (typeName.length() == 1) {
            return typeName.toLowerCase(Locale.ROOT);
        }
        return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
    }

    private static String toSnakeCase(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return typeName;
        }
        StringBuilder result = new StringBuilder(typeName.length() + 8);
        for (int i = 0; i < typeName.length(); i++) {
            char current = typeName.charAt(i);
            if (Character.isUpperCase(current)) {
                if (i > 0) {
                    char prev = typeName.charAt(i - 1);
                    if (Character.isLowerCase(prev) || Character.isDigit(prev)) {
                        result.append('_');
                    } else if (i + 1 < typeName.length() && Character.isLowerCase(typeName.charAt(i + 1))) {
                        result.append('_');
                    }
                }
                result.append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static String normalizeRustDoc(String rawDocumentation) {
        if (rawDocumentation == null || rawDocumentation.isBlank()) {
            return null;
        }

        String[] lines = rawDocumentation.split("\\R");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String normalized = line.trim();
            if (!normalized.startsWith("///")) {
                continue;
            }
            String content = normalized.substring(3).trim();
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(content);
        }
        return result.isEmpty() ? null : result.toString();
    }
}
