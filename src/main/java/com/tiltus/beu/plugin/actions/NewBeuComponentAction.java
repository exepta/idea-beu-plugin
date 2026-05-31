package com.tiltus.beu.plugin.actions;

import com.intellij.ide.IdeView;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
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
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class NewBeuComponentAction extends AnAction {
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

        String defaultRegistryPath = resolveDefaultRegistryPath(project, targetDirectory);
        CreateBeuComponentDialog dialog = new CreateBeuComponentDialog(project, defaultRegistryPath);
        if (!dialog.showAndGet()) {
            return;
        }

        ParsedInput parsedInput = parseInput(dialog.componentInput());
        if (parsedInput == null) {
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
                CreatedComponent createdComponent = createComponentFiles(project, targetDirectory, parsedInput);
                prependRegistryEntry(project, registryPath, createdComponent);
            });
        } catch (IllegalArgumentException error) {
            Messages.showErrorDialog(project, error.getMessage(), "Could not create beu component");
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

    private static ParsedInput parseInput(String userInput) {
        String normalized = userInput.trim().replace('\\', '/');
        normalized = normalized.replaceAll("/+", "/");
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.startsWith("/") || normalized.endsWith("/")) {
            return null;
        }

        String[] segments = normalized.split("/");
        List<String> folders = new ArrayList<>();
        for (int i = 0; i < segments.length - 1; i++) {
            String folder = segments[i].trim();
            if (!isValidPathSegment(folder)) {
                return null;
            }
            folders.add(folder);
        }

        String componentName = segments[segments.length - 1].trim();
        if (!isValidPathSegment(componentName)) {
            return null;
        }

        return new ParsedInput(folders, componentName);
    }

    private static CreatedComponent createComponentFiles(Project project, PsiDirectory baseDirectory, ParsedInput parsedInput) {
        PsiDirectory targetDirectory = baseDirectory;
        for (String folder : parsedInput.folders()) {
            PsiDirectory existing = targetDirectory.findSubdirectory(folder);
            targetDirectory = existing != null ? existing : targetDirectory.createSubdirectory(folder);
        }

        String componentName = parsedInput.componentName();
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

    private static void prependRegistryEntry(Project project, String registryPathInput, CreatedComponent createdComponent) {
        VirtualFile registryFile = resolveRegistryFile(project, registryPathInput);
        if (registryFile == null || registryFile.isDirectory()) {
            throw new IllegalArgumentException("Registry file not found: " + registryPathInput);
        }
        if (!registryFile.isWritable()) {
            throw new IllegalArgumentException("Registry file is not writable: " + registryFile.getPath());
        }

        var document = FileDocumentManager.getInstance().getDocument(registryFile);
        if (document == null) {
            throw new IllegalArgumentException("Could not open registry file: " + registryFile.getPath());
        }

        String modName = toSnakeIdentifier(createdComponent.componentName()) + "_component_mod";
        String relativePath = relativeComponentPath(registryFile, createdComponent.rustFile());
        String snippet = buildRegistrySnippet(relativePath, modName);

        if (document.getText().contains("mod " + modName + ";")) {
            return;
        }

        String original = document.getText();
        String separator;
        if (original.isBlank()) {
            separator = "";
        } else if (original.endsWith("\n\n")) {
            separator = "";
        } else if (original.endsWith("\n")) {
            separator = "\n";
        } else {
            separator = "\n\n";
        }
        document.setText(original + separator + snippet);
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
        VirtualFile beuRegistry = findBeuRegistryFile(project, targetDirectory.getVirtualFile());
        if (beuRegistry != null) {
            return beuRegistry.getPath();
        }

        VirtualFile ancestorMain = findMainRsInAncestors(targetDirectory.getVirtualFile(), project.getBasePath());
        if (ancestorMain != null) {
            return ancestorMain.getPath();
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

        Collection<VirtualFile> projectMains = FilenameIndex.getVirtualFilesByName(project, "main.rs", GlobalSearchScope.projectScope(project));
        VirtualFile selected = null;
        for (VirtualFile candidate : projectMains) {
            if (candidate.isDirectory()) {
                continue;
            }
            if (selected == null || candidate.getPath().length() < selected.getPath().length()) {
                selected = candidate;
            }
        }
        return selected == null ? "" : selected.getPath();
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

        VirtualFile best = null;
        int bestScore = Integer.MIN_VALUE;
        String selectedPath = targetDirectory.getPath();
        for (VirtualFile candidate : candidates) {
            VirtualFile parent = candidate.getParent();
            String parentPath = parent == null ? "" : parent.getPath();
            int score;
            if (!parentPath.isEmpty() && selectedPath.startsWith(parentPath + "/")) {
                score = 100_000 + parentPath.length();
            } else {
                score = -candidate.getPath().length();
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static @Nullable VirtualFile findMainRsInAncestors(VirtualFile startDirectory, String projectBasePath) {
        String normalizedBase = projectBasePath == null ? null : projectBasePath.replace('\\', '/');
        VirtualFile current = startDirectory;
        while (current != null) {
            VirtualFile child = current.findChild("main.rs");
            if (child != null && !child.isDirectory()) {
                return child;
            }
            if (normalizedBase != null && normalizedBase.equals(current.getPath())) {
                break;
            }
            current = current.getParent();
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
        return "use bevy_extended_ui_macros::*;\n" +
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
                "};\n";
    }

    private static String buildRegistrySnippet(String relativePath, String modName) {
        return "#[cfg(feature = \"extended-framework\")]\n" +
                "#[allow(dead_code)]\n" +
                "#[path = \"" + relativePath + "\"]\n" +
                "mod " + modName + ";\n\n";
    }

    private static String relativeComponentPath(VirtualFile registryFile, VirtualFile componentRsFile) {
        VirtualFile registryParent = registryFile.getParent();
        if (registryParent == null) {
            return componentRsFile.getPath().replace('\\', '/');
        }
        try {
            Path from = Paths.get(registryParent.getPath());
            Path to = Paths.get(componentRsFile.getPath());
            String relative = from.relativize(to).toString().replace('\\', '/');
            return relative.isBlank() ? componentRsFile.getName() : relative;
        } catch (Exception ignored) {
            return componentRsFile.getPath().replace('\\', '/');
        }
    }

    private static String toPascalIdentifier(String name) {
        List<String> parts = toAlnumTokens(name);
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
        List<String> parts = toAlnumTokens(name);
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
        List<String> parts = toAlnumTokens(name);
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

    private static List<String> toAlnumTokens(String name) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                current.append(ch);
                continue;
            }
            if (!current.isEmpty()) {
                parts.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static boolean isValidPathSegment(String part) {
        if (part.isBlank()) {
            return false;
        }
        if (".".equals(part) || "..".equals(part)) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            char ch = part.charAt(i);
            if (ch < 32) {
                return false;
            }
            if ("\\/:*?\"<>|".indexOf(ch) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidRustIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private record CreatedComponent(String componentName, VirtualFile rustFile) {
    }

    private record ParsedInput(List<String> folders, String componentName) {
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
}
