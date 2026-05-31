package com.tiltus.beu.plugin.project;

import com.intellij.facet.ui.ValidationResult;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.DirectoryProjectGenerator;
import com.intellij.platform.ProjectGeneratorPeer;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class BevyDirectoryProjectGenerator implements DirectoryProjectGenerator<BevyProjectSettings> {
    private static final String BEU_MAIN_GIT_OPTION = "main (git)";
    private static final String BEU_GIT_URL = "https://github.com/exepta/bevy_extended_ui";

    private static final String MAIN_RS_DEFAULT = """
            use bevy::prelude::*;

            fn main() {
                App::new()
                    .add_plugins(DefaultPlugins)
                    .run();
            }
            """;

    private static final String MAIN_COMPONENT_HTML = """
            <div class="container">
            <h1>Bevy Extended UI</h1>
            <h5>Welcome!</h5>
            </div>
            """;

    private static final String MAIN_COMPONENT_CSS = """
            body {
                width: 100%;
                height: 100%;
                display: flex;
                background: #3c3c4e;
            }

            .container {
                width: 100%;
                height: 100%;
                display: flex;
                justify-content: center;
                align-items: center;
                flex-direction: column;
            }
            """;

    private static final String INDEX_HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="app-main" />
              <title>Bevy Extended UI</title>
            </head>
            <body>
            <app-main></app-main>
            </body>
            </html>
            """;

    @Override
    public @NotNull String getName() {
        return "Bevy";
    }

    @Override
    public Icon getLogo() {
        return IconLoader.getIcon("/icons/bevyProject16.svg", BevyDirectoryProjectGenerator.class);
    }

    @Override
    public ProjectGeneratorPeer<BevyProjectSettings> createPeer() {
        return new BevyProjectGeneratorPeer();
    }

    @Override
    public void generateProject(Project project, VirtualFile baseDir, BevyProjectSettings settings, Module module) {
        WriteAction.run(() -> {
            try {
                String markerFileName = normalizeRegistryFileName(settings.registryFileName());
                boolean createRegistryMarker = shouldCreateRegistryMarker(settings);

                writeFile(baseDir, "Cargo.toml", buildCargoToml(baseDir.getName(), settings));
                writeFile(baseDir, "src/main.rs", buildMainRs(settings.includeBevyExtendedUi(), createRegistryMarker, markerFileName));
                writeFile(baseDir, "README.md", buildReadme(baseDir.getName(), settings));
                if (settings.createGitignore()) {
                    writeFile(baseDir, ".gitignore", "/target\n");
                }

                if (settings.includeBevyExtendedUi()) {
                    ensureDirectory(baseDir, "assets");
                }

                if (createRegistryMarker) {
                    writeFile(baseDir, "src/" + markerFileName + ".rs", buildRegistryRs());
                    createMainComponentAssets(baseDir);
                    writeFile(baseDir, "assets/index.html", INDEX_HTML);
                }
            } catch (IOException error) {
                throw new IllegalStateException("Failed to generate Bevy project files.", error);
            }
        });
    }

    @Override
    public ValidationResult validate(String baseDirPath) {
        if (baseDirPath == null || baseDirPath.isBlank()) {
            return new ValidationResult("Project path is required.");
        }

        try {
            Path path = Path.of(baseDirPath);
            if (Files.exists(path) && !Files.isDirectory(path)) {
                return new ValidationResult("Project path points to a file, not a directory.");
            }
        } catch (Exception ignored) {
            return new ValidationResult("Project path is invalid.");
        }

        return ValidationResult.OK;
    }

    private static void writeFile(VirtualFile baseDir, String relativePath, String content) throws IOException {
        String[] segments = relativePath.split("/");
        VirtualFile current = baseDir;
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            VirtualFile child = current.findChild(segment);
            if (child == null) {
                child = current.createChildDirectory(BevyDirectoryProjectGenerator.class, segment);
            }
            current = child;
        }

        String fileName = segments[segments.length - 1];
        VirtualFile file = current.findChild(fileName);
        if (file == null) {
            file = current.createChildData(BevyDirectoryProjectGenerator.class, fileName);
        }
        VfsUtil.saveText(file, content);
    }

    private static void ensureDirectory(VirtualFile baseDir, String relativePath) throws IOException {
        String[] segments = relativePath.split("/");
        VirtualFile current = baseDir;
        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            VirtualFile child = current.findChild(segment);
            if (child == null) {
                child = current.createChildDirectory(BevyDirectoryProjectGenerator.class, segment);
            }
            current = child;
        }
    }

    private static void createMainComponentAssets(VirtualFile baseDir) throws IOException {
        ensureDirectory(baseDir, "assets/components");
        writeFile(baseDir, "assets/components/main.component.rs", buildComponentRust("main"));
        writeFile(baseDir, "assets/components/main.component.html", MAIN_COMPONENT_HTML);
        writeFile(baseDir, "assets/components/main.component.css", MAIN_COMPONENT_CSS);
    }

    private static boolean shouldCreateRegistryMarker(BevyProjectSettings settings) {
        if (!settings.includeBevyExtendedUi()) {
            return false;
        }
        for (String feature : settings.bevyExtendedUiFeatures()) {
            if ("extended-framework".equals(feature) || "extended_framework".equals(feature)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeRegistryFileName(String name) {
        String raw = name == null ? "" : name.trim();
        if (raw.endsWith(".rs")) {
            raw = raw.substring(0, raw.length() - 3);
        }
        if (raw.isBlank()) {
            return "beu_registry_marker";
        }

        StringBuilder sanitized = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                sanitized.append(ch);
            } else {
                sanitized.append('_');
            }
        }

        String value = sanitized.toString().replaceAll("_+", "_");
        value = value.replaceAll("^_+", "");
        value = value.replaceAll("_+$", "");
        if (value.isBlank()) {
            return "beu_registry_marker";
        }
        return value;
    }

    private static String buildCargoToml(String directoryName, BevyProjectSettings settings) {
        String packageName = sanitizePackageName(directoryName);
        String bevyVersion = normalizeCrateVersion(settings.bevyVersion());
        String edition = settings.rustEdition() == null || settings.rustEdition().isBlank()
                ? "2024"
                : settings.rustEdition();
        List<String> selectedFeatures = settings.bevyExtendedUiFeatures().stream()
                .filter(feature -> !feature.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();

        StringBuilder toml = new StringBuilder();
        toml.append("[package]\n")
                .append("name = \"").append(packageName).append("\"\n")
                .append("version = \"0.1.0\"\n")
                .append("edition = \"").append(edition).append("\"\n")
                .append("authors = [\"\"]\n")
                .append("description = \"Auto generated bevy app from beu-plugin\"\n\n");

        appendDependencyBlock(toml, "bevy", new DependencySpec("version", bevyVersion, null, null, List.of()));

        if (settings.includeBevyExtendedUi()) {
            DependencySpec beuSpec;
            if (isBeuMainGitSelection(settings.bevyExtendedUiVersion())) {
                beuSpec = new DependencySpec("git", null, BEU_GIT_URL, "main", selectedFeatures);
            } else {
                beuSpec = new DependencySpec("version", normalizeCrateVersion(settings.bevyExtendedUiVersion()), null, null, selectedFeatures);
            }
            appendDependencyBlock(toml, "bevy_extended_ui", beuSpec);

            if (shouldAddMacrosDependency(settings)) {
                DependencySpec macrosSpec;
                if (isBeuMainGitSelection(settings.bevyExtendedUiVersion())) {
                    macrosSpec = new DependencySpec("git", null, BEU_GIT_URL, "main", List.of());
                } else {
                    macrosSpec = new DependencySpec("version", normalizeCrateVersion(settings.bevyExtendedUiVersion()), null, null, List.of());
                }
                appendDependencyBlock(toml, "bevy_extended_ui_macros", macrosSpec);
            }
        }

        Map<String, DependencySpec> assetDependencies = toAssetDependencies(settings.selectedAssets());
        for (Map.Entry<String, DependencySpec> entry : assetDependencies.entrySet()) {
            String dependencyName = entry.getKey();
            if ("bevy".equals(dependencyName)
                    || "bevy_extended_ui".equals(dependencyName)
                    || "bevy_extended_ui_macros".equals(dependencyName)) {
                continue;
            }
            appendDependencyBlock(toml, dependencyName, entry.getValue());
        }
        return toml.toString();
    }

    private static String sanitizePackageName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                builder.append(ch);
            } else {
                builder.append('-');
            }
        }

        String normalized = builder.toString().replaceAll("-+", "-");
        normalized = normalized.replaceAll("^-+", "");
        normalized = normalized.replaceAll("-+$", "");
        if (normalized.isBlank()) {
            return "bevy_app";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            return "bevy_" + normalized;
        }
        return normalized;
    }

    private static String normalizeCrateVersion(String version) {
        if (version == null) {
            return "0.1.0";
        }
        String value = version.trim();
        if (value.length() > 1 && value.charAt(0) == 'v' && Character.isDigit(value.charAt(1))) {
            return value.substring(1);
        }
        return value.isBlank() ? "0.1.0" : value;
    }

    private static void appendDependencyBlock(StringBuilder toml, String dependencyName, DependencySpec dependency) {
        toml.append("[dependencies.")
                .append(dependencyName)
                .append("]\n");
        if ("git".equals(dependency.sourceType())) {
            toml.append("git = \"").append(dependency.gitUrl()).append("\"\n");
            if (dependency.branch() != null && !dependency.branch().isBlank()) {
                toml.append("branch = \"").append(dependency.branch()).append("\"\n");
            }
        } else {
            toml.append("version = \"").append(dependency.version()).append("\"\n");
        }
        if (!dependency.features().isEmpty()) {
            String serializedFeatures = dependency.features().stream()
                    .map(feature -> "\"" + feature + "\"")
                    .collect(Collectors.joining(", "));
            toml.append("features = [").append(serializedFeatures).append("]\n");
        }
        toml.append("\n");
    }

    private static Map<String, DependencySpec> toAssetDependencies(List<BevyProjectSettings.BevyAssetSelection> assets) {
        Map<String, DependencySpec> dependencies = new LinkedHashMap<>();
        for (BevyProjectSettings.BevyAssetSelection asset : assets) {
            DependencyEntry dependencyEntry = resolveAssetDependency(asset.url());
            if (dependencyEntry == null) {
                continue;
            }
            dependencies.putIfAbsent(dependencyEntry.name(), dependencyEntry.spec());
        }
        return dependencies;
    }

    private static DependencyEntry resolveAssetDependency(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String[] segments = uri.getPath().split("/");
            List<String> filteredSegments = new java.util.ArrayList<>();
            for (String segment : segments) {
                if (!segment.isBlank()) {
                    filteredSegments.add(segment);
                }
            }

            if (normalizedHost.contains("crates.io") && filteredSegments.size() >= 2 && "crates".equals(filteredSegments.get(0))) {
                String crate = sanitizePackageName(filteredSegments.get(1));
                return new DependencyEntry(crate, new DependencySpec("version", "*", null, null, List.of()));
            }
            if (normalizedHost.contains("docs.rs") && !filteredSegments.isEmpty()) {
                String crate = sanitizePackageName(filteredSegments.get(0));
                return new DependencyEntry(crate, new DependencySpec("version", "*", null, null, List.of()));
            }
            if ((normalizedHost.contains("github.com") || normalizedHost.contains("gitlab.com")) && filteredSegments.size() >= 2) {
                String owner = filteredSegments.get(0);
                String repo = filteredSegments.get(1);
                if (repo.endsWith(".git")) {
                    repo = repo.substring(0, repo.length() - 4);
                }
                String depName = sanitizePackageName(repo);
                String gitUrl = "https://" + host + "/" + owner + "/" + repo;
                return new DependencyEntry(depName, new DependencySpec("git", null, gitUrl, null, List.of()));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static boolean shouldAddMacrosDependency(BevyProjectSettings settings) {
        if (!settings.includeBevyExtendedUi()) {
            return false;
        }
        if (!hasExtendedFrameworkFeature(settings.bevyExtendedUiFeatures())) {
            return false;
        }
        if (isBeuMainGitSelection(settings.bevyExtendedUiVersion())) {
            return true;
        }
        return isSemverAtLeast(normalizeCrateVersion(settings.bevyExtendedUiVersion()), 1, 6, 0);
    }

    private static boolean hasExtendedFrameworkFeature(List<String> features) {
        for (String feature : features) {
            if ("extended-framework".equals(feature) || "extended_framework".equals(feature)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSemverAtLeast(String version, int major, int minor, int patch) {
        try {
            String[] parts = version.split("[.-]");
            int vMajor = parts.length > 0 ? parseIntOrZero(parts[0]) : 0;
            int vMinor = parts.length > 1 ? parseIntOrZero(parts[1]) : 0;
            int vPatch = parts.length > 2 ? parseIntOrZero(parts[2]) : 0;
            if (vMajor != major) {
                return vMajor > major;
            }
            if (vMinor != minor) {
                return vMinor > minor;
            }
            return vPatch >= patch;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int parseIntOrZero(String value) {
        String digits = value.replaceAll("[^0-9].*$", "");
        if (digits.isBlank()) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    private static String buildMainRs(boolean includeBevyExtendedUi, boolean includeRegistryMod, String registryFileName) {
        if (!includeBevyExtendedUi) {
            return MAIN_RS_DEFAULT;
        }
        StringBuilder builder = new StringBuilder();
        if (includeRegistryMod) {
            builder.append("mod ").append(toRustModuleIdentifier(registryFileName)).append(";\n\n");
        }
        builder.append("use bevy::asset::{AssetMetaCheck, AssetPlugin};\n")
                .append("use bevy::prelude::*;\n")
                .append("use bevy_extended_ui::ExtendedUiPlugin;\n")
                .append("\n")
                .append("fn main() {\n")
                .append("    App::new()\n")
                .append("        .add_plugins(DefaultPlugins.set(AssetPlugin {\n")
                .append("            file_path: format!(\"{}/assets\", env!(\"CARGO_MANIFEST_DIR\")),\n")
                .append("            meta_check: AssetMetaCheck::Never,\n")
                .append("            ..default()\n")
                .append("        }))\n")
                .append("        .add_plugins(ExtendedUiPlugin)\n")
                .append("        .run();\n")
                .append("}\n");
        return builder.toString();
    }

    private static String toRustModuleIdentifier(String fileName) {
        String normalized = fileName.replaceAll("[^A-Za-z0-9_]", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+", "");
        normalized = normalized.replaceAll("_+$", "");
        if (normalized.isBlank()) {
            return "beu_registry_marker";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            return "mod_" + normalized;
        }
        return normalized;
    }

    private static String buildComponentRust(String componentName) {
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

    private static String buildRegistryRs() {
        return "use bevy_extended_ui_macros::beu_registry;\n\n" +
                "#[beu_registry]\n" +
                "mod beu_registry_marker {}\n\n" +
                "#[allow(dead_code)]\n" +
                "#[path = \"../assets/components/main.component.rs\"]\n" +
                "mod main_component_mod;\n";
    }

    private static String buildReadme(String directoryName, BevyProjectSettings settings) {
        String projectName = directoryName == null || directoryName.isBlank() ? "Bevy Project" : directoryName;
        StringBuilder readme = new StringBuilder();
        readme.append("# ").append(projectName).append("\n\n")
                .append("This project was generated with the `beu-plugin` Bevy project wizard.\n\n")
                .append("## Links\n")
                .append("- Bevy: https://github.com/bevyengine/bevy\n");

        if (settings.includeBevyExtendedUi()) {
            readme.append("- bevy_extended_ui: https://github.com/exepta/bevy_extended_ui\n");
        }

        readme.append("- Bevy Assets Directory: https://bevy.org/assets/\n\n")
                .append("## Selected Assets\n");

        if (settings.selectedAssets().isEmpty()) {
            readme.append("- None selected.\n");
            return readme.toString();
        }

        for (BevyProjectSettings.BevyAssetSelection asset : settings.selectedAssets()) {
            if (asset.title() == null || asset.title().isBlank() || asset.url() == null || asset.url().isBlank()) {
                continue;
            }
            readme.append("- [")
                    .append(asset.title())
                    .append("](")
                    .append(asset.url())
                    .append(")\n");
        }
        return readme.toString();
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

    private static List<String> toAlnumTokens(String name) {
        List<String> parts = new java.util.ArrayList<>();
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

    private static boolean isValidRustIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isBeuMainGitSelection(String value) {
        return BEU_MAIN_GIT_OPTION.equals(value) || "main".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    private record DependencySpec(String sourceType, String version, String gitUrl, String branch, List<String> features) {
    }

    private record DependencyEntry(String name, DependencySpec spec) {
    }
}
