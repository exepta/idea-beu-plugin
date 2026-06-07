package com.tiltus.beu.plugin.actions;

import com.intellij.ide.IdeView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NewBeuComponentAction extends AnAction {
    private static final String ASSETS_COMPONENTS_SEGMENT = "/assets/components/";
    private static final Pattern MOD_LINE_PATTERN = Pattern.compile("(?m)^\\s*mod\\s+[A-Za-z_][A-Za-z0-9_]*\\s*;\\s*$");
    private static final Pattern USE_LINE_PATTERN = Pattern.compile("(?m)^\\s*use\\s+.+;\\s*$");

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiDirectory targetDirectory = resolveTargetDirectory(event);
        boolean enabled = project != null && targetDirectory != null && targetDirectory.isWritable();
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiDirectory targetDirectory = resolveTargetDirectory(event);
        if (project == null || targetDirectory == null) {
            return;
        }

        SelectGenerateTargetDialog selectGenerateTargetDialog = new SelectGenerateTargetDialog(project);
        if (!selectGenerateTargetDialog.showAndGet()) {
            return;
        }
        GenerateTarget generateTarget = selectGenerateTargetDialog.selectedTarget();
        if (generateTarget == null) {
            return;
        }

        if (generateTarget == GenerateTarget.COMPONENT) {
            generateComponent(project, targetDirectory);
            return;
        }
        generateRoutes(project, targetDirectory);
    }

    private static void generateComponent(Project project, PsiDirectory targetDirectory) {
        String defaultRegistryPath = resolveDefaultRegistryPath(project, targetDirectory);
        CreateBeuComponentDialog dialog = new CreateBeuComponentDialog(project, defaultRegistryPath);
        if (!dialog.showAndGet()) {
            return;
        }

        ParsedComponentPath parsedComponentPath = parseComponentPathInput(dialog.componentInput());
        if (parsedComponentPath == null) {
            Messages.showErrorDialog(project, "Please enter a valid component name.", "Invalid beu component name");
            return;
        }

        String registryPath = dialog.effectiveRegistryPath();
        if (registryPath.isBlank()) {
            Messages.showErrorDialog(
                    project,
                    "Default main.rs could not be resolved. Enable override and provide a registry file path.",
                    "Missing registry file"
            );
            return;
        }

        try {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                CreatedComponent createdComponent = createComponentFiles(targetDirectory, parsedComponentPath);
                appendRegistryEntry(project, registryPath, createdComponent);
            });
        } catch (IllegalArgumentException error) {
            Messages.showErrorDialog(project, error.getMessage(), "Could not create beu component");
        }
    }

    private static void generateRoutes(Project project, PsiDirectory targetDirectory) {
        CreateBeuRoutesDialog dialog = new CreateBeuRoutesDialog(project, findExistingRoutesFiles(project));
        if (!dialog.showAndGet()) {
            return;
        }

        ParsedComponentPath parsedComponentPath = parseComponentPathInput(dialog.routeInput());
        if (parsedComponentPath == null) {
            Messages.showErrorDialog(project, "Please enter a valid routes name.", "Invalid beu routes name");
            return;
        }

        try {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                CreatedRoutes createdRoutes = createRoutesFile(targetDirectory, parsedComponentPath);
                appendRoutesModuleEntry(project, targetDirectory, dialog.selectedRoutesContainerPath(), createdRoutes.routesFile());
            });
        } catch (IllegalArgumentException error) {
            Messages.showErrorDialog(project, error.getMessage(), "Could not create beu routes");
        }
    }

    private static PsiDirectory resolveTargetDirectory(AnActionEvent event) {
        IdeView ideView = event.getData(LangDataKeys.IDE_VIEW);
        if (ideView == null) {
            return null;
        }
        PsiDirectory[] directories = ideView.getDirectories();
        return directories.length == 0 ? null : directories[0];
    }

    private static ParsedComponentPath parseComponentPathInput(String userInput) {
        String normalizedInput = userInput.trim().replace('\\', '/');
        normalizedInput = normalizedInput.replaceAll("/+", "/");
        if (normalizedInput.isBlank()) {
            return null;
        }
        if (normalizedInput.startsWith("/") || normalizedInput.endsWith("/")) {
            return null;
        }

        String[] pathSegments = normalizedInput.split("/");
        List<String> targetDirectories = new ArrayList<>();
        for (int i = 0; i < pathSegments.length - 1; i++) {
            String directoryName = pathSegments[i].trim();
            if (!isValidPathSegment(directoryName)) {
                return null;
            }
            targetDirectories.add(directoryName);
        }

        String componentBaseName = pathSegments[pathSegments.length - 1].trim();
        if (!isValidPathSegment(componentBaseName)) {
            return null;
        }

        return new ParsedComponentPath(targetDirectories, componentBaseName);
    }

    private static CreatedComponent createComponentFiles(PsiDirectory baseDirectory, ParsedComponentPath parsedComponentPath) {
        PsiDirectory targetDirectory = baseDirectory;
        for (String directoryName : parsedComponentPath.targetDirectories()) {
            PsiDirectory existingSubdirectory = targetDirectory.findSubdirectory(directoryName);
            targetDirectory = existingSubdirectory != null ? existingSubdirectory : targetDirectory.createSubdirectory(directoryName);
        }

        String componentName = parsedComponentPath.componentBaseName();
        String rustFileName = componentName + ".component.rs";
        String htmlFileName = componentName + ".component.html";
        String cssFileName = componentName + ".component.css";

        ensureMissing(targetDirectory.findFile(rustFileName), rustFileName);
        ensureMissing(targetDirectory.findFile(htmlFileName), htmlFileName);
        ensureMissing(targetDirectory.findFile(cssFileName), cssFileName);

        PsiFile rustFile = targetDirectory.createFile(rustFileName);
        try {
            VfsUtil.saveText(rustFile.getVirtualFile(), buildRustComponent(componentName));
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not write file: " + rustFileName);
        }
        targetDirectory.createFile(htmlFileName);
        targetDirectory.createFile(cssFileName);
        return new CreatedComponent(componentName, rustFile.getVirtualFile());
    }

    private static CreatedRoutes createRoutesFile(PsiDirectory baseDirectory, ParsedComponentPath parsedComponentPath) {
        PsiDirectory targetDirectory = baseDirectory;
        for (String directoryName : parsedComponentPath.targetDirectories()) {
            PsiDirectory existingSubdirectory = targetDirectory.findSubdirectory(directoryName);
            targetDirectory = existingSubdirectory != null ? existingSubdirectory : targetDirectory.createSubdirectory(directoryName);
        }

        String routeName = parsedComponentPath.componentBaseName();
        String routesFileName = routeName + ".routes.rs";
        ensureMissing(targetDirectory.findFile(routesFileName), routesFileName);

        PsiFile routesFile = targetDirectory.createFile(routesFileName);
        try {
            VfsUtil.saveText(routesFile.getVirtualFile(), buildRoutesFile(routeName));
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not write file: " + routesFileName);
        }
        return new CreatedRoutes(routeName, routesFile.getVirtualFile());
    }

    private static void appendRoutesModuleEntry(Project project,
                                                PsiDirectory targetDirectory,
                                                String selectedContainerRelativePath,
                                                VirtualFile createdRoutesFile) {
        if (selectedContainerRelativePath == null || selectedContainerRelativePath.isBlank()) {
            return;
        }

        VirtualFile routesContainerFile = resolveOrCreateRoutesContainerFile(project, targetDirectory, selectedContainerRelativePath);
        if (routesContainerFile == null || routesContainerFile.isDirectory()) {
            throw new IllegalArgumentException("Routes container file not found: " + selectedContainerRelativePath);
        }
        if (!routesContainerFile.isWritable()) {
            throw new IllegalArgumentException("Routes container file is not writable: " + routesContainerFile.getPath());
        }
        if (routesContainerFile.getPath().equals(createdRoutesFile.getPath())) {
            return;
        }

        String relativePath = relativeComponentPath(routesContainerFile, createdRoutesFile);
        String modName = toSnakeIdentifier(removeTrailingRs(createdRoutesFile.getName()));
        String modLine = "mod " + modName + ";";
        String moduleSnippet = "#[path = \"" + relativePath + "\"]\n" + modLine + "\n";

        Document document = FileDocumentManager.getInstance().getDocument(routesContainerFile);
        if (document == null) {
            throw new IllegalArgumentException("Could not open routes container file: " + routesContainerFile.getPath());
        }
        String text = document.getText();
        if (text.contains(modLine)) {
            return;
        }

        int insertionOffset = resolveRoutesInsertionOffset(text);
        String prefix = insertionOffset > 0 ? "\n\n" : "";
        String updatedText = text.substring(0, insertionOffset) + prefix + moduleSnippet + text.substring(insertionOffset);
        document.setText(updatedText);
        PsiDocumentManager.getInstance(project).commitDocument(document);
        FileDocumentManager.getInstance().saveDocument(document);
    }

    private static int resolveRoutesInsertionOffset(String text) {
        int lastModEnd = -1;
        Matcher modMatcher = MOD_LINE_PATTERN.matcher(text);
        while (modMatcher.find()) {
            lastModEnd = modMatcher.end();
        }
        if (lastModEnd >= 0) {
            return lastModEnd;
        }

        int lastUseEnd = -1;
        Matcher useMatcher = USE_LINE_PATTERN.matcher(text);
        while (useMatcher.find()) {
            lastUseEnd = useMatcher.end();
        }
        return Math.max(lastUseEnd, 0);
    }

    private static @Nullable VirtualFile resolveOrCreateRoutesContainerFile(Project project,
                                                                            PsiDirectory targetDirectory,
                                                                            String relativePath) {
        VirtualFile assetsComponentsRoot = resolveAssetsComponentsRoot(project, targetDirectory);
        if (assetsComponentsRoot == null) {
            return null;
        }

        String normalizedPath = relativePath.trim().replace('\\', '/').replaceAll("/+", "/");
        if (normalizedPath.startsWith("/") || normalizedPath.endsWith("/")) {
            throw new IllegalArgumentException("Invalid routes container path: " + relativePath);
        }
        String[] segments = normalizedPath.split("/");
        if (segments.length == 0) {
            throw new IllegalArgumentException("Invalid routes container path: " + relativePath);
        }

        VirtualFile currentDirectory = assetsComponentsRoot;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i].trim();
            if (!isValidPathSegment(segment)) {
                throw new IllegalArgumentException("Invalid routes container path: " + relativePath);
            }
            VirtualFile childDirectory = currentDirectory.findChild(segment);
            if (childDirectory == null) {
                try {
                    childDirectory = currentDirectory.createChildDirectory(NewBeuComponentAction.class, segment);
                } catch (Exception error) {
                    throw new IllegalArgumentException("Could not create directory: " + segment);
                }
            }
            currentDirectory = childDirectory;
        }

        String fileName = segments[segments.length - 1].trim();
        if (!isValidPathSegment(fileName) || !fileName.endsWith(".routes.rs")) {
            throw new IllegalArgumentException("Invalid routes container file: " + relativePath);
        }

        VirtualFile containerFile = currentDirectory.findChild(fileName);
        if (containerFile == null) {
            try {
                containerFile = currentDirectory.createChildData(NewBeuComponentAction.class, fileName);
                VfsUtil.saveText(containerFile, buildRoutesFile(removeRoutesFileSuffix(fileName)));
            } catch (Exception error) {
                throw new IllegalArgumentException("Could not create routes container file: " + relativePath);
            }
        }
        return containerFile;
    }

    private static @Nullable VirtualFile resolveAssetsComponentsRoot(Project project, PsiDirectory targetDirectory) {
        VirtualFile current = targetDirectory.getVirtualFile();
        while (current != null) {
            String path = current.getPath().replace('\\', '/');
            if (path.endsWith("/assets/components")) {
                return current;
            }
            current = current.getParent();
        }

        String projectBasePath = project.getBasePath();
        if (projectBasePath == null || projectBasePath.isBlank()) {
            return null;
        }
        String rootPath = Paths.get(projectBasePath, "assets", "components").toString().replace('\\', '/');
        VirtualFile root = LocalFileSystem.getInstance().refreshAndFindFileByPath(rootPath);
        if (root != null) {
            return root;
        }

        VirtualFile assetsDir = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(Paths.get(projectBasePath, "assets").toString().replace('\\', '/'));
        if (assetsDir == null) {
            try {
                VirtualFile projectRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(projectBasePath.replace('\\', '/'));
                if (projectRoot != null) {
                    assetsDir = projectRoot.createChildDirectory(NewBeuComponentAction.class, "assets");
                }
            } catch (Exception error) {
                throw new IllegalArgumentException("Could not create assets/components directory.");
            }
        }
        if (assetsDir == null) {
            return null;
        }
        try {
            VirtualFile componentsDir = assetsDir.findChild("components");
            if (componentsDir == null) {
                componentsDir = assetsDir.createChildDirectory(NewBeuComponentAction.class, "components");
            }
            return componentsDir;
        } catch (Exception error) {
            throw new IllegalArgumentException("Could not create assets/components directory.");
        }
    }

    private static List<String> findExistingRoutesFiles(Project project) {
        Collection<VirtualFile> rustFiles = FilenameIndex.getAllFilesByExt(project, "rs", GlobalSearchScope.projectScope(project));
        Set<String> collectedPaths = new LinkedHashSet<>();
        for (VirtualFile rustFile : rustFiles) {
            if (rustFile.isDirectory()) {
                continue;
            }
            String fullPath = rustFile.getPath().replace('\\', '/');
            if (!fullPath.endsWith(".routes.rs")) {
                continue;
            }
            int markerIndex = fullPath.indexOf(ASSETS_COMPONENTS_SEGMENT);
            if (markerIndex < 0) {
                continue;
            }
            String relativePath = fullPath.substring(markerIndex + ASSETS_COMPONENTS_SEGMENT.length());
            if (!relativePath.isBlank()) {
                collectedPaths.add(relativePath);
            }
        }
        List<String> paths = new ArrayList<>(collectedPaths);
        Collections.sort(paths);
        if (!paths.contains("beu.routes.rs")) {
            paths.add(0, "beu.routes.rs");
        }
        return paths;
    }

    private static void appendRegistryEntry(Project project, String registryFilePathInput, CreatedComponent createdComponent) {
        VirtualFile registryFile = resolveRegistryFile(project, registryFilePathInput);
        if (registryFile == null || registryFile.isDirectory()) {
            throw new IllegalArgumentException("Registry file not found: " + registryFilePathInput);
        }
        if (!registryFile.isWritable()) {
            throw new IllegalArgumentException("Registry file is not writable: " + registryFile.getPath());
        }

        Document document = FileDocumentManager.getInstance().getDocument(registryFile);
        if (document == null) {
            throw new IllegalArgumentException("Could not open registry file: " + registryFile.getPath());
        }

        String componentModuleName = toSnakeIdentifier(createdComponent.componentName()) + "_component_mod";
        String relativeComponentFilePath = relativeComponentPath(registryFile, createdComponent.rustFile());
        String registryModuleSnippet = buildRegistrySnippet(relativeComponentFilePath, componentModuleName);

        if (document.getText().contains("mod " + componentModuleName + ";")) {
            return;
        }

        String existingContent = document.getText();
        String spacingPrefix;
        if (existingContent.isBlank()) {
            spacingPrefix = "";
        } else if (existingContent.endsWith("\n\n")) {
            spacingPrefix = "";
        } else if (existingContent.endsWith("\n")) {
            spacingPrefix = "\n";
        } else {
            spacingPrefix = "\n\n";
        }
        document.setText(existingContent + spacingPrefix + registryModuleSnippet);
        PsiDocumentManager.getInstance(project).commitDocument(document);
        FileDocumentManager.getInstance().saveDocument(document);
    }

    private static @Nullable VirtualFile resolveRegistryFile(Project project, String registryPathInput) {
        String normalizedInput = registryPathInput.trim().replace('\\', '/');
        if (normalizedInput.isBlank()) {
            return null;
        }

        Path candidatePath;
        try {
            candidatePath = Paths.get(normalizedInput);
        } catch (InvalidPathException ignored) {
            return null;
        }

        if (!candidatePath.isAbsolute()) {
            String basePath = project.getBasePath();
            if (basePath == null || basePath.isBlank()) {
                return null;
            }
            candidatePath = Paths.get(basePath).resolve(candidatePath);
        }

        candidatePath = candidatePath.normalize();
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(candidatePath.toString().replace('\\', '/'));
    }

    private static String resolveDefaultRegistryPath(Project project, PsiDirectory targetDirectory) {
        VirtualFile registryMarkerFile = findBeuRegistryFile(project, targetDirectory.getVirtualFile());
        if (registryMarkerFile != null) {
            return registryMarkerFile.getPath();
        }

        VirtualFile nearestMainRs = findMainRsInAncestors(targetDirectory.getVirtualFile(), project.getBasePath());
        if (nearestMainRs != null) {
            return nearestMainRs.getPath();
        }

        String projectBasePath = project.getBasePath();
        if (projectBasePath != null && !projectBasePath.isBlank()) {
            VirtualFile srcMain = LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(Paths.get(projectBasePath, "src", "main.rs").toString().replace('\\', '/'));
            if (srcMain != null && !srcMain.isDirectory()) {
                return srcMain.getPath();
            }

            VirtualFile rootMain = LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(Paths.get(projectBasePath, "main.rs").toString().replace('\\', '/'));
            if (rootMain != null && !rootMain.isDirectory()) {
                return rootMain.getPath();
            }
        }

        Collection<VirtualFile> projectMainFiles = FilenameIndex.getVirtualFilesByName(project, "main.rs", GlobalSearchScope.projectScope(project));
        VirtualFile shortestPathMainFile = null;
        for (VirtualFile candidate : projectMainFiles) {
            if (candidate.isDirectory()) {
                continue;
            }
            if (shortestPathMainFile == null || candidate.getPath().length() < shortestPathMainFile.getPath().length()) {
                shortestPathMainFile = candidate;
            }
        }
        return shortestPathMainFile == null ? "" : shortestPathMainFile.getPath();
    }

    private static @Nullable VirtualFile findBeuRegistryFile(Project project, VirtualFile targetDirectory) {
        Collection<VirtualFile> rustFiles = FilenameIndex.getAllFilesByExt(project, "rs", GlobalSearchScope.projectScope(project));
        List<VirtualFile> candidates = new ArrayList<>();
        for (VirtualFile rustFile : rustFiles) {
            if (rustFile.isDirectory()) {
                continue;
            }
            try {
                String text = VfsUtilCore.loadText(rustFile);
                if (text.contains("#[beu_registry]")) {
                    candidates.add(rustFile);
                }
            } catch (Exception ignored) {
                // Ignore unreadable files and continue with remaining candidates.
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        VirtualFile bestCandidate = null;
        int bestCandidateScore = Integer.MIN_VALUE;
        String selectedDirectoryPath = targetDirectory.getPath();
        for (VirtualFile candidate : candidates) {
            VirtualFile parentDirectory = candidate.getParent();
            String parentDirectoryPath = parentDirectory == null ? "" : parentDirectory.getPath();
            int score;
            if (!parentDirectoryPath.isEmpty() && selectedDirectoryPath.startsWith(parentDirectoryPath + "/")) {
                score = 100_000 + parentDirectoryPath.length();
            } else {
                score = -candidate.getPath().length();
            }
            if (score > bestCandidateScore) {
                bestCandidateScore = score;
                bestCandidate = candidate;
            }
        }
        return bestCandidate;
    }

    private static @Nullable VirtualFile findMainRsInAncestors(VirtualFile startDirectory, String projectBasePath) {
        String normalizedProjectBasePath = projectBasePath == null ? null : projectBasePath.replace('\\', '/');
        VirtualFile currentDirectory = startDirectory;
        while (currentDirectory != null) {
            VirtualFile mainRsFile = currentDirectory.findChild("main.rs");
            if (mainRsFile != null && !mainRsFile.isDirectory()) {
                return mainRsFile;
            }
            if (normalizedProjectBasePath != null && normalizedProjectBasePath.equals(currentDirectory.getPath())) {
                break;
            }
            currentDirectory = currentDirectory.getParent();
        }
        return null;
    }

    private static void ensureMissing(PsiFile file, String fileName) {
        if (file != null) {
            throw new IllegalArgumentException("File already exists: " + fileName);
        }
    }

    private static String buildRustComponent(String componentName) {
        String structName = toPascalIdentifier(componentName) + "Component";
        String constName = toUpperSnakeIdentifier(componentName) + "_COMPONENT";
        return "use bevy::prelude::*;\n" +
                "use bevy_extended_ui_macros::*;\n" +
                "\n" +
                "#[ui_component]\n" +
                "pub struct " + structName + " {\n" +
                "    pub template_name: &'static str,\n" +
                "    pub template_file: &'static str,\n" +
                "    pub styles: &'static [&'static str],\n" +
                "}\n" +
                "\n" +
                "pub const " + constName + ": " + structName + " = " + structName + " {\n" +
                "    template_name: \"app-" + componentName + "\",\n" +
                "    template_file: \"" + componentName + ".component.html\",\n" +
                "    styles: &[\"" + componentName + ".component.css\"],\n" +
                "};\n" +
                "\n" +
                "/// Called at initialize the component. You can register or define for example resources here!\n" +
                "#[component_init]\n" +
                "pub fn constructor(mut commands: Commands) {\n" +
                "}\n";
    }

    private static String buildRoutesFile(String routeName) {
        if ("beu".equalsIgnoreCase(routeName)) {
            return "use bevy_extended_ui::routing::Routes;\n" +
                    "use bevy_extended_ui_macros::beu_routes;\n" +
                    "\n" +
                    "#[beu_routes]\n" +
                    "pub fn routes() -> Routes {\n" +
                    "    Routes::new()\n" +
                    "        .route(\"/\", \"app-main\")\n" +
                    "        .redirect(\"\", \"/\")\n" +
                    "        .fallback(\"app-main\")\n" +
                    "}\n";
        }
        return "use bevy_extended_ui::routing::Routes;\n" +
                "\n" +
                "pub fn " + routeName + "() -> Routes {\n" +
                    "    Routes::new()\n" +
                    "        .route(\"/" + routeName + "\", \"\")\n" +
                "}\n";
    }

    private static String buildRegistrySnippet(String relativePath, String modName) {
        return "#[cfg(feature = \"extended-framework\")]\n" +
                "#[allow(dead_code)]\n" +
                "#[path = \"" + relativePath + "\"]\n" +
                "mod " + modName + ";\n\n";
    }

    private static String relativeComponentPath(VirtualFile registryFile, VirtualFile componentRustFile) {
        VirtualFile registryParent = registryFile.getParent();
        if (registryParent == null) {
            return componentRustFile.getPath().replace('\\', '/');
        }
        try {
            Path parentDirectoryPath = Paths.get(registryParent.getPath());
            Path componentPath = Paths.get(componentRustFile.getPath());
            String relativePath = parentDirectoryPath.relativize(componentPath).toString().replace('\\', '/');
            return relativePath.isBlank() ? componentRustFile.getName() : relativePath;
        } catch (Exception ignored) {
            return componentRustFile.getPath().replace('\\', '/');
        }
    }

    private static String toPascalIdentifier(String name) {
        List<String> parts = toAlphanumericTokens(name);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            result.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                result.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        if (result.isEmpty()) {
            return "Main";
        }
        if (!isValidRustIdentifierStart(result.charAt(0))) {
            result.insert(0, "Component");
        }
        return result.toString();
    }

    private static String toUpperSnakeIdentifier(String name) {
        List<String> parts = toAlphanumericTokens(name);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!result.isEmpty()) {
                result.append('_');
            }
            result.append(part.toUpperCase(Locale.ROOT));
        }
        if (result.isEmpty()) {
            return "MAIN";
        }
        if (!isValidRustIdentifierStart(result.charAt(0))) {
            result.insert(0, "COMPONENT_");
        }
        return result.toString();
    }

    private static String toSnakeIdentifier(String name) {
        List<String> parts = toAlphanumericTokens(name);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (!result.isEmpty()) {
                result.append('_');
            }
            result.append(part.toLowerCase(Locale.ROOT));
        }
        if (result.isEmpty()) {
            return "main";
        }
        if (!isValidRustIdentifierStart(result.charAt(0))) {
            result.insert(0, "component_");
        }
        return result.toString();
    }

    private static List<String> toAlphanumericTokens(String name) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char currentChar = name.charAt(index);
            if (Character.isLetterOrDigit(currentChar)) {
                currentToken.append(currentChar);
                continue;
            }
            if (!currentToken.isEmpty()) {
                tokens.add(currentToken.toString());
                currentToken.setLength(0);
            }
        }
        if (!currentToken.isEmpty()) {
            tokens.add(currentToken.toString());
        }
        return tokens;
    }

    private static boolean isValidPathSegment(String segment) {
        if (segment.isBlank()) {
            return false;
        }
        if (".".equals(segment) || "..".equals(segment)) {
            return false;
        }
        for (int index = 0; index < segment.length(); index++) {
            char currentChar = segment.charAt(index);
            if (currentChar < 32) {
                return false;
            }
            if ("\\/:*?\"<>|".indexOf(currentChar) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidRustIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static String removeTrailingRs(String fileName) {
        if (fileName.endsWith(".rs")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }

    private static String removeRoutesFileSuffix(String fileName) {
        if (fileName.endsWith(".routes.rs")) {
            return fileName.substring(0, fileName.length() - ".routes.rs".length());
        }
        return removeTrailingRs(fileName);
    }

    private enum GenerateTarget {
        COMPONENT("Component : {name}.component.rs | html | css"),
        ROUTES("Routes : {name}.routes.rs");

        private final String displayName;

        GenerateTarget(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private record CreatedComponent(String componentName, VirtualFile rustFile) {
    }

    private record CreatedRoutes(String routeName, VirtualFile routesFile) {
    }

    private record ParsedComponentPath(List<String> targetDirectories, String componentBaseName) {
    }

    private static final class SelectGenerateTargetDialog extends DialogWrapper {
        private final JBTextField searchField = new JBTextField();
        private final DefaultListModel<GenerateTarget> listModel = new DefaultListModel<>();
        private final JBList<GenerateTarget> optionsList = new JBList<>(listModel);

        private SelectGenerateTargetDialog(Project project) {
            super(project);
            setTitle("Beu Generate");
            init();
            setOKActionEnabled(false);
            refillList("");
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.weightx = 1.0;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(0, 0, 6, 0);
            panel.add(new JBLabel("Select what to generate."), constraints);

            constraints.gridy++;
            panel.add(searchField, constraints);

            constraints.gridy++;
            constraints.weighty = 1.0;
            constraints.fill = GridBagConstraints.BOTH;
            constraints.insets = new Insets(6, 0, 0, 0);
            optionsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            panel.add(new JBScrollPane(optionsList), constraints);

            searchField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent event) {
                    refillList(searchField.getText());
                }

                @Override
                public void removeUpdate(DocumentEvent event) {
                    refillList(searchField.getText());
                }

                @Override
                public void changedUpdate(DocumentEvent event) {
                    refillList(searchField.getText());
                }
            });
            optionsList.addListSelectionListener(event -> setOKActionEnabled(optionsList.getSelectedValue() != null));
            optionsList.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2 && optionsList.getSelectedValue() != null) {
                        doOKAction();
                    }
                }
            });
            return panel;
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return searchField;
        }

        private @Nullable GenerateTarget selectedTarget() {
            return optionsList.getSelectedValue();
        }

        private void refillList(String query) {
            String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            listModel.clear();
            for (GenerateTarget target : GenerateTarget.values()) {
                String display = target.displayName().toLowerCase(Locale.ROOT);
                if (normalizedQuery.isBlank() || display.contains(normalizedQuery)) {
                    listModel.addElement(target);
                }
            }
            if (!listModel.isEmpty()) {
                optionsList.setSelectedIndex(0);
                setOKActionEnabled(true);
            } else {
                setOKActionEnabled(false);
            }
        }
    }

    private static final class CreateBeuComponentDialog extends DialogWrapper {
        private final String defaultRegistryPath;
        private final JBTextField componentNameField = new JBTextField();
        private final JBCheckBox overrideRegistryCheckbox = new JBCheckBox("Override default registry");
        private final JBTextField registryPathField = new JBTextField();

        private CreateBeuComponentDialog(Project project, String defaultRegistryPath) {
            super(project);
            this.defaultRegistryPath = defaultRegistryPath == null ? "" : defaultRegistryPath;
            setTitle("New Beu Component");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.weightx = 1.0;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(0, 0, 6, 0);

            panel.add(new JBLabel("give your component a name."), constraints);

            constraints.gridy++;
            constraints.insets = new Insets(0, 0, 10, 0);
            panel.add(componentNameField, constraints);

            constraints.gridy++;
            constraints.insets = new Insets(0, 0, 6, 0);
            panel.add(overrideRegistryCheckbox, constraints);

            constraints.gridy++;
            constraints.insets = new Insets(0, 0, 0, 0);
            registryPathField.setText(defaultRegistryPath);
            registryPathField.setEnabled(false);
            panel.add(registryPathField, constraints);

            overrideRegistryCheckbox.addActionListener(event -> registryPathField.setEnabled(overrideRegistryCheckbox.isSelected()));
            return panel;
        }

        private String componentInput() {
            return componentNameField.getText();
        }

        private String effectiveRegistryPath() {
            if (!overrideRegistryCheckbox.isSelected()) {
                return defaultRegistryPath;
            }
            return registryPathField.getText().trim();
        }
    }

    private static final class CreateBeuRoutesDialog extends DialogWrapper {
        private final JBTextField routeNameField = new JBTextField();
        private final JComboBox<String> routesContainerCombo;

        private CreateBeuRoutesDialog(Project project, List<String> existingRoutesFiles) {
            super(project);
            setTitle("New Beu Routes");
            routesContainerCombo = new JComboBox<>(existingRoutesFiles.toArray(new String[0]));
            routesContainerCombo.setEditable(false);
            routesContainerCombo.setSelectedItem("beu.routes.rs");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.weightx = 1.0;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(0, 0, 6, 0);

            panel.add(new JBLabel("give your routes file a name."), constraints);

            constraints.gridy++;
            constraints.insets = new Insets(0, 0, 0, 0);
            panel.add(routeNameField, constraints);

            constraints.gridy++;
            constraints.insets = new Insets(8, 0, 6, 0);
            panel.add(new JBLabel("Routes container file."), constraints);

            constraints.gridy++;
            constraints.insets = new Insets(0, 0, 0, 0);
            panel.add(routesContainerCombo, constraints);
            return panel;
        }

        private String routeInput() {
            return routeNameField.getText();
        }

        private String selectedRoutesContainerPath() {
            Object selectedValue = routesContainerCombo.getSelectedItem();
            if (selectedValue instanceof String value) {
                return value.trim();
            }
            return "beu.routes.rs";
        }
    }
}
