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
                String registryFileName = normalizeRegistryFileName(settings.registryFileName());
                boolean shouldGenerateRegistryMarker = shouldCreateRegistryMarker(settings);

                writeFile(baseDir, "Cargo.toml", buildCargoToml(baseDir.getName(), settings));
                writeFile(baseDir, "src/main.rs", buildMainRs(settings.includeBevyExtendedUi(), shouldGenerateRegistryMarker, registryFileName));
                writeFile(baseDir, "README.md", buildReadme(baseDir.getName(), settings));
                if (settings.createGitignore()) {
                    writeFile(baseDir, ".gitignore", "/target\n");
                }

                if (settings.includeBevyExtendedUi()) {
                    ensureDirectory(baseDir, "assets");
                }

                if (shouldGenerateRegistryMarker) {
                    writeFile(baseDir, "src/" + registryFileName + ".rs", buildRegistryRs());
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
        String[] pathSegments = relativePath.split("/");
        VirtualFile currentDirectory = baseDir;
        for (int i = 0; i < pathSegments.length - 1; i++) {
            String segment = pathSegments[i];
            VirtualFile childDirectory = currentDirectory.findChild(segment);
            if (childDirectory == null) {
                childDirectory = currentDirectory.createChildDirectory(BevyDirectoryProjectGenerator.class, segment);
            }
            currentDirectory = childDirectory;
        }

        String fileName = pathSegments[pathSegments.length - 1];
        VirtualFile file = currentDirectory.findChild(fileName);
        if (file == null) {
            file = currentDirectory.createChildData(BevyDirectoryProjectGenerator.class, fileName);
        }
        VfsUtil.saveText(file, content);
    }

    private static void ensureDirectory(VirtualFile baseDir, String relativePath) throws IOException {
        String[] pathSegments = relativePath.split("/");
        VirtualFile currentDirectory = baseDir;
        for (String segment : pathSegments) {
            if (segment.isBlank()) {
                continue;
            }
            VirtualFile childDirectory = currentDirectory.findChild(segment);
            if (childDirectory == null) {
                childDirectory = currentDirectory.createChildDirectory(BevyDirectoryProjectGenerator.class, segment);
            }
            currentDirectory = childDirectory;
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
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.endsWith(".rs")) {
            trimmedName = trimmedName.substring(0, trimmedName.length() - 3);
        }
        if (trimmedName.isBlank()) {
            return "beu_registry_marker";
        }

        StringBuilder sanitizedName = new StringBuilder();
        for (int i = 0; i < trimmedName.length(); i++) {
            char currentChar = trimmedName.charAt(i);
            if (Character.isLetterOrDigit(currentChar) || currentChar == '_') {
                sanitizedName.append(currentChar);
            } else {
                sanitizedName.append('_');
            }
        }

        String normalizedName = sanitizedName.toString().replaceAll("_+", "_");
        normalizedName = normalizedName.replaceAll("^_+", "");
        normalizedName = normalizedName.replaceAll("_+$", "");
        if (normalizedName.isBlank()) {
            return "beu_registry_marker";
        }
        return normalizedName;
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

        StringBuilder cargoTomlBuilder = new StringBuilder();
        cargoTomlBuilder.append("[package]\n")
                .append("name = \"").append(packageName).append("\"\n")
                .append("version = \"0.1.0\"\n")
                .append("edition = \"").append(edition).append("\"\n")
                .append("authors = [\"\"]\n")
                .append("description = \"Auto generated bevy app from beu-plugin\"\n\n");

        appendDependencyBlock(cargoTomlBuilder, "bevy", new DependencySpec("version", bevyVersion, null, null, List.of()));

        if (settings.includeBevyExtendedUi()) {
            DependencySpec beuSpec;
            if (isBeuMainGitSelection(settings.bevyExtendedUiVersion())) {
                beuSpec = new DependencySpec("git", null, BEU_GIT_URL, "main", selectedFeatures);
            } else {
                beuSpec = new DependencySpec("version", normalizeCrateVersion(settings.bevyExtendedUiVersion()), null, null, selectedFeatures);
            }
            appendDependencyBlock(cargoTomlBuilder, "bevy_extended_ui", beuSpec);

            if (shouldAddMacrosDependency(settings)) {
                DependencySpec macrosSpec;
                if (isBeuMainGitSelection(settings.bevyExtendedUiVersion())) {
                    macrosSpec = new DependencySpec("git", null, BEU_GIT_URL, "main", List.of());
                } else {
                    macrosSpec = new DependencySpec("version", normalizeCrateVersion(settings.bevyExtendedUiVersion()), null, null, List.of());
                }
                appendDependencyBlock(cargoTomlBuilder, "bevy_extended_ui_macros", macrosSpec);
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
            appendDependencyBlock(cargoTomlBuilder, dependencyName, entry.getValue());
        }
        return cargoTomlBuilder.toString();
    }

    private static String sanitizePackageName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        StringBuilder normalizedNameBuilder = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') {
                normalizedNameBuilder.append(ch);
            } else {
                normalizedNameBuilder.append('-');
            }
        }

        String normalized = normalizedNameBuilder.toString().replaceAll("-+", "-");
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

    private static void appendDependencyBlock(StringBuilder cargoTomlBuilder, String dependencyName, DependencySpec dependency) {
        cargoTomlBuilder.append("[dependencies.")
                .append(dependencyName)
                .append("]\n");
        if ("git".equals(dependency.sourceType())) {
            cargoTomlBuilder.append("git = \"").append(dependency.gitUrl()).append("\"\n");
            if (dependency.branch() != null && !dependency.branch().isBlank()) {
                cargoTomlBuilder.append("branch = \"").append(dependency.branch()).append("\"\n");
            }
        } else {
            cargoTomlBuilder.append("version = \"").append(dependency.version()).append("\"\n");
        }
        if (!dependency.features().isEmpty()) {
            String serializedFeatures = dependency.features().stream()
                    .map(feature -> "\"" + feature + "\"")
                    .collect(Collectors.joining(", "));
            cargoTomlBuilder.append("features = [").append(serializedFeatures).append("]\n");
        }
        cargoTomlBuilder.append("\n");
    }

    private static Map<String, DependencySpec> toAssetDependencies(List<BevyProjectSettings.BevyAssetSelection> assets) {
        Map<String, DependencySpec> assetDependencyMap = new LinkedHashMap<>();
        for (BevyProjectSettings.BevyAssetSelection asset : assets) {
            DependencyEntry dependencyEntry = resolveAssetDependency(asset.url());
            if (dependencyEntry == null) {
                continue;
            }
            assetDependencyMap.putIfAbsent(dependencyEntry.name(), dependencyEntry.spec());
        }
        return assetDependencyMap;
    }

    private static DependencyEntry resolveAssetDependency(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            String[] pathSegments = uri.getPath().split("/");
            List<String> nonEmptyPathSegments = new java.util.ArrayList<>();
            for (String segment : pathSegments) {
                if (!segment.isBlank()) {
                    nonEmptyPathSegments.add(segment);
                }
            }

            if (normalizedHost.contains("crates.io") && nonEmptyPathSegments.size() >= 2 && "crates".equals(nonEmptyPathSegments.get(0))) {
                String crate = sanitizePackageName(nonEmptyPathSegments.get(1));
                return new DependencyEntry(crate, new DependencySpec("version", "*", null, null, List.of()));
            }
            if (normalizedHost.contains("docs.rs") && !nonEmptyPathSegments.isEmpty()) {
                String crate = sanitizePackageName(nonEmptyPathSegments.get(0));
                return new DependencyEntry(crate, new DependencySpec("version", "*", null, null, List.of()));
            }
            if ((normalizedHost.contains("github.com") || normalizedHost.contains("gitlab.com")) && nonEmptyPathSegments.size() >= 2) {
                String owner = nonEmptyPathSegments.get(0);
                String repo = nonEmptyPathSegments.get(1);
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
            int vMajor = parts.length > 0 ? parseLeadingIntOrZero(parts[0]) : 0;
            int vMinor = parts.length > 1 ? parseLeadingIntOrZero(parts[1]) : 0;
            int vPatch = parts.length > 2 ? parseLeadingIntOrZero(parts[2]) : 0;
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

    private static int parseLeadingIntOrZero(String value) {
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
        StringBuilder mainRsBuilder = new StringBuilder();
        if (includeRegistryMod) {
            mainRsBuilder.append("mod ").append(toRustModuleIdentifier(registryFileName)).append(";\n\n");
        }
        mainRsBuilder.append("use bevy::asset::{AssetMetaCheck, AssetPlugin};\n")
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
        return mainRsBuilder.toString();
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
        boolean includeBeu = settings.includeBevyExtendedUi();
        boolean includeRegistryMarker = shouldCreateRegistryMarker(settings);
        String registryFileName = normalizeRegistryFileName(settings.registryFileName()) + ".rs";
        String edition = settings.rustEdition() == null || settings.rustEdition().isBlank()
                ? "2024"
                : settings.rustEdition();
        List<String> selectedFeatures = settings.bevyExtendedUiFeatures().stream()
                .filter(feature -> feature != null && !feature.isBlank())
                .sorted(Comparator.naturalOrder())
                .toList();

        StringBuilder readme = new StringBuilder();
        readme.append("# ").append(projectName).append("\n\n")
                .append("Generated with the `beu-plugin` Bevy project wizard.\n\n")
                .append("## Stack\n")
                .append("- Rust edition: `").append(edition).append("`\n")
                .append("- Bevy: `").append(normalizeCrateVersion(settings.bevyVersion())).append("`\n");

        if (includeBeu) {
            if (isBeuMainGitSelection(settings.bevyExtendedUiVersion())) {
                readme.append("- bevy_extended_ui: `main` (git)\n");
            } else {
                readme.append("- bevy_extended_ui: `")
                        .append(normalizeCrateVersion(settings.bevyExtendedUiVersion()))
                        .append("`\n");
            }
            if (selectedFeatures.isEmpty()) {
                readme.append("- bevy_extended_ui features: none selected\n");
            } else {
                readme.append("- bevy_extended_ui features: ")
                        .append(selectedFeatures.stream().map(feature -> "`" + feature + "`").collect(Collectors.joining(", ")))
                        .append("\n");
            }
        } else {
            readme.append("- bevy_extended_ui: not included\n");
        }

        readme.append("\n")
                .append("## Project Structure\n")
                .append("- `src/main.rs`\n");

        if (includeRegistryMarker) {
            readme.append("- `src/").append(registryFileName).append("`\n");
        }
        if (includeBeu) {
            readme.append("- `assets/`\n");
        }
        if (includeRegistryMarker) {
            readme.append("- `assets/index.html`\n")
                    .append("- `assets/components/main.component.rs`\n")
                    .append("- `assets/components/main.component.html`\n")
                    .append("- `assets/components/main.component.css`\n");
        }

        readme.append("\n")
                .append("## Links\n")
                .append("- Bevy: https://github.com/bevyengine/bevy\n");

        if (includeBeu) {
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

    private static List<String> toAlphanumericTokens(String name) {
        List<String> tokens = new java.util.ArrayList<>();
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

    private static boolean isValidRustIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isBeuMainGitSelection(String selectedVersion) {
        return BEU_MAIN_GIT_OPTION.equals(selectedVersion)
                || "main".equalsIgnoreCase(selectedVersion == null ? "" : selectedVersion.trim());
    }

    private record DependencySpec(String sourceType, String version, String gitUrl, String branch, List<String> features) {
    }

    private record DependencyEntry(String name, DependencySpec spec) {
    }
}
