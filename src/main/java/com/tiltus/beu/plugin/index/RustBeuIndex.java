package com.tiltus.beu.plugin.index;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
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

    public static final class StructTarget {
        private final VirtualFile file;
        private final int offset;

        private StructTarget(VirtualFile file, int offset) {
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
        private final Map<String, String> fieldTypes;
        private final List<String> methods;
        private final Map<String, String> methodDocs;
        private final Map<String, String> methodParameters;
        private final Map<String, String> methodReturnTypes;

        private StructInfo(
                String doc,
                List<String> fields,
                Map<String, String> fieldDocs,
                Map<String, String> fieldTypes,
                List<String> methods,
                Map<String, String> methodDocs,
                Map<String, String> methodParameters,
                Map<String, String> methodReturnTypes
        ) {
            this.doc = doc;
            this.fields = fields;
            this.fieldDocs = fieldDocs;
            this.fieldTypes = fieldTypes;
            this.methods = methods;
            this.methodDocs = methodDocs;
            this.methodParameters = methodParameters;
            this.methodReturnTypes = methodReturnTypes;
        }
    }

    private static final class EnumVariantInfo {
        private final String name;
        private final int offset;
        private final String doc;

        private EnumVariantInfo(String name, int offset, String doc) {
            this.name = name;
            this.offset = offset;
            this.doc = doc;
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
            "(?ms)(?:(?<docs>(?:\\s*///[^\\r\\n]*\\R)+)\\s*)?(?<attrs>(?:\\s*#\\[[^\\r\\n]*]\\s*\\R)*)\\s*(?:pub\\s+)?struct\\s+(?<name>[A-Za-z_][\\w]*)\\b[^\\{;]*\\{(?<body>.*?)\\}"
    );
    private static final Pattern ENUM_PATTERN = Pattern.compile(
            "(?ms)(?:(?<docs>(?:\\s*///[^\\r\\n]*\\R)+)\\s*)?(?<attrs>(?:\\s*#\\[[^\\r\\n]*]\\s*\\R)*)\\s*(?:pub\\s+)?enum\\s+(?<name>[A-Za-z_][\\w]*)\\b"
    );
    private static final Pattern ENUM_WITH_BODY_PATTERN = Pattern.compile(
            "(?ms)(?:(?<docs>(?:\\s*///[^\\r\\n]*\\R)+)\\s*)?(?<attrs>(?:\\s*#\\[[^\\r\\n]*]\\s*\\R)*)\\s*(?:pub\\s+)?enum\\s+(?<name>[A-Za-z_][\\w]*)\\b[^\\{;]*\\{(?<body>.*?)\\}"
    );
    private static final Pattern TYPE_ALIAS_PATTERN = Pattern.compile(
            "(?ms)(?:(?<docs>(?:\\s*///[^\\r\\n]*\\R)+)\\s*)?(?<attrs>(?:\\s*#\\[[^\\r\\n]*]\\s*\\R)*)\\s*(?:pub\\s+)?type\\s+(?<name>[A-Za-z_][\\w]*)\\b"
    );
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(?ms)(?:(?<docs>(?:\\s*///[^\\r\\n]*\\R)+)\\s*)?(?:pub(?:\\([^)]*\\))?\\s+)?(?<name>[A-Za-z_][\\w]*)\\s*:"
    );
    private static final Pattern PUB_METHOD_PATTERN = Pattern.compile(
            "(?ms)(?:(?<docs>(?:\\s*///[^\\r\\n]*\\R)+)\\s*)?pub(?:\\([^)]*\\))?\\s+(?:async\\s+)?fn\\s+(?<name>[A-Za-z_][\\w]*)\\s*(?:<[^>]*>\\s*)?\\("
    );
    private static final Pattern UI_COMPONENT_STRUCT_PATTERN = Pattern.compile("(?s)#\\[ui_component\\]\\s*(?:pub\\s+)?struct\\s+([A-Za-z_][\\w]*)\\b");
    private static final Pattern TEMPLATE_NAME_PATTERN = Pattern.compile("template_name\\s*:\\s*\"([A-Za-z_][A-Za-z0-9_-]*)\"");
    private static final Pattern HTML_FN_PATTERN = Pattern.compile(
            "(?ms)#\\[html_fn\\((?:\"(?<htmlQuoted>[A-Za-z_][\\w]*)\"|(?<html>[A-Za-z_][\\w]*))\\)\\]\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+(?<fn>[A-Za-z_][\\w]*)"
    );
    private static final Pattern HTML_EXPOSED_TYPE_PATTERN = Pattern.compile(
            "(?ms)#\\[(?:html_use|html_shared)(?:\\([^\\]]*\\))?\\]\\s*(?:\\s*#\\[[^\\n]*]\\s*\\R)*\\s*(?:pub\\s+)?(?:struct|enum|type)\\s+([A-Za-z_][\\w]*)\\b"
    );
    private static final Pattern EXPOSED_ATTR_PATTERN = Pattern.compile("#\\[(?:html_use|html_shared)(?:\\([^\\]]*\\))?\\]");
    private static final StructInfo MISSING_STRUCT = new StructInfo(null, List.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of());
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

    public List<String> methodsForStructName(String structName) {
        StructInfo info = resolveStructInfo(structName, true);
        return info == null ? List.of() : info.methods;
    }

    public String structDocForStructName(String structName) {
        StructInfo info = resolveStructInfo(structName, true);
        return info == null ? null : info.doc;
    }

    public String typeDocForName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }

        StructInfo structInfo = resolveStructInfo(typeName, true);
        if (structInfo != null && structInfo.doc != null && !structInfo.doc.isBlank()) {
            return structInfo.doc;
        }

        List<VirtualFile> candidateFiles = findCandidateFiles(typeName);
        if (candidateFiles.isEmpty()) {
            return null;
        }

        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isBlank() || !text.contains(typeName)) {
                continue;
            }
            String doc = findTypeDocInText(text, typeName);
            if (doc != null && !doc.isBlank()) {
                return doc;
            }
        }
        return null;
    }

    public String fieldDocForStructAndField(String structName, String fieldName) {
        StructInfo info = resolveStructInfo(structName, true);
        if (info == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        return info.fieldDocs.get(fieldName.toLowerCase(Locale.ROOT));
    }

    public String fieldTypeForStructAndField(String structName, String fieldName) {
        StructInfo info = resolveStructInfo(structName, true);
        if (info == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        return info.fieldTypes.get(fieldName.toLowerCase(Locale.ROOT));
    }

    public String methodDocForStructAndMethod(String structName, String methodName) {
        StructInfo info = resolveStructInfo(structName, true);
        if (info == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        return info.methodDocs.get(methodName.toLowerCase(Locale.ROOT));
    }

    public String methodParametersForStructAndMethod(String structName, String methodName) {
        StructInfo info = resolveStructInfo(structName, true);
        if (info == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        return info.methodParameters.get(methodName.toLowerCase(Locale.ROOT));
    }

    public String methodReturnTypeForStructAndMethod(String structName, String methodName) {
        StructInfo info = resolveStructInfo(structName, true);
        if (info == null || methodName == null || methodName.isBlank()) {
            return null;
        }
        return info.methodReturnTypes.get(methodName.toLowerCase(Locale.ROOT));
    }

    public List<String> variantsForEnumName(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return List.of();
        }

        List<VirtualFile> candidateFiles = findCandidateFiles(enumName);
        if (candidateFiles.isEmpty()) {
            return List.of();
        }

        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isBlank() || !text.contains(enumName) || !text.contains("enum")) {
                continue;
            }

            List<EnumVariantInfo> variants = findEnumVariantsInText(text, enumName);
            if (!variants.isEmpty()) {
                List<String> names = new ArrayList<>(variants.size());
                for (EnumVariantInfo variant : variants) {
                    names.add(variant.name);
                }
                return names;
            }
        }
        return List.of();
    }

    public StructTarget enumVariantTargetForEnumAndVariant(String enumName, String variantName) {
        if (enumName == null || enumName.isBlank() || variantName == null || variantName.isBlank()) {
            return null;
        }

        List<VirtualFile> candidateFiles = findCandidateFiles(enumName);
        if (candidateFiles.isEmpty()) {
            return null;
        }

        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isBlank() || !text.contains(enumName) || !text.contains(variantName)) {
                continue;
            }

            List<EnumVariantInfo> variants = findEnumVariantsInText(text, enumName);
            for (EnumVariantInfo variant : variants) {
                if (variant.name.equals(variantName)) {
                    return new StructTarget(file, variant.offset);
                }
            }
        }
        return null;
    }

    public String enumVariantDocForEnumAndVariant(String enumName, String variantName) {
        if (enumName == null || enumName.isBlank() || variantName == null || variantName.isBlank()) {
            return null;
        }

        List<VirtualFile> candidateFiles = findCandidateFiles(enumName);
        if (candidateFiles.isEmpty()) {
            return null;
        }

        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isBlank() || !text.contains(enumName) || !text.contains(variantName)) {
                continue;
            }

            List<EnumVariantInfo> variants = findEnumVariantsInText(text, enumName);
            for (EnumVariantInfo variant : variants) {
                if (!variant.name.equals(variantName)) {
                    continue;
                }
                if (variant.doc != null && !variant.doc.isBlank()) {
                    return variant.doc;
                }
                return null;
            }
        }
        return null;
    }

    public String resolveStructNameForObject(String objectName, String preferredStructName, boolean allowDeepPreferredLookup) {
        if (preferredStructName != null && !preferredStructName.isBlank()) {
            if (isResolvableTypeName(preferredStructName, allowDeepPreferredLookup)) {
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
            if (isResolvableTypeName(typeName, allowDeepPreferredLookup)) {
                return typeName;
            }
        }
        return null;
    }

    private boolean isResolvableTypeName(String typeName, boolean allowDeepPreferredLookup) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        if (resolveStructInfo(typeName, allowDeepPreferredLookup) != null) {
            return true;
        }
        return typeTargetForName(typeName) != null;
    }

    public List<String> componentTags() {
        return snapshot.componentTags;
    }

    public List<String> htmlFunctionNames() {
        Map<String, List<HtmlFunctionTarget>> liveFunctionTargets = new LinkedHashMap<>();
        boolean needsRescan = false;
        for (Map.Entry<String, List<HtmlFunctionTarget>> entry : snapshot.htmlFunctions.entrySet()) {
            String functionName = entry.getKey();
            List<HtmlFunctionTarget> validated = validateHtmlFunctionTargets(functionName, entry.getValue());
            if (!validated.isEmpty()) {
                liveFunctionTargets.put(functionName, validated);
            } else {
                needsRescan = true;
            }
        }
        if (liveFunctionTargets.isEmpty() || needsRescan) {
            liveFunctionTargets.putAll(scanHtmlFunctionsByName());
        }
        return List.copyOf(liveFunctionTargets.keySet());
    }

    public List<HtmlFunctionTarget> htmlFunctionTargets(String functionName) {
        if (functionName == null || functionName.isBlank()) {
            return List.of();
        }
        String normalizedName = functionName.toLowerCase(Locale.ROOT);
        List<HtmlFunctionTarget> snapshotTargets = snapshot.htmlFunctions.get(normalizedName);
        List<HtmlFunctionTarget> validatedTargets = validateHtmlFunctionTargets(normalizedName, snapshotTargets);
        if (!validatedTargets.isEmpty()) {
            return validatedTargets;
        }

        Map<String, List<HtmlFunctionTarget>> scanned = scanHtmlFunctionsByName();
        List<HtmlFunctionTarget> targets = scanned.get(normalizedName);
        return targets == null ? List.of() : targets;
    }

    public StructTarget typeTargetForName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }

        List<VirtualFile> candidateFiles = findCandidateFiles(typeName);
        if (candidateFiles.isEmpty()) {
            return null;
        }

        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isEmpty() || !text.contains(typeName)) {
                continue;
            }
            int offset = findTypeNameOffsetInText(text, typeName);
            if (offset >= 0) {
                return new StructTarget(file, offset);
            }
        }
        return null;
    }

    public StructTarget structTargetForStructName(String structName) {
        if (structName == null || structName.isBlank()) {
            return null;
        }

        List<VirtualFile> candidateFiles = findCandidateFiles(structName);
        if (candidateFiles.isEmpty()) {
            return null;
        }

        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isEmpty() || !text.contains("struct") || !text.contains(structName)) {
                continue;
            }
            int offset = findStructNameOffsetInText(text, structName);
            if (offset >= 0) {
                return new StructTarget(file, offset);
            }
        }
        return null;
    }

    public StructTarget fieldTargetForStructAndField(String structName, String fieldName) {
        if (structName == null || structName.isBlank() || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        List<VirtualFile> candidateFiles = findCandidateFiles(structName);
        if (candidateFiles.isEmpty()) {
            return null;
        }

        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isEmpty() || !text.contains(structName) || !text.contains(fieldName)) {
                continue;
            }

            int offset = findFieldOffsetInText(text, structName, fieldName);
            if (offset >= 0) {
                return new StructTarget(file, offset);
            }
        }
        return null;
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
                if (cachedStructInfo != MISSING_STRUCT) {
                    return cachedStructInfo;
                }
                if (!allowDeepSearch) {
                    return null;
                }
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

        StructInfo structInfo = null;
        Set<String> methodNames = new LinkedHashSet<>();
        Map<String, String> methodDocs = new LinkedHashMap<>();
        Map<String, String> methodParameters = new LinkedHashMap<>();
        Map<String, String> methodReturnTypes = new LinkedHashMap<>();
        for (VirtualFile file : candidateFiles) {
            String text = loadText(file);
            if (text == null || text.isEmpty() || !text.contains(structName)) {
                continue;
            }

            if (structInfo == null && text.contains("struct")) {
                structInfo = findStructInText(text, structName);
            }
            if (text.contains("impl") && text.contains("fn")) {
                collectPublicMethodsFromImpls(text, structName, methodNames, methodDocs, methodParameters, methodReturnTypes);
            }
        }
        if (structInfo == null) {
            if (methodNames.isEmpty()) {
                return null;
            }
            return new StructInfo(
                    null,
                    List.of(),
                    Map.of(),
                    Map.of(),
                    List.copyOf(methodNames),
                    Map.copyOf(methodDocs),
                    Map.copyOf(methodParameters),
                    Map.copyOf(methodReturnTypes)
            );
        }
        return withMethods(structInfo, methodNames, methodDocs, methodParameters, methodReturnTypes);
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

    private static int findStructNameOffsetInText(String text, String structName) {
        Matcher structMatcher = STRUCT_PATTERN.matcher(text);
        while (structMatcher.find()) {
            String matchedStructName = structMatcher.group("name");
            if (matchedStructName == null || !matchedStructName.equals(structName)) {
                continue;
            }
            return structMatcher.start("name");
        }
        return -1;
    }

    private static int findTypeNameOffsetInText(String text, String typeName) {
        int structOffset = findStructNameOffsetInText(text, typeName);
        if (structOffset >= 0) {
            return structOffset;
        }

        Matcher enumMatcher = ENUM_PATTERN.matcher(text);
        while (enumMatcher.find()) {
            String matchedEnumName = enumMatcher.group("name");
            if (matchedEnumName == null || !matchedEnumName.equals(typeName)) {
                continue;
            }
            return enumMatcher.start("name");
        }

        Matcher aliasMatcher = TYPE_ALIAS_PATTERN.matcher(text);
        while (aliasMatcher.find()) {
            String matchedAliasName = aliasMatcher.group("name");
            if (matchedAliasName == null || !matchedAliasName.equals(typeName)) {
                continue;
            }
            return aliasMatcher.start("name");
        }

        return -1;
    }

    private static String findTypeDocInText(String text, String typeName) {
        Matcher structMatcher = STRUCT_PATTERN.matcher(text);
        while (structMatcher.find()) {
            String matchedName = structMatcher.group("name");
            if (matchedName == null || !matchedName.equals(typeName)) {
                continue;
            }
            return normalizeRustDoc(structMatcher.group("docs"));
        }

        Matcher enumMatcher = ENUM_PATTERN.matcher(text);
        while (enumMatcher.find()) {
            String matchedName = enumMatcher.group("name");
            if (matchedName == null || !matchedName.equals(typeName)) {
                continue;
            }
            return normalizeRustDoc(enumMatcher.group("docs"));
        }

        Matcher aliasMatcher = TYPE_ALIAS_PATTERN.matcher(text);
        while (aliasMatcher.find()) {
            String matchedName = aliasMatcher.group("name");
            if (matchedName == null || !matchedName.equals(typeName)) {
                continue;
            }
            return normalizeRustDoc(aliasMatcher.group("docs"));
        }

        return null;
    }

    private static List<EnumVariantInfo> findEnumVariantsInText(String text, String enumName) {
        Matcher enumMatcher = ENUM_WITH_BODY_PATTERN.matcher(text);
        while (enumMatcher.find()) {
            String matchedEnumName = enumMatcher.group("name");
            if (matchedEnumName == null || !matchedEnumName.equals(enumName)) {
                continue;
            }

            String body = enumMatcher.group("body");
            if (body == null || body.isBlank()) {
                return List.of();
            }
            int bodyStart = enumMatcher.start("body");
            return parseEnumVariants(body, bodyStart);
        }
        return List.of();
    }

    private static List<EnumVariantInfo> parseEnumVariants(String body, int bodyStartOffset) {
        List<EnumVariantInfo> variants = new ArrayList<>();
        int segmentStart = 0;
        int angleDepth = 0;
        int parenDepth = 0;
        int squareDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int index = 0; index < body.length(); index++) {
            char ch = body.charAt(index);

            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (inChar) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '\'') {
                inChar = true;
                continue;
            }

            if (ch == '<') {
                angleDepth++;
                continue;
            }
            if (ch == '>') {
                if (angleDepth > 0) {
                    angleDepth--;
                }
                continue;
            }
            if (ch == '(') {
                parenDepth++;
                continue;
            }
            if (ch == ')') {
                if (parenDepth > 0) {
                    parenDepth--;
                }
                continue;
            }
            if (ch == '[') {
                squareDepth++;
                continue;
            }
            if (ch == ']') {
                if (squareDepth > 0) {
                    squareDepth--;
                }
                continue;
            }
            if (ch == '{') {
                braceDepth++;
                continue;
            }
            if (ch == '}') {
                if (braceDepth > 0) {
                    braceDepth--;
                }
                continue;
            }

            if (ch == ',' && angleDepth == 0 && parenDepth == 0 && squareDepth == 0 && braceDepth == 0) {
                EnumVariantInfo info = extractEnumVariantInfo(body, bodyStartOffset, segmentStart, index);
                if (info != null) {
                    variants.add(info);
                }
                segmentStart = index + 1;
            }
        }

        EnumVariantInfo tail = extractEnumVariantInfo(body, bodyStartOffset, segmentStart, body.length());
        if (tail != null) {
            variants.add(tail);
        }
        return variants;
    }

    private static EnumVariantInfo extractEnumVariantInfo(String body, int bodyStartOffset, int segmentStart, int segmentEnd) {
        int index = segmentStart;
        StringBuilder docs = new StringBuilder();
        while (index < segmentEnd) {
            char ch = body.charAt(index);
            if (Character.isWhitespace(ch)) {
                index++;
                continue;
            }
            if (index + 2 < segmentEnd && body.charAt(index) == '/' && body.charAt(index + 1) == '/' && body.charAt(index + 2) == '/') {
                int lineEnd = skipToLineEnd(body, index);
                if (docs.length() > 0) {
                    docs.append('\n');
                }
                docs.append(body, index, Math.min(lineEnd, body.length()));
                index = skipToLineEnd(body, index);
                continue;
            }
            if (index + 1 < segmentEnd && body.charAt(index) == '#' && body.charAt(index + 1) == '[') {
                index = skipAttribute(body, index, segmentEnd);
                continue;
            }
            break;
        }

        if (index >= segmentEnd || !isIdentifierStartChar(body.charAt(index))) {
            return null;
        }

        int nameStart = index;
        index++;
        while (index < segmentEnd && isIdentifierChar(body.charAt(index))) {
            index++;
        }
        String name = body.substring(nameStart, index);
        String variantDoc = docs.isEmpty() ? null : normalizeRustDoc(docs.toString());
        return new EnumVariantInfo(name, bodyStartOffset + nameStart, variantDoc);
    }

    private static int skipToLineEnd(String text, int start) {
        int index = start;
        while (index < text.length() && text.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    private static int skipAttribute(String text, int start, int endExclusive) {
        int index = start + 2;
        int depth = 1;
        while (index < endExclusive) {
            char ch = text.charAt(index);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
            index++;
        }
        return endExclusive;
    }

    private static int findFieldOffsetInText(String text, String structName, String fieldName) {
        Matcher structMatcher = STRUCT_PATTERN.matcher(text);
        while (structMatcher.find()) {
            String matchedStructName = structMatcher.group("name");
            if (matchedStructName == null || !matchedStructName.equals(structName)) {
                continue;
            }

            String body = structMatcher.group("body");
            if (body == null || body.isBlank()) {
                return -1;
            }

            int bodyStart = structMatcher.start("body");
            Matcher fieldMatcher = FIELD_PATTERN.matcher(body);
            while (fieldMatcher.find()) {
                String matchedFieldName = fieldMatcher.group("name");
                if (matchedFieldName == null || !matchedFieldName.equals(fieldName)) {
                    continue;
                }
                return bodyStart + fieldMatcher.start("name");
            }
            return -1;
        }
        return -1;
    }

    private static StructInfo buildStructInfo(String docs, String body) {
        String structDoc = normalizeRustDoc(docs);
        Set<String> fields = new LinkedHashSet<>();
        Map<String, String> fieldDocs = new LinkedHashMap<>();
        Map<String, String> fieldTypes = new LinkedHashMap<>();

        String structBody = body == null ? "" : body;
        Matcher fieldMatcher = FIELD_PATTERN.matcher(structBody);
        while (fieldMatcher.find()) {
            String fieldName = fieldMatcher.group("name");
            if (fieldName == null || fieldName.isBlank()) {
                continue;
            }
            String normalizedFieldName = fieldName.toLowerCase(Locale.ROOT);
            fields.add(fieldName);

            String fieldDoc = normalizeRustDoc(fieldMatcher.group("docs"));
            if (fieldDoc != null && !fieldDoc.isBlank()) {
                fieldDocs.put(normalizedFieldName, fieldDoc);
            }

            String fieldType = extractFieldType(structBody, fieldMatcher.end());
            if (fieldType != null && !fieldType.isBlank()) {
                fieldTypes.put(normalizedFieldName, fieldType);
            }
        }

        return new StructInfo(
                structDoc,
                List.copyOf(fields),
                Map.copyOf(fieldDocs),
                Map.copyOf(fieldTypes),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private static String loadText(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        if (document != null) {
            return document.getText();
        }
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException ignored) {
            return null;
        }
    }

    private List<HtmlFunctionTarget> validateHtmlFunctionTargets(String normalizedFunctionName, List<HtmlFunctionTarget> snapshotTargets) {
        if (snapshotTargets == null || snapshotTargets.isEmpty()) {
            return List.of();
        }

        List<HtmlFunctionTarget> validated = new ArrayList<>();
        for (HtmlFunctionTarget target : snapshotTargets) {
            VirtualFile file = target.file();
            String text = loadText(file);
            if (text == null || text.isBlank() || !text.contains("#[html_fn(")) {
                continue;
            }

            Matcher matcher = HTML_FN_PATTERN.matcher(text);
            while (matcher.find()) {
                String rawName = matcher.group("htmlQuoted");
                if (rawName == null || rawName.isBlank()) {
                    rawName = matcher.group("html");
                }
                if (rawName == null || rawName.isBlank()) {
                    continue;
                }
                if (!normalizedFunctionName.equals(rawName.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                int fnOffset = matcher.start("fn");
                if (fnOffset == target.offset()) {
                    validated.add(target);
                    break;
                }
            }
        }
        return List.copyOf(validated);
    }

    private Map<String, List<HtmlFunctionTarget>> scanHtmlFunctionsByName() {
        Map<String, List<HtmlFunctionTarget>> targetMap = new LinkedHashMap<>();
        List<VirtualFile> files = collectSnapshotFiles(project);
        for (VirtualFile file : files) {
            String text = loadText(file);
            if (text == null || text.isBlank() || !text.contains("#[html_fn(")) {
                continue;
            }
            collectHtmlFunctions(text, file, targetMap);
        }

        Map<String, List<HtmlFunctionTarget>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<HtmlFunctionTarget>> entry : targetMap.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(immutable);
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
            String rawHtmlName = htmlFnMatcher.group("htmlQuoted");
            if (rawHtmlName == null || rawHtmlName.isBlank()) {
                rawHtmlName = htmlFnMatcher.group("html");
            }
            if (rawHtmlName == null || rawHtmlName.isBlank()) {
                continue;
            }
            String htmlName = rawHtmlName.toLowerCase(Locale.ROOT);
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
            targetMap.put(structName.toLowerCase(Locale.ROOT), enrichStructInfoWithMethods(text, structName, info));
        }
    }

    private static StructInfo enrichStructInfoWithMethods(String text, String structName, StructInfo info) {
        if (info == null || text == null || text.isEmpty() || structName == null || structName.isBlank()) {
            return info;
        }

        Set<String> methods = new LinkedHashSet<>(info.methods);
        Map<String, String> methodDocs = new LinkedHashMap<>(info.methodDocs);
        Map<String, String> methodParameters = new LinkedHashMap<>(info.methodParameters);
        Map<String, String> methodReturnTypes = new LinkedHashMap<>(info.methodReturnTypes);
        collectPublicMethodsFromImpls(text, structName, methods, methodDocs, methodParameters, methodReturnTypes);

        return withMethods(info, methods, methodDocs, methodParameters, methodReturnTypes);
    }

    private static StructInfo withMethods(
            StructInfo base,
            Set<String> additionalMethods,
            Map<String, String> additionalMethodDocs,
            Map<String, String> additionalMethodParameters,
            Map<String, String> additionalMethodReturnTypes
    ) {
        Set<String> allMethods = new LinkedHashSet<>(base.methods);
        allMethods.addAll(additionalMethods);

        Map<String, String> allMethodDocs = new LinkedHashMap<>(base.methodDocs);
        allMethodDocs.putAll(additionalMethodDocs);

        Map<String, String> allMethodParameters = new LinkedHashMap<>(base.methodParameters);
        allMethodParameters.putAll(additionalMethodParameters);

        Map<String, String> allMethodReturnTypes = new LinkedHashMap<>(base.methodReturnTypes);
        allMethodReturnTypes.putAll(additionalMethodReturnTypes);

        return new StructInfo(
                base.doc,
                base.fields,
                base.fieldDocs,
                base.fieldTypes,
                List.copyOf(allMethods),
                Map.copyOf(allMethodDocs),
                Map.copyOf(allMethodParameters),
                Map.copyOf(allMethodReturnTypes)
        );
    }

    private static String extractFieldType(String structBody, int typeStartOffset) {
        if (structBody == null || structBody.isEmpty()) {
            return null;
        }
        int start = Math.max(0, typeStartOffset);
        while (start < structBody.length() && Character.isWhitespace(structBody.charAt(start))) {
            start++;
        }
        if (start >= structBody.length()) {
            return null;
        }

        int angleDepth = 0;
        int parenDepth = 0;
        int squareDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int index = start; index < structBody.length(); index++) {
            char ch = structBody.charAt(index);

            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (inChar) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '\'') {
                inChar = true;
                continue;
            }

            if (ch == '<') {
                angleDepth++;
                continue;
            }
            if (ch == '>') {
                if (angleDepth > 0) {
                    angleDepth--;
                }
                continue;
            }
            if (ch == '(') {
                parenDepth++;
                continue;
            }
            if (ch == ')') {
                if (parenDepth > 0) {
                    parenDepth--;
                }
                continue;
            }
            if (ch == '[') {
                squareDepth++;
                continue;
            }
            if (ch == ']') {
                if (squareDepth > 0) {
                    squareDepth--;
                }
                continue;
            }
            if (ch == '{') {
                braceDepth++;
                continue;
            }
            if (ch == '}') {
                if (braceDepth > 0) {
                    braceDepth--;
                } else {
                    return normalizeFieldType(structBody.substring(start, index));
                }
                continue;
            }
            if (ch == ',' && angleDepth == 0 && parenDepth == 0 && squareDepth == 0 && braceDepth == 0) {
                return normalizeFieldType(structBody.substring(start, index));
            }
        }

        return normalizeFieldType(structBody.substring(start));
    }

    private static String normalizeFieldType(String rawType) {
        if (rawType == null) {
            return null;
        }
        String normalized = rawType.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private static void collectPublicMethodsFromImpls(
            String text,
            String structName,
            Set<String> methodNames,
            Map<String, String> methodDocs,
            Map<String, String> methodParameters,
            Map<String, String> methodReturnTypes
    ) {
        int searchOffset = 0;
        while (searchOffset < text.length()) {
            int implStart = text.indexOf("impl", searchOffset);
            if (implStart < 0) {
                return;
            }
            if (!isIdentifierBoundary(text, implStart, implStart + 4)) {
                searchOffset = implStart + 4;
                continue;
            }

            int headerEnd = text.indexOf('{', implStart + 4);
            if (headerEnd < 0) {
                return;
            }

            int blockEnd = findMatchingBrace(text, headerEnd);
            if (blockEnd < 0) {
                return;
            }

            String header = text.substring(implStart, headerEnd);
            if (matchesImplHeaderForStruct(header, structName)) {
                String body = text.substring(headerEnd + 1, blockEnd);
                Matcher methodMatcher = PUB_METHOD_PATTERN.matcher(body);
                while (methodMatcher.find()) {
                    String methodName = methodMatcher.group("name");
                    if (methodName == null || methodName.isBlank()) {
                        continue;
                    }
                    methodNames.add(methodName);
                    String normalizedMethodName = methodName.toLowerCase(Locale.ROOT);

                    String methodDoc = normalizeRustDoc(methodMatcher.group("docs"));
                    if (methodDoc != null && !methodDoc.isBlank()) {
                        methodDocs.put(normalizedMethodName, methodDoc);
                    }

                    int openParenOffset = methodMatcher.end() - 1;
                    int closeParenOffset = findMatchingParen(body, openParenOffset);
                    if (closeParenOffset > openParenOffset) {
                        String rawParams = body.substring(openParenOffset + 1, closeParenOffset);
                        String normalizedParams = normalizeMethodParameters(rawParams);
                        if (normalizedParams != null) {
                            methodParameters.put(normalizedMethodName, normalizedParams);
                        }

                        String returnType = extractMethodReturnType(body, closeParenOffset + 1);
                        if (returnType != null && !returnType.isBlank()) {
                            methodReturnTypes.put(normalizedMethodName, returnType);
                        }
                    }
                }
            }

            searchOffset = blockEnd + 1;
        }
    }

    private static boolean matchesImplHeaderForStruct(String header, String structName) {
        if (header == null || header.isBlank() || structName == null || structName.isBlank()) {
            return false;
        }

        String structPattern = "(?:[A-Za-z_][\\w]*::)*" + Pattern.quote(structName) + "(?:\\b|\\s*<)";
        Pattern inherentImplPattern = Pattern.compile("\\bimpl(?:\\s*<[^>]*>)?\\s+" + structPattern);
        if (inherentImplPattern.matcher(header).find()) {
            return true;
        }

        Pattern traitImplPattern = Pattern.compile("\\bfor\\s+" + structPattern);
        return traitImplPattern.matcher(header).find();
    }

    private static int findMatchingBrace(String text, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findMatchingParen(String text, int openParenIndex) {
        if (openParenIndex < 0 || openParenIndex >= text.length() || text.charAt(openParenIndex) != '(') {
            return -1;
        }

        int depth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;
        for (int i = openParenIndex; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (inChar) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '\'') {
                inChar = true;
                continue;
            }

            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalizeMethodParameters(String rawParameters) {
        if (rawParameters == null) {
            return null;
        }
        String normalized = rawParameters.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized;
    }

    private static String extractMethodReturnType(String text, int afterParamsOffset) {
        if (text == null || text.isEmpty() || afterParamsOffset < 0 || afterParamsOffset >= text.length()) {
            return null;
        }

        int signatureEnd = findMethodSignatureEnd(text, afterParamsOffset);
        if (signatureEnd <= afterParamsOffset) {
            return null;
        }
        String signatureTail = text.substring(afterParamsOffset, signatureEnd);
        int arrowOffset = signatureTail.indexOf("->");
        if (arrowOffset < 0) {
            return null;
        }

        String returnPart = signatureTail.substring(arrowOffset + 2).trim();
        if (returnPart.isEmpty()) {
            return null;
        }

        int whereOffset = findKeywordAtTopLevel(returnPart, "where");
        if (whereOffset >= 0) {
            returnPart = returnPart.substring(0, whereOffset);
        }

        return normalizeFieldType(returnPart);
    }

    private static int findMethodSignatureEnd(String text, int startOffset) {
        int angleDepth = 0;
        int parenDepth = 0;
        int squareDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int index = Math.max(0, startOffset); index < text.length(); index++) {
            char ch = text.charAt(index);

            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (inChar) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '\'') {
                inChar = true;
                continue;
            }

            if (ch == '<') {
                angleDepth++;
                continue;
            }
            if (ch == '>') {
                if (angleDepth > 0) {
                    angleDepth--;
                }
                continue;
            }
            if (ch == '(') {
                parenDepth++;
                continue;
            }
            if (ch == ')') {
                if (parenDepth > 0) {
                    parenDepth--;
                }
                continue;
            }
            if (ch == '[') {
                squareDepth++;
                continue;
            }
            if (ch == ']') {
                if (squareDepth > 0) {
                    squareDepth--;
                }
                continue;
            }
            if (ch == '{' && angleDepth == 0 && parenDepth == 0 && squareDepth == 0 && braceDepth == 0) {
                return index;
            }
            if (ch == ';' && angleDepth == 0 && parenDepth == 0 && squareDepth == 0 && braceDepth == 0) {
                return index;
            }
            if (ch == '{') {
                braceDepth++;
                continue;
            }
            if (ch == '}') {
                if (braceDepth > 0) {
                    braceDepth--;
                }
            }
        }
        return text.length();
    }

    private static int findKeywordAtTopLevel(String text, String keyword) {
        if (text == null || text.isEmpty() || keyword == null || keyword.isBlank()) {
            return -1;
        }

        int angleDepth = 0;
        int parenDepth = 0;
        int squareDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);

            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (inChar) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '\'') {
                    inChar = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '\'') {
                inChar = true;
                continue;
            }

            if (ch == '<') {
                angleDepth++;
                continue;
            }
            if (ch == '>') {
                if (angleDepth > 0) {
                    angleDepth--;
                }
                continue;
            }
            if (ch == '(') {
                parenDepth++;
                continue;
            }
            if (ch == ')') {
                if (parenDepth > 0) {
                    parenDepth--;
                }
                continue;
            }
            if (ch == '[') {
                squareDepth++;
                continue;
            }
            if (ch == ']') {
                if (squareDepth > 0) {
                    squareDepth--;
                }
                continue;
            }
            if (ch == '{') {
                braceDepth++;
                continue;
            }
            if (ch == '}') {
                if (braceDepth > 0) {
                    braceDepth--;
                }
                continue;
            }

            if (angleDepth != 0 || parenDepth != 0 || squareDepth != 0 || braceDepth != 0) {
                continue;
            }
            if (!startsWithKeyword(text, index, keyword)) {
                continue;
            }
            return index;
        }
        return -1;
    }

    private static boolean startsWithKeyword(String text, int offset, String keyword) {
        int keywordLength = keyword.length();
        if (offset < 0 || offset + keywordLength > text.length()) {
            return false;
        }
        if (!text.regionMatches(offset, keyword, 0, keywordLength)) {
            return false;
        }
        if (offset > 0 && isIdentifierChar(text.charAt(offset - 1))) {
            return false;
        }
        int end = offset + keywordLength;
        return end >= text.length() || !isIdentifierChar(text.charAt(end));
    }

    private static boolean isIdentifierBoundary(String text, int start, int end) {
        if (start > 0 && isIdentifierChar(text.charAt(start - 1))) {
            return false;
        }
        return end >= text.length() || !isIdentifierChar(text.charAt(end));
    }

    private static boolean isIdentifierStartChar(char ch) {
        return ch == '_' || Character.isLetter(ch);
    }

    private static boolean isIdentifierChar(char ch) {
        return ch == '_' || Character.isLetterOrDigit(ch);
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
