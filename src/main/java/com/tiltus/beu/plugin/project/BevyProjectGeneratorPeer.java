package com.tiltus.beu.plugin.project;

import com.intellij.ide.util.projectWizard.ModuleNameLocationSettings;
import com.intellij.ide.util.projectWizard.SettingsStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.platform.ProjectGeneratorPeer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BevyProjectGeneratorPeer implements ProjectGeneratorPeer<BevyProjectSettings> {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern TAG_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CARD_PATTERN = Pattern.compile("<a[^>]*class=asset-card[^>]*>.*?</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HREF_PATTERN = Pattern.compile("href=([\"'][^\"']+[\"']|[^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile("<div\\s+class=asset-card__title>(.*?)</div>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final List<String> FALLBACK_BEVY_VERSIONS = List.of("0.18.1", "0.18.0", "0.17.2");
    private static final List<String> FALLBACK_BEU_VERSIONS = List.of("0.1.0");
    private static final String BEU_MAIN_GIT_OPTION = "main (git)";

    private final AtomicInteger pendingAsyncJobs = new AtomicInteger(0);
    private final Map<String, String> bevyTagByDisplay = new LinkedHashMap<>();
    private final Map<String, String> beuTagByDisplay = new LinkedHashMap<>();
    private final Map<String, JBCheckBox> featureCheckboxes = new LinkedHashMap<>();
    private final Map<AssetOption, JBCheckBox> assetCheckboxes = new LinkedHashMap<>();

    private final JComboBox<String> bevyVersionCombo = new JComboBox<>(new String[]{"Loading..."});
    private final JBLabel rustVersionLabel = new JBLabel("Detecting rustc version...");
    private final JComboBox<String> rustEditionCombo = new JComboBox<>(new String[]{"2024", "2021", "2018"});
    private final JBCheckBox createGitignoreCheckbox = new JBCheckBox("Create .gitignore", true);

    private final JBCheckBox includeBeuCheckbox = new JBCheckBox("Include bevy_extended_ui", true);
    private final JComboBox<String> beuVersionCombo = new JComboBox<>(new String[]{"Loading..."});
    private final JBPanel<?> featuresPanel = new JBPanel<>(new BorderLayout());
    private final JBPanel<?> featuresListPanel = new JBPanel<>();
    private final JBLabel featuresStatusLabel = new JBLabel("Loading features...");
    private final JBScrollPane featuresScrollPane = new JBScrollPane(featuresPanel);
    private final JBTextField registryFileNameField = new JBTextField("beu_registry_marker");

    private final JBPanel<?> assetsCheckboxPanel = new JBPanel<>();
    private final JBLabel assetsStatusLabel = new JBLabel("Loading assets...");
    private final JBPanel<?> assetsPanel = new JBPanel<>(new BorderLayout());

    private boolean suppressBeuVersionEvents;
    private boolean dataLoadingStarted;
    private JPanel legacySettingsPanel;

    public BevyProjectGeneratorPeer() {
        featuresListPanel.setLayout(new BoxLayout(featuresListPanel, BoxLayout.Y_AXIS));
        featuresPanel.add(featuresStatusLabel, BorderLayout.NORTH);
        featuresPanel.add(featuresListPanel, BorderLayout.CENTER);
        featuresScrollPane.setPreferredSize(new Dimension(360, 130));

        assetsCheckboxPanel.setLayout(new BoxLayout(assetsCheckboxPanel, BoxLayout.Y_AXIS));
        JBScrollPane assetsScrollPane = new JBScrollPane(assetsCheckboxPanel);
        assetsScrollPane.setPreferredSize(new Dimension(360, 160));
        assetsPanel.add(assetsStatusLabel, BorderLayout.NORTH);
        assetsPanel.add(assetsScrollPane, BorderLayout.CENTER);

        beuVersionCombo.setEnabled(false);
        registryFileNameField.setEnabled(false);

        includeBeuCheckbox.addActionListener(event -> updateBeuControlsEnabled());
        beuVersionCombo.addActionListener(event -> {
            if (!suppressBeuVersionEvents && includeBeuCheckbox.isSelected()) {
                loadBeuFeaturesForSelectedVersion();
            }
        });
    }

    @Override
    public synchronized JComponent getComponent(@NotNull TextFieldWithBrowseButton locationField, @NotNull Runnable checkValid) {
        if (legacySettingsPanel == null) {
            legacySettingsPanel = new JPanel(new GridBagLayout());
            buildUI(new LegacySettingsStepAdapter(legacySettingsPanel));
        }
        return legacySettingsPanel;
    }

    @Override
    public synchronized JComponent getComponent() {
        return getComponent(new TextFieldWithBrowseButton(), () -> {
        });
    }

    @Override
    public void buildUI(@NotNull SettingsStep settingsStep) {
        settingsStep.addSettingsField("Bevy version:", bevyVersionCombo);
        settingsStep.addSettingsField("Rust version:", rustVersionLabel);
        settingsStep.addSettingsField("Rust edition:", rustEditionCombo);
        settingsStep.addSettingsComponent(createGitignoreCheckbox);
        settingsStep.addSettingsComponent(includeBeuCheckbox);
        settingsStep.addSettingsField("bevy_extended_ui version:", beuVersionCombo);
        settingsStep.addSettingsField("bevy_extended_ui features:", featuresScrollPane);
        settingsStep.addSettingsField("Registry marker filename:", registryFileNameField);
        settingsStep.addExpertField("Bevy assets:", assetsPanel);

        updateBeuControlsEnabled();
        if (!dataLoadingStarted) {
            dataLoadingStarted = true;
            loadRustVersion();
            loadBevyVersions();
            loadBeuVersions();
            loadAssets();
        }
    }

    @Override
    public BevyProjectSettings getSettings() {
        String bevyVersion = selectedOrFallback(bevyVersionCombo, FALLBACK_BEVY_VERSIONS.get(0));
        String rustEdition = selectedOrFallback(rustEditionCombo, "2024");
        boolean includeBeu = includeBeuCheckbox.isSelected();
        String beuVersion = selectedOrFallback(beuVersionCombo, FALLBACK_BEU_VERSIONS.get(0));

        List<String> selectedFeatures = new ArrayList<>();
        for (Map.Entry<String, JBCheckBox> entry : featureCheckboxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                selectedFeatures.add(entry.getKey());
            }
        }

        List<BevyProjectSettings.BevyAssetSelection> selectedAssets = new ArrayList<>();
        for (Map.Entry<AssetOption, JBCheckBox> entry : assetCheckboxes.entrySet()) {
            if (!entry.getValue().isSelected()) {
                continue;
            }
            AssetOption option = entry.getKey();
            selectedAssets.add(new BevyProjectSettings.BevyAssetSelection(option.title(), option.url()));
        }

        return new BevyProjectSettings(
                bevyVersion,
                rustEdition,
                createGitignoreCheckbox.isSelected(),
                includeBeu,
                beuVersion,
                selectedFeatures,
                selectedAssets,
                registryFileNameField.getText().trim()
        );
    }

    @Override
    public @Nullable ValidationInfo validate() {
        if (bevyVersionCombo.getSelectedItem() == null) {
            return new ValidationInfo("Please select a Bevy version.", bevyVersionCombo);
        }
        if (includeBeuCheckbox.isSelected() && beuVersionCombo.getSelectedItem() == null) {
            return new ValidationInfo("Please select a bevy_extended_ui version.", beuVersionCombo);
        }
        if (registryFileNameField.isEnabled()) {
            String fileName = registryFileNameField.getText().trim();
            if (fileName.isBlank()) {
                return new ValidationInfo("Registry marker filename is required.", registryFileNameField);
            }
            String normalized = stripOptionalRs(fileName);
            if (!isValidFileSegment(normalized)) {
                return new ValidationInfo("Registry marker filename is invalid.", registryFileNameField);
            }
        }
        return null;
    }

    @Override
    public boolean isBackgroundJobRunning() {
        return pendingAsyncJobs.get() > 0;
    }

    private void loadRustVersion() {
        runAsync(
                BevyProjectGeneratorPeer::detectRustVersion,
                rustVersion -> rustVersionLabel.setText(rustVersion),
                error -> rustVersionLabel.setText("rustc not found")
        );
    }

    private void loadBevyVersions() {
        runAsync(
                () -> fetchRepositoryTags("bevyengine", "bevy"),
                tags -> {
                    bevyTagByDisplay.clear();
                    for (String tag : tags) {
                        String display = normalizeCrateVersion(tag);
                        bevyTagByDisplay.putIfAbsent(display, tag);
                    }
                    List<String> availableVersions = new ArrayList<>(bevyTagByDisplay.keySet());
                    if (availableVersions.isEmpty()) {
                        availableVersions = FALLBACK_BEVY_VERSIONS;
                    }
                    replaceComboValues(bevyVersionCombo, availableVersions);
                },
                error -> replaceComboValues(bevyVersionCombo, FALLBACK_BEVY_VERSIONS)
        );
    }

    private void loadBeuVersions() {
        runAsync(
                () -> fetchRepositoryTags("exepta", "bevy_extended_ui"),
                tags -> {
                    beuTagByDisplay.clear();
                    for (String tag : tags) {
                        String display = normalizeCrateVersion(tag);
                        beuTagByDisplay.putIfAbsent(display, tag);
                    }
                    List<String> availableVersions = new ArrayList<>(beuTagByDisplay.keySet());
                    if (availableVersions.isEmpty()) {
                        availableVersions = new ArrayList<>(FALLBACK_BEU_VERSIONS);
                    }
                    availableVersions.add(0, BEU_MAIN_GIT_OPTION);

                    suppressBeuVersionEvents = true;
                    replaceComboValues(beuVersionCombo, availableVersions);
                    suppressBeuVersionEvents = false;
                    updateBeuControlsEnabled();
                    if (includeBeuCheckbox.isSelected()) {
                        loadBeuFeaturesForSelectedVersion();
                    }
                },
                error -> {
                    List<String> fallbackValues = new ArrayList<>();
                    fallbackValues.add(BEU_MAIN_GIT_OPTION);
                    fallbackValues.addAll(FALLBACK_BEU_VERSIONS);
                    suppressBeuVersionEvents = true;
                    replaceComboValues(beuVersionCombo, fallbackValues);
                    suppressBeuVersionEvents = false;
                    updateBeuControlsEnabled();
                    setFeatures(List.of("extended-framework", "extended_framework"));
                }
        );
    }

    private void loadBeuFeaturesForSelectedVersion() {
        String selectedDisplayVersion = selectedOrFallback(beuVersionCombo, FALLBACK_BEU_VERSIONS.get(0));
        String rawTag = BEU_MAIN_GIT_OPTION.equals(selectedDisplayVersion)
                ? "main"
                : beuTagByDisplay.getOrDefault(selectedDisplayVersion, selectedDisplayVersion);
        featuresStatusLabel.setText("Loading features...");
        featuresStatusLabel.setVisible(true);
        clearFeatures();

        runAsync(
                () -> {
                    String encodedTag = URLEncoder.encode(rawTag, StandardCharsets.UTF_8);
                    String url = "https://raw.githubusercontent.com/exepta/bevy_extended_ui/" + encodedTag + "/Cargo.toml";
                    try {
                        return parseFeaturesFromCargoToml(fetchText(url));
                    } catch (Exception ignored) {
                        String fallbackUrl = "https://raw.githubusercontent.com/exepta/bevy_extended_ui/main/Cargo.toml";
                        return parseFeaturesFromCargoToml(fetchText(fallbackUrl));
                    }
                },
                this::setFeatures,
                error -> setFeatures(List.of("extended-framework", "extended_framework"))
        );
    }

    private void loadAssets() {
        runAsync(
                () -> parseAssets(fetchText("https://bevy.org/assets/")),
                assets -> {
                    clearAssetSelectionPanel();
                    for (AssetOption asset : assets) {
                        JBCheckBox checkBox = new JBCheckBox(asset.title(), false);
                        checkBox.setToolTipText(asset.url());
                        assetCheckboxes.put(asset, checkBox);
                        assetsCheckboxPanel.add(checkBox);
                    }
                    assetsCheckboxPanel.revalidate();
                    assetsCheckboxPanel.repaint();
                    assetsStatusLabel.setText(assets.isEmpty()
                            ? "No assets loaded."
                            : "Loaded " + assets.size() + " assets (select with checkbox).");
                },
                error -> {
                    clearAssetSelectionPanel();
                    assetsStatusLabel.setText("Could not load assets from bevy.org.");
                }
        );
    }

    private void clearAssetSelectionPanel() {
        assetCheckboxes.clear();
        assetsCheckboxPanel.removeAll();
        assetsCheckboxPanel.revalidate();
        assetsCheckboxPanel.repaint();
    }

    private void setFeatures(List<String> features) {
        featureCheckboxes.clear();
        featuresListPanel.removeAll();

        if (features.isEmpty()) {
            featuresStatusLabel.setText("No features found.");
            featuresStatusLabel.setVisible(true);
            updateRegistryFieldState();
            featuresListPanel.revalidate();
            featuresListPanel.repaint();
            return;
        }

        featuresStatusLabel.setVisible(false);
        for (String feature : features) {
            JBCheckBox featureCheckbox = new JBCheckBox(feature, false);
            featureCheckbox.addActionListener(event -> updateRegistryFieldState());
            featureCheckboxes.put(feature, featureCheckbox);
            featuresListPanel.add(featureCheckbox);
        }

        featuresListPanel.revalidate();
        featuresListPanel.repaint();
        updateBeuControlsEnabled();
    }

    private void clearFeatures() {
        featureCheckboxes.clear();
        featuresListPanel.removeAll();
        featuresListPanel.revalidate();
        featuresListPanel.repaint();
        updateRegistryFieldState();
    }

    private void updateBeuControlsEnabled() {
        boolean enabled = includeBeuCheckbox.isSelected();
        beuVersionCombo.setEnabled(enabled);
        featuresScrollPane.setEnabled(enabled);
        for (JBCheckBox checkBox : featureCheckboxes.values()) {
            checkBox.setEnabled(enabled);
        }
        updateRegistryFieldState();
    }

    private void updateRegistryFieldState() {
        boolean enabled = includeBeuCheckbox.isSelected() && isExtendedFrameworkSelected();
        registryFileNameField.setEnabled(enabled);
    }

    private boolean isExtendedFrameworkSelected() {
        JBCheckBox kebabCase = featureCheckboxes.get("extended-framework");
        if (kebabCase != null && kebabCase.isSelected()) {
            return true;
        }
        JBCheckBox snakeCase = featureCheckboxes.get("extended_framework");
        return snakeCase != null && snakeCase.isSelected();
    }

    private static boolean isValidFileSegment(String fileSegment) {
        if (fileSegment.isBlank()) {
            return false;
        }
        if (".".equals(fileSegment) || "..".equals(fileSegment)) {
            return false;
        }
        if (fileSegment.contains("/") || fileSegment.contains("\\")) {
            return false;
        }
        for (int i = 0; i < fileSegment.length(); i++) {
            char ch = fileSegment.charAt(i);
            if (ch < 32) {
                return false;
            }
            if ("\\/:*?\"<>|".indexOf(ch) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static String stripOptionalRs(String fileName) {
        String value = fileName.trim();
        if (value.endsWith(".rs")) {
            return value.substring(0, value.length() - 3);
        }
        return value;
    }

    private static String selectedOrFallback(JComboBox<String> comboBox, String fallbackValue) {
        Object selected = comboBox.getSelectedItem();
        if (selected instanceof String value && !value.isBlank()) {
            return value;
        }
        return fallbackValue;
    }

    private static void replaceComboValues(JComboBox<String> comboBox, List<String> values) {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        for (String value : values) {
            model.addElement(value);
        }
        comboBox.setModel(model);
        comboBox.setEnabled(model.getSize() > 0);
        if (model.getSize() > 0) {
            comboBox.setSelectedIndex(0);
        }
    }

    private <T> void runAsync(AsyncSupplier<T> supplier, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        pendingAsyncJobs.incrementAndGet();
        CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }).whenComplete((result, throwable) ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        if (throwable == null) {
                            onSuccess.accept(result);
                        } else {
                            onError.accept(unwrapCompletion(throwable));
                        }
                    } finally {
                        pendingAsyncJobs.decrementAndGet();
                    }
                }, ModalityState.any())
        );
    }

    private static Throwable unwrapCompletion(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    private static String detectRustVersion() {
        try {
            Process process = new ProcessBuilder("rustc", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(4, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                return "rustc not found";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return output.isBlank() ? "rustc not found" : output;
        } catch (Exception ignored) {
            return "rustc not found";
        }
    }

    private static List<String> fetchRepositoryTags(String owner, String repo) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/tags?per_page=100";
        String body = fetchText(url);

        Set<String> tags = new LinkedHashSet<>();
        Matcher matcher = TAG_PATTERN.matcher(body);
        while (matcher.find()) {
            String tag = matcher.group(1).trim();
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return new ArrayList<>(tags);
    }

    private static List<String> parseFeaturesFromCargoToml(String cargoToml) {
        List<String> features = new ArrayList<>();
        boolean inFeaturesSection = false;

        String[] lines = cargoToml.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inFeaturesSection = "[features]".equals(trimmed);
                continue;
            }
            if (!inFeaturesSection || trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }

            int equalsIndex = trimmed.indexOf('=');
            if (equalsIndex <= 0) {
                continue;
            }
            String key = trimmed.substring(0, equalsIndex).trim();
            if (key.startsWith("\"") && key.endsWith("\"") && key.length() > 1) {
                key = key.substring(1, key.length() - 1);
            }
            if (key.isBlank() || "default".equals(key)) {
                continue;
            }
            features.add(key);
        }

        features.sort(String::compareTo);
        return features;
    }

    private static List<AssetOption> parseAssets(String html) {
        List<AssetOption> assets = new ArrayList<>();
        Set<String> seenAssets = new LinkedHashSet<>();

        Matcher cardMatcher = CARD_PATTERN.matcher(html);
        while (cardMatcher.find()) {
            String assetCardHtml = cardMatcher.group();
            Matcher titleMatcher = TITLE_PATTERN.matcher(assetCardHtml);
            Matcher hrefMatcher = HREF_PATTERN.matcher(assetCardHtml);
            if (!titleMatcher.find() || !hrefMatcher.find()) {
                continue;
            }

            String title = cleanupHtmlText(titleMatcher.group(1));
            String assetUrlCandidate = stripQuotes(hrefMatcher.group(1));
            if (title.isBlank() || assetUrlCandidate.isBlank()) {
                continue;
            }

            String normalizedUrl = normalizeAssetUrl(assetUrlCandidate);
            if (!isSupportedDependencyUrl(normalizedUrl)) {
                continue;
            }
            String dedupKey = title + "|" + normalizedUrl;
            if (seenAssets.add(dedupKey)) {
                assets.add(new AssetOption(title, normalizedUrl));
            }
            if (assets.size() >= 300) {
                break;
            }
        }
        return assets;
    }

    private static boolean isSupportedDependencyUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            String normalizedHost = host.toLowerCase();
            return normalizedHost.contains("github.com")
                    || normalizedHost.contains("gitlab.com")
                    || normalizedHost.contains("crates.io")
                    || normalizedHost.contains("docs.rs");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String cleanupHtmlText(String htmlFragment) {
        String withoutTags = htmlFragment.replaceAll("<[^>]+>", " ");
        String unescaped = StringUtil.unescapeXmlEntities(withoutTags);
        return unescaped.replaceAll("\\s+", " ").trim();
    }

    private static String stripQuotes(String quotedValue) {
        if (quotedValue.length() >= 2) {
            char first = quotedValue.charAt(0);
            char last = quotedValue.charAt(quotedValue.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return quotedValue.substring(1, quotedValue.length() - 1);
            }
        }
        return quotedValue;
    }

    private static String normalizeAssetUrl(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return "https://bevy.org" + url;
        }
        return "https://bevy.org/" + url;
    }

    private static String fetchText(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json, text/plain, */*")
                .header("User-Agent", "idea-beu-plugin")
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Request failed with status " + response.statusCode() + ": " + url);
        }
        return response.body();
    }

    private static String normalizeCrateVersion(String value) {
        if (value.length() > 1 && value.charAt(0) == 'v' && Character.isDigit(value.charAt(1))) {
            return value.substring(1);
        }
        return value;
    }

    private record AssetOption(String title, String url) {
    }

    @FunctionalInterface
    private interface AsyncSupplier<T> {
        T get() throws Exception;
    }

    private static final class LegacySettingsStepAdapter implements SettingsStep {
        private final JPanel panel;
        private int row;

        private LegacySettingsStepAdapter(JPanel panel) {
            this.panel = panel;
        }

        @Override
        public WizardContext getContext() {
            return null;
        }

        @Override
        public void addSettingsField(String label, JComponent field) {
            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = row;
            labelConstraints.weightx = 0;
            labelConstraints.anchor = GridBagConstraints.WEST;
            labelConstraints.insets = new Insets(0, 0, 6, 8);
            panel.add(new JBLabel(label), labelConstraints);

            GridBagConstraints fieldConstraints = new GridBagConstraints();
            fieldConstraints.gridx = 1;
            fieldConstraints.gridy = row;
            fieldConstraints.weightx = 1;
            fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
            fieldConstraints.anchor = GridBagConstraints.WEST;
            fieldConstraints.insets = new Insets(0, 0, 6, 0);
            panel.add(field, fieldConstraints);
            row++;
        }

        @Override
        public void addSettingsComponent(JComponent component) {
            addFullWidth(component, 6);
        }

        @Override
        public void addExpertPanel(JComponent component) {
            addFullWidth(component, 6);
        }

        @Override
        public void addExpertField(String label, JComponent field) {
            addSettingsField(label, field);
        }

        @Override
        public JTextField getModuleNameField() {
            return null;
        }

        @Override
        public ModuleNameLocationSettings getModuleNameLocationSettings() {
            return null;
        }

        private void addFullWidth(JComponent component, int bottomInset) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0;
            constraints.gridy = row;
            constraints.gridwidth = 2;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(0, 0, bottomInset, 0);
            panel.add(component, constraints);
            row++;
        }
    }
}
