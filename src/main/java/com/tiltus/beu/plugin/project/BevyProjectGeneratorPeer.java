package com.tiltus.beu.plugin.project;

import com.intellij.ide.BrowserUtil;
import com.intellij.ide.util.projectWizard.ModuleNameLocationSettings;
import com.intellij.ide.util.projectWizard.SettingsStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.platform.ProjectGeneratorPeer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private static final Pattern CARD_PATTERN = Pattern.compile(
            "<a\\b[^>]*\\bclass\\s*=\\s*(?:\"[^\"]*\\basset-card\\b[^\"]*\"|'[^']*\\basset-card\\b[^']*'|[^\\s>]*\\basset-card\\b[^\\s>]*)[^>]*>.*?</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern HREF_PATTERN = Pattern.compile("href=([\"'][^\"']+[\"']|[^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<div\\b[^>]*\\bclass\\s*=\\s*(?:\"[^\"]*\\basset-card__title\\b[^\"]*\"|'[^']*\\basset-card__title\\b[^']*'|[^\\s>]*\\basset-card__title\\b[^\\s>]*)[^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile(
            "<div\\b[^>]*\\bclass\\s*=\\s*(?:\"[^\"]*\\basset-card__description\\b[^\"]*\"|'[^']*\\basset-card__description\\b[^']*'|[^\\s>]*\\basset-card__description\\b[^\\s>]*)[^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SHOWCASE_IMAGE_PATTERN = Pattern.compile(
            "<img\\b[^>]*\\balt\\s*=\\s*(?:\"Showcase image\"|'Showcase image'|Showcase\\s+image)[^>]*\\bsrc\\s*=\\s*([\"'][^\"']+[\"']|[^\\s>]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SHOWCASE_VIDEO_POSTER_PATTERN = Pattern.compile(
            "<video\\b[^>]*\\bposter\\s*=\\s*([\"'][^\"']+[\"']|[^\\s>]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SHOWCASE_VIDEO_SOURCE_PATTERN = Pattern.compile(
            "<video\\b[^>]*\\bsrc\\s*=\\s*([\"'][^\"']+[\"']|[^\\s>]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern DIV_TOKEN_PATTERN = Pattern.compile("</?div\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSET_TAG_PATTERN = Pattern.compile(
            "<span\\b[^>]*\\bclass\\s*=\\s*(?:\"[^\"]*\\basset-card__tag\\b[^\"]*\"|'[^']*\\basset-card__tag\\b[^']*'|[^\\s>]*\\basset-card__tag\\b[^\\s>]*)[^>]*>(.*?)</span>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern NUMERIC_VERSION_PATTERN = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");
    private static final Pattern GITHUB_REPO_PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/#?]+).*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CRATES_IO_CRATE_PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?crates\\.io/crates/([^/#?]+).*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DOCS_RS_CRATE_PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?docs\\.rs/([^/#?]+).*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CRATES_REPOSITORY_PATTERN = Pattern.compile("\"repository\"\\s*:\\s*(?:null|\"([^\"]*)\")");
    private static final Pattern CRATES_HOMEPAGE_PATTERN = Pattern.compile("\"homepage\"\\s*:\\s*(?:null|\"([^\"]*)\")");
    private static final Pattern IMG_CANONICAL_SRC_PATTERN = Pattern.compile(
            "(<img\\b[^>]*?)\\bsrc\\s*=\\s*([\"'])([^\"']*)\\2([^>]*?)\\bdata-canonical-src\\s*=\\s*([\"'])([^\"']+)\\5([^>]*>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern ROOT_LINK_PATTERN = Pattern.compile(
            "(href|src)\\s*=\\s*([\"'])/(?!/)([^\"']*)\\2",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RELATIVE_HREF_PATTERN = Pattern.compile(
            "href\\s*=\\s*([\"'])(?!https?:|mailto:|#|//|javascript:)([^\"']+)\\1",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RELATIVE_SRC_PATTERN = Pattern.compile(
            "src\\s*=\\s*([\"'])(?!https?:|data:|//)([^\"']+)\\1",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMG_SRC_ATTR_PATTERN = Pattern.compile("\\bsrc\\s*=\\s*([\"'])([^\"']+)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_STYLE_ATTR_PATTERN = Pattern.compile("\\sstyle\\s*=\\s*([\"']).*?\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern IMG_WIDTH_ATTR_PATTERN = Pattern.compile("\\swidth\\s*=\\s*([\"'])?[^\\s>]+\\1?", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_ALIGN_ATTR_PATTERN = Pattern.compile("\\salign\\s*=\\s*([\"'])?[^\\s>]+\\1?", Pattern.CASE_INSENSITIVE);
    private static final int README_MAX_HTML_CHARS = 220_000;

    private static final List<String> FALLBACK_BEVY_VERSIONS = List.of("0.18.1", "0.18.0", "0.17.2");
    private static final List<String> FALLBACK_BEU_VERSIONS = List.of("0.1.0");
    private static final String BEU_MAIN_GIT_OPTION = "main (git)";
    private static final String ASSET_VERSION_ALL = "All";
    private static final String ASSET_VERSION_WILDCARD = "*";

    private final AtomicInteger pendingAsyncJobs = new AtomicInteger(0);
    private final Map<String, String> bevyTagByDisplay = new LinkedHashMap<>();
    private final Map<String, String> beuTagByDisplay = new LinkedHashMap<>();
    private final Map<String, JBCheckBox> featureCheckboxes = new LinkedHashMap<>();
    private final Map<AssetOption, JBCheckBox> assetCheckboxes = new LinkedHashMap<>();
    private final Map<AssetOption, JComponent> assetRows = new LinkedHashMap<>();
    private final List<AssetOption> allAssetOptions = new ArrayList<>();

    private final JComboBox<String> bevyVersionCombo = new JComboBox<>(new String[]{"Loading..."});
    private final JBLabel rustVersionLabel = new JBLabel("Detecting rustc version...");
    private final JComboBox<String> rustEditionCombo = new JComboBox<>(new String[]{"2024", "2021", "2018"});
    private final JBCheckBox createGitignoreCheckbox = new JBCheckBox("Create .gitignore", true);

    private final JBCheckBox includeBeuCheckbox = new JBCheckBox("Include bevy_extended_ui", false);
    private final JComboBox<String> beuVersionCombo = new JComboBox<>(new String[]{"Loading..."});
    private final JBPanel<?> featuresPanel = new JBPanel<>(new BorderLayout());
    private final JBPanel<?> featuresListPanel = new JBPanel<>();
    private final JBLabel featuresStatusLabel = new JBLabel("Loading features...");
    private final JBScrollPane featuresScrollPane = new JBScrollPane(featuresPanel);
    private final JBPanel<?> featuresFieldPanel = new JBPanel<>(new BorderLayout(0, 4));
    private final JBTextField registryFileNameField = new JBTextField("beu_registry");
    private final JBCheckBox useRoutingCheckbox = new JBCheckBox("Use Routing", false);

    private final JBPanel<?> assetsCheckboxPanel = new JBPanel<>();
    private final JBLabel assetsStatusLabel = new JBLabel("Loading assets...");
    private final JBPanel<?> assetsPanel = new JBPanel<>(new BorderLayout());
    private final JBTextField assetsSearchField = new JBTextField();
    private final JComboBox<String> assetsVersionCombo = new JComboBox<>(new String[]{ASSET_VERSION_ALL});

    private boolean suppressBeuVersionEvents;
    private boolean suppressAssetVersionEvents;
    private boolean assetVersionManuallySelected;
    private boolean dataLoadingStarted;
    private JPanel legacySettingsPanel;

    public BevyProjectGeneratorPeer() {
        featuresListPanel.setLayout(new BoxLayout(featuresListPanel, BoxLayout.Y_AXIS));
        featuresPanel.add(featuresStatusLabel, BorderLayout.NORTH);
        featuresPanel.add(featuresListPanel, BorderLayout.CENTER);
        featuresScrollPane.setPreferredSize(new Dimension(360, 130));
        featuresFieldPanel.add(new JBLabel("bevy_extended_ui features:"), BorderLayout.NORTH);
        featuresFieldPanel.add(featuresScrollPane, BorderLayout.CENTER);

        assetsCheckboxPanel.setLayout(new BoxLayout(assetsCheckboxPanel, BoxLayout.Y_AXIS));
        JBScrollPane assetsScrollPane = new JBScrollPane(assetsCheckboxPanel);
        assetsScrollPane.setPreferredSize(new Dimension(360, 160));
        assetsSearchField.putClientProperty("JTextField.placeholderText", "Search assets...");
        JPanel assetsFilterPanel = new JPanel(new BorderLayout(8, 0));
        assetsFilterPanel.add(assetsSearchField, BorderLayout.CENTER);
        assetsVersionCombo.setPreferredSize(new Dimension(130, assetsVersionCombo.getPreferredSize().height));
        assetsFilterPanel.add(assetsVersionCombo, BorderLayout.EAST);

        JPanel assetsHeaderPanel = new JPanel();
        assetsHeaderPanel.setLayout(new BoxLayout(assetsHeaderPanel, BoxLayout.Y_AXIS));
        assetsHeaderPanel.add(assetsFilterPanel);
        assetsHeaderPanel.add(Box.createVerticalStrut(4));
        assetsHeaderPanel.add(assetsStatusLabel);

        assetsPanel.add(assetsHeaderPanel, BorderLayout.NORTH);
        assetsPanel.add(assetsScrollPane, BorderLayout.CENTER);

        beuVersionCombo.setEnabled(false);
        registryFileNameField.setEnabled(false);
        useRoutingCheckbox.setEnabled(false);

        includeBeuCheckbox.addActionListener(event -> {
            updateBeuControlsEnabled();
            maybeLoadBeuFeatures();
        });
        beuVersionCombo.addActionListener(event -> {
            if (!suppressBeuVersionEvents && includeBeuCheckbox.isSelected()) {
                loadBeuFeaturesForSelectedVersion();
            }
        });
        assetsVersionCombo.addActionListener(event -> {
            if (suppressAssetVersionEvents) {
                return;
            }
            String selectedVersion = selectedOrFallback(assetsVersionCombo, ASSET_VERSION_ALL);
            assetVersionManuallySelected = !ASSET_VERSION_ALL.equals(selectedVersion);
            applyAssetFilters();
        });
        assetsSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyAssetFilters();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyAssetFilters();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyAssetFilters();
            }
        });
        bevyVersionCombo.addActionListener(event -> {
            if (!assetVersionManuallySelected) {
                syncAssetVersionFilterToBevyVersion();
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
        settingsStep.addSettingsComponent(featuresFieldPanel);
        settingsStep.addSettingsField("Registry marker filename:", registryFileNameField);
        settingsStep.addSettingsComponent(useRoutingCheckbox);
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
                useRoutingCheckbox.isSelected(),
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
                    maybeLoadBeuFeatures();
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

    private void maybeLoadBeuFeatures() {
        if (!includeBeuCheckbox.isSelected()) {
            return;
        }
        Object selectedItem = beuVersionCombo.getSelectedItem();
        if (!(selectedItem instanceof String selectedVersion) || selectedVersion.isBlank() || "Loading...".equals(selectedVersion)) {
            return;
        }
        loadBeuFeaturesForSelectedVersion();
    }

    private void loadAssets() {
        runAsync(
                () -> parseAssets(fetchText("https://bevy.org/assets/")),
                assets -> {
                    clearAssetSelectionPanel();
                    allAssetOptions.addAll(assets);
                    buildAssetVersionModel(assets);
                    for (AssetOption asset : assets) {
                        JBCheckBox checkBox = new JBCheckBox();
                        checkBox.setToolTipText(asset.url());
                        assetCheckboxes.put(asset, checkBox);
                        assetRows.put(asset, createAssetRow(asset, checkBox));
                    }
                    if (!assetVersionManuallySelected) {
                        syncAssetVersionFilterToBevyVersion();
                    }
                    applyAssetFilters();
                },
                error -> {
                    clearAssetSelectionPanel();
                    assetsStatusLabel.setText("Could not load assets from bevy.org.");
                }
        );
    }

    private void clearAssetSelectionPanel() {
        allAssetOptions.clear();
        assetCheckboxes.clear();
        assetRows.clear();
        assetsCheckboxPanel.removeAll();
        assetsCheckboxPanel.revalidate();
        assetsCheckboxPanel.repaint();
    }

    private JComponent createAssetRow(AssetOption asset, JBCheckBox checkBox) {
        JBPanel<?> rowPanel = new JBPanel<>(new BorderLayout(8, 0));
        rowPanel.setBorder(JBUI.Borders.empty(2, 0));

        JBLabel titleLabel = new JBLabel("<html><a href=''>" + escapeHtml(asset.title()) + "</a></html>");
        titleLabel.setToolTipText(asset.url());
        titleLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titleLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1) {
                    new AssetDetailsDialog(asset).show();
                }
            }
        });

        JBPanel<?> textPanel = new JBPanel<>();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.add(titleLabel);
        if (!asset.description().isBlank()) {
            JBLabel descriptionLabel = new JBLabel(StringUtil.shortenTextWithEllipsis(asset.description(), 90, 0, true));
            descriptionLabel.setFont(descriptionLabel.getFont().deriveFont(Font.PLAIN, Math.max(10f, descriptionLabel.getFont().getSize2D() - 1f)));
            descriptionLabel.setToolTipText(asset.description());
            descriptionLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            descriptionLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getButton() == MouseEvent.BUTTON1) {
                        new AssetDetailsDialog(asset).show();
                    }
                }
            });
            textPanel.add(descriptionLabel);
        }

        rowPanel.add(checkBox, BorderLayout.WEST);
        rowPanel.add(textPanel, BorderLayout.CENTER);
        return rowPanel;
    }

    private void buildAssetVersionModel(List<AssetOption> assets) {
        Set<String> versionSet = new LinkedHashSet<>();
        for (AssetOption asset : assets) {
            for (String version : asset.bevyVersions()) {
                if (!ASSET_VERSION_WILDCARD.equals(version)) {
                    versionSet.add(version);
                }
            }
        }
        List<String> versions = new ArrayList<>(versionSet);
        versions.sort(BevyProjectGeneratorPeer::compareVersionsDescending);

        List<String> comboValues = new ArrayList<>();
        comboValues.add(ASSET_VERSION_ALL);
        comboValues.addAll(versions);

        suppressAssetVersionEvents = true;
        replaceComboValues(assetsVersionCombo, comboValues);
        suppressAssetVersionEvents = false;
    }

    private void syncAssetVersionFilterToBevyVersion() {
        if (assetsVersionCombo.getItemCount() == 0) {
            return;
        }
        String selectedBevyVersion = normalizeAssetVersion(selectedOrFallback(bevyVersionCombo, FALLBACK_BEVY_VERSIONS.get(0)));
        boolean hasBevyVersion = comboContainsValue(assetsVersionCombo, selectedBevyVersion);

        suppressAssetVersionEvents = true;
        assetsVersionCombo.setSelectedItem(hasBevyVersion ? selectedBevyVersion : ASSET_VERSION_ALL);
        suppressAssetVersionEvents = false;
        assetVersionManuallySelected = false;
        applyAssetFilters();
    }

    private static boolean comboContainsValue(JComboBox<String> comboBox, String expectedValue) {
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            String value = comboBox.getItemAt(index);
            if (expectedValue.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private void applyAssetFilters() {
        if (allAssetOptions.isEmpty()) {
            assetsCheckboxPanel.removeAll();
            assetsCheckboxPanel.revalidate();
            assetsCheckboxPanel.repaint();
            assetsStatusLabel.setText("No assets loaded.");
            return;
        }

        String selectedVersion = selectedOrFallback(assetsVersionCombo, ASSET_VERSION_ALL);
        List<String> searchTerms = normalizedSearchTerms(assetsSearchField.getText());
        int visibleAssets = 0;

        assetsCheckboxPanel.removeAll();
        for (AssetOption asset : allAssetOptions) {
            if (!matchesVersionFilter(asset, selectedVersion)) {
                continue;
            }
            if (!matchesSearchTerms(asset, searchTerms)) {
                continue;
            }
            JComponent row = assetRows.get(asset);
            if (row == null) {
                continue;
            }
            assetsCheckboxPanel.add(row);
            visibleAssets++;
        }

        assetsCheckboxPanel.revalidate();
        assetsCheckboxPanel.repaint();
        assetsStatusLabel.setText("Showing " + visibleAssets + " of " + allAssetOptions.size() + " assets.");
    }

    private static boolean matchesVersionFilter(AssetOption asset, String selectedVersion) {
        if (ASSET_VERSION_ALL.equals(selectedVersion)) {
            return true;
        }
        if (asset.bevyVersions().isEmpty()) {
            return true;
        }
        for (String version : asset.bevyVersions()) {
            if (ASSET_VERSION_WILDCARD.equals(version) || selectedVersion.equals(version)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSearchTerms(AssetOption asset, List<String> searchTerms) {
        if (searchTerms.isEmpty()) {
            return true;
        }
        String searchText = (asset.title() + " " + asset.description() + " " + asset.url() + " " + String.join(" ", asset.bevyVersions()))
                .toLowerCase(Locale.ROOT);
        for (String term : searchTerms) {
            if (!searchText.contains(term)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> normalizedSearchTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(term -> !term.isBlank())
                .toList();
    }

    private void setFeatures(List<String> features) {
        featureCheckboxes.clear();
        featuresListPanel.removeAll();

        if (features.isEmpty()) {
            featuresStatusLabel.setText("No features found.");
            featuresStatusLabel.setVisible(true);
            updateExtendedFrameworkControls();
            featuresListPanel.revalidate();
            featuresListPanel.repaint();
            return;
        }

        featuresStatusLabel.setVisible(false);
        for (String feature : features) {
            JBCheckBox featureCheckbox = new JBCheckBox(feature, false);
            featureCheckbox.addActionListener(event -> updateExtendedFrameworkControls());
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
        updateExtendedFrameworkControls();
    }

    private void updateBeuControlsEnabled() {
        boolean enabled = includeBeuCheckbox.isSelected();
        beuVersionCombo.setEnabled(enabled);
        featuresFieldPanel.setVisible(enabled);
        featuresScrollPane.setEnabled(enabled);
        for (JBCheckBox checkBox : featureCheckboxes.values()) {
            checkBox.setEnabled(enabled);
        }
        updateExtendedFrameworkControls();
    }

    private void updateExtendedFrameworkControls() {
        boolean enabled = includeBeuCheckbox.isSelected() && isExtendedFrameworkSelected();
        registryFileNameField.setEnabled(enabled);
        useRoutingCheckbox.setEnabled(enabled);
        if (!enabled) {
            useRoutingCheckbox.setSelected(false);
        }
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
                assets.add(new AssetOption(
                        title,
                        normalizedUrl,
                        parseAssetDescription(assetCardHtml),
                        parseAssetVersions(assetCardHtml),
                        parsePreviewUrl(assetCardHtml)
                ));
            }
            if (assets.size() >= 300) {
                break;
            }
        }
        return assets;
    }

    private static String parseAssetDescription(String assetCardHtml) {
        Matcher matcher = DESCRIPTION_PATTERN.matcher(assetCardHtml);
        if (!matcher.find()) {
            return "";
        }
        return cleanupHtmlText(matcher.group(1));
    }

    private static List<String> parseAssetVersions(String assetCardHtml) {
        int markerIndex = StringUtil.indexOfIgnoreCase(assetCardHtml, "asset-card__bevy-versions", 0);
        if (markerIndex < 0) {
            return List.of();
        }

        int sectionStart = assetCardHtml.lastIndexOf("<div", markerIndex);
        if (sectionStart < 0) {
            return List.of();
        }
        int sectionEnd = findMatchingDivEnd(assetCardHtml, sectionStart);
        if (sectionEnd <= sectionStart) {
            return List.of();
        }

        Set<String> versions = new LinkedHashSet<>();
        String section = assetCardHtml.substring(sectionStart, sectionEnd);
        Matcher tagMatcher = ASSET_TAG_PATTERN.matcher(section);
        while (tagMatcher.find()) {
            String normalizedVersion = normalizeAssetVersion(cleanupHtmlText(tagMatcher.group(1)));
            versions.add(normalizedVersion);
        }
        return new ArrayList<>(versions);
    }

    private static int findMatchingDivEnd(String html, int startIndex) {
        Matcher matcher = DIV_TOKEN_PATTERN.matcher(html);
        if (!matcher.find(startIndex)) {
            return -1;
        }

        int depth = 0;
        do {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.startsWith("</div")) {
                depth--;
            } else {
                depth++;
            }
            if (depth == 0) {
                return matcher.end();
            }
        } while (matcher.find());
        return -1;
    }

    private static String parsePreviewUrl(String assetCardHtml) {
        Matcher imageMatcher = SHOWCASE_IMAGE_PATTERN.matcher(assetCardHtml);
        if (imageMatcher.find()) {
            String candidate = stripQuotes(imageMatcher.group(1));
            return normalizeAssetUrl(candidate);
        }

        Matcher posterMatcher = SHOWCASE_VIDEO_POSTER_PATTERN.matcher(assetCardHtml);
        if (posterMatcher.find()) {
            String candidate = stripQuotes(posterMatcher.group(1));
            return normalizeAssetUrl(candidate);
        }

        Matcher sourceMatcher = SHOWCASE_VIDEO_SOURCE_PATTERN.matcher(assetCardHtml);
        if (sourceMatcher.find()) {
            String candidate = stripQuotes(sourceMatcher.group(1));
            return normalizeAssetUrl(candidate);
        }

        return "";
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

    private static String normalizeAssetVersion(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || "main".equals(normalized) || ASSET_VERSION_WILDCARD.equals(normalized)) {
            return ASSET_VERSION_WILDCARD;
        }
        normalized = normalized.replaceFirst("^[^\\d]+", "");
        normalized = normalized.replaceFirst("[^\\d]+$", "");

        Matcher matcher = NUMERIC_VERSION_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return ASSET_VERSION_WILDCARD;
        }
        int major = parseVersionPart(matcher.group(1));
        int minor = parseVersionPart(matcher.group(2));
        int patch = parseVersionPart(matcher.group(3));
        return major + "." + minor + "." + patch;
    }

    private static int parseVersionPart(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int compareVersionsDescending(String left, String right) {
        int[] leftParts = parseVersionTuple(left);
        int[] rightParts = parseVersionTuple(right);
        int majorCompare = Integer.compare(rightParts[0], leftParts[0]);
        if (majorCompare != 0) {
            return majorCompare;
        }
        int minorCompare = Integer.compare(rightParts[1], leftParts[1]);
        if (minorCompare != 0) {
            return minorCompare;
        }
        return Integer.compare(rightParts[2], leftParts[2]);
    }

    private static int[] parseVersionTuple(String version) {
        if (version == null || version.isBlank() || ASSET_VERSION_WILDCARD.equals(version)) {
            return new int[]{0, 0, 0};
        }
        String[] parts = version.split("\\.");
        int major = parts.length > 0 ? parseVersionPart(parts[0]) : 0;
        int minor = parts.length > 1 ? parseVersionPart(parts[1]) : 0;
        int patch = parts.length > 2 ? parseVersionPart(parts[2]) : 0;
        return new int[]{major, minor, patch};
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static @Nullable String fetchRenderedReadmeHtml(AssetOption asset) {
        String repositoryPath = extractGithubRepositoryPath(asset.url());
        if (repositoryPath == null) {
            repositoryPath = resolveGithubRepositoryPathFromCrate(asset.url());
        }
        if (repositoryPath == null) {
            return null;
        }

        try {
            String apiUrl = "https://api.github.com/repos/" + repositoryPath + "/readme";
            String readmeHtml = fetchTextWithAccept(apiUrl, "application/vnd.github.html");
            return wrapReadmeHtml(preprocessReadmeHtml(readmeHtml, repositoryPath));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable String resolveGithubRepositoryPathFromCrate(String url) {
        String crateName = extractCrateName(url);
        if (crateName == null) {
            return null;
        }

        try {
            String crateApiUrl = "https://crates.io/api/v1/crates/" + crateName;
            String json = fetchTextWithAccept(crateApiUrl, "application/json");

            String repositoryUrl = extractFirstGroup(json, CRATES_REPOSITORY_PATTERN);
            if (repositoryUrl != null && !repositoryUrl.isBlank()) {
                return extractGithubRepositoryPath(unescapeJson(repositoryUrl));
            }

            String homepageUrl = extractFirstGroup(json, CRATES_HOMEPAGE_PATTERN);
            if (homepageUrl != null && !homepageUrl.isBlank()) {
                return extractGithubRepositoryPath(unescapeJson(homepageUrl));
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable String extractCrateName(String url) {
        Matcher cratesMatcher = CRATES_IO_CRATE_PATTERN.matcher(url);
        if (cratesMatcher.matches()) {
            return cratesMatcher.group(1);
        }
        Matcher docsMatcher = DOCS_RS_CRATE_PATTERN.matcher(url);
        if (docsMatcher.matches()) {
            return docsMatcher.group(1);
        }
        return null;
    }

    private static @Nullable String extractGithubRepositoryPath(String url) {
        Matcher matcher = GITHUB_REPO_PATTERN.matcher(url);
        if (!matcher.matches()) {
            return null;
        }
        String owner = matcher.group(1);
        String repository = matcher.group(2);
        if (owner == null || owner.isBlank() || repository == null || repository.isBlank()) {
            return null;
        }

        repository = repository.endsWith(".git")
                ? repository.substring(0, repository.length() - 4)
                : repository;
        return owner + "/" + repository;
    }

    private static @Nullable String extractFirstGroup(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    private static String unescapeJson(String value) {
        return value
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static String fetchTextWithAccept(String url, String acceptHeader) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", acceptHeader)
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

    private static String wrapReadmeHtml(String readmeHtml) {
        return "<html><head><style>" +
                "*{box-sizing:border-box;max-width:100%;}" +
                "html,body{width:100%;max-width:100%;overflow-x:hidden;}" +
                "body{font-family:Inter,Segoe UI,Arial,sans-serif;font-size:13px;line-height:1.45;padding:12px;word-break:break-word;overflow-wrap:anywhere;}" +
                "img,video{max-width:100% !important;height:auto !important;display:block;float:none;clear:both;}" +
                "a{overflow-wrap:anywhere;word-break:break-word;}" +
                "code,pre{font-family:JetBrains Mono,Consolas,monospace;}" +
                "pre{overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;}" +
                "p,li,td,th{overflow-wrap:anywhere;}" +
                "table{border-collapse:collapse;max-width:100%;width:100%;table-layout:fixed;}" +
                "thead,tbody,tr,th,td{max-width:100%;overflow-wrap:anywhere;}" +
                "th,td{border:1px solid #555;padding:4px 8px;}" +
                ".readme-table,.readme-row,.readme-cell{display:block;width:100% !important;max-width:100% !important;}" +
                ".readme-cell{padding:0 0 6px 0;}" +
                "</style></head><body>" + readmeHtml + "</body></html>";
    }

    private static String preprocessReadmeHtml(String html, String repositoryPath) {
        String processed = replaceCanonicalImageSources(html);
        processed = replaceRootRelativeUrls(processed);
        processed = replaceRelativeLinks(processed, repositoryPath);
        processed = replaceRelativeImageSources(processed, repositoryPath);
        processed = normalizeReadmeTables(processed);
        processed = normalizeImageSourceUrls(processed);
        processed = normalizeReadmeImages(processed);
        return truncateReadmeHtml(processed);
    }

    private static String replaceCanonicalImageSources(String html) {
        Matcher matcher = IMG_CANONICAL_SRC_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String originalSource = matcher.group(3);
            String canonical = matcher.group(6);
            String middle = matcher.group(4).replaceAll("\\s*data-canonical-src\\s*=\\s*([\"']).*?\\1", "");
            String suffix = matcher.group(7);
            String resolvedSource = resolveImageSourceForReadme(originalSource, canonical);
            String replacement = prefix + "src=\"" + escapeHtmlAttribute(resolvedSource) + "\"" + middle + suffix;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String resolveImageSourceForReadme(String originalSource, String canonicalSource) {
        String original = StringUtil.unescapeXmlEntities(originalSource == null ? "" : originalSource).trim();
        String canonical = StringUtil.unescapeXmlEntities(canonicalSource == null ? "" : canonicalSource).trim();
        if (canonical.isBlank()) {
            return original;
        }
        return canonical;
    }

    private static String normalizeReadmeTables(String html) {
        String processed = html;
        processed = processed.replaceAll("(?is)<\\/?thead\\b[^>]*>", "");
        processed = processed.replaceAll("(?is)<\\/?tbody\\b[^>]*>", "");
        processed = processed.replaceAll("(?is)<table\\b[^>]*>", "<div class=\"readme-table\">");
        processed = processed.replaceAll("(?is)</table>", "</div>");
        processed = processed.replaceAll("(?is)<tr\\b[^>]*>", "<div class=\"readme-row\">");
        processed = processed.replaceAll("(?is)</tr>", "</div>");
        processed = processed.replaceAll("(?is)<t[dh]\\b[^>]*>", "<div class=\"readme-cell\">");
        processed = processed.replaceAll("(?is)</t[dh]>", "</div>");
        return processed;
    }

    private static String replaceRootRelativeUrls(String html) {
        Matcher matcher = ROOT_LINK_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String attribute = matcher.group(1);
            String quote = matcher.group(2);
            String path = matcher.group(3);
            String replacement = attribute + "=" + quote + "https://github.com/" + path + quote;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceRelativeLinks(String html, String repositoryPath) {
        Matcher matcher = RELATIVE_HREF_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String quote = matcher.group(1);
            String path = matcher.group(2);
            if (path.startsWith("/") || path.startsWith("#")) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String url = "https://github.com/" + repositoryPath + "/blob/HEAD/" + path;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("href=" + quote + url + quote));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceRelativeImageSources(String html, String repositoryPath) {
        Matcher matcher = RELATIVE_SRC_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String quote = matcher.group(1);
            String path = matcher.group(2);
            if (path.startsWith("/") || path.startsWith("#")) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String url = "https://raw.githubusercontent.com/" + repositoryPath + "/HEAD/" + path;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("src=" + quote + url + quote));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String normalizeImageSourceUrls(String html) {
        Matcher matcher = IMG_TAG_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String imageTag = matcher.group();
            Matcher srcMatcher = IMG_SRC_ATTR_PATTERN.matcher(imageTag);
            if (!srcMatcher.find()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(imageTag));
                continue;
            }

            String quote = srcMatcher.group(1);
            String rawSource = srcMatcher.group(2);
            String normalizedSource = normalizeReadmeImageUrl(rawSource);
            String replacement = "src=" + quote + escapeHtmlAttribute(normalizedSource) + quote;
            String normalizedTag = srcMatcher.replaceFirst(Matcher.quoteReplacement(replacement));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(normalizedTag));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String normalizeReadmeImageUrl(String source) {
        String normalized = StringUtil.unescapeXmlEntities(source == null ? "" : source).trim();
        if (normalized.isBlank()) {
            return normalized;
        }
        normalized = normalized.replace("&amp;", "&");

        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host == null) {
                return normalized;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
            String rawQuery = uri.getRawQuery();

            if (lowerHost.contains("docs.rs") && rawPath.endsWith("/badge.svg")) {
                String[] segments = rawPath.split("/");
                if (segments.length >= 2) {
                    String crate = segments[1];
                    if (!crate.isBlank()) {
                        return "https://raster.shields.io/docsrs/" + crate + "/latest.png";
                    }
                }
            }

            if (lowerHost.contains("img.shields.io")) {
                String path = rawPath;
                if (path.endsWith(".svg")) {
                    path = path.substring(0, path.length() - 4) + ".png";
                } else if (!hasExtensionInLastSegment(path)) {
                    path = path + ".png";
                }
                return "https://raster.shields.io" + path + (rawQuery == null ? "" : "?" + rawQuery);
            }

            if (lowerHost.contains("raster.shields.io")) {
                return normalized;
            }
        } catch (Exception ignored) {
        }
        return normalized;
    }

    private static boolean hasExtensionInLastSegment(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash;
    }

    private static String normalizeReadmeImages(String html) {
        Matcher matcher = IMG_TAG_PATTERN.matcher(html);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String imageTag = matcher.group();
            imageTag = IMG_STYLE_ATTR_PATTERN.matcher(imageTag).replaceAll("");
            imageTag = IMG_WIDTH_ATTR_PATTERN.matcher(imageTag).replaceAll("");
            imageTag = IMG_ALIGN_ATTR_PATTERN.matcher(imageTag).replaceAll("");

            String imageSource = "";
            Matcher srcMatcher = IMG_SRC_ATTR_PATTERN.matcher(imageTag);
            if (srcMatcher.find()) {
                imageSource = srcMatcher.group(2);
            }

            String styleAttribute;
            if (isLikelyBadgeImage(imageSource)) {
                styleAttribute = " style=\"max-width:100% !important;max-height:22px !important;height:auto !important;display:block;clear:both;margin:0 0 2px 0;\"";
            } else {
                styleAttribute = " style=\"max-width:100% !important;height:auto !important;display:block;\"";
                if (isLikelyLargeReadmeImage(imageSource)) {
                    imageTag = injectWidthAttribute(imageTag, 960);
                }
            }
            int endIndex = imageTag.lastIndexOf('>');
            if (endIndex > 0) {
                imageTag = imageTag.substring(0, endIndex) + styleAttribute + imageTag.substring(endIndex);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(imageTag));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static boolean isLikelyBadgeImage(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        return normalized.contains("shields.io")
                || normalized.contains("/badge")
                || normalized.contains("badge.svg")
                || normalized.contains("badge.png");
    }

    private static boolean isLikelyLargeReadmeImage(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String normalized = source.toLowerCase(Locale.ROOT);
        return normalized.contains("githubusercontent.com")
                || normalized.contains("raw.githubusercontent.com")
                || normalized.endsWith(".gif")
                || normalized.contains(".gif?")
                || normalized.endsWith(".mp4")
                || normalized.contains(".mp4?");
    }

    private static String injectWidthAttribute(String imageTag, int width) {
        int endIndex = imageTag.lastIndexOf('>');
        if (endIndex <= 0) {
            return imageTag;
        }
        return imageTag.substring(0, endIndex) + " width=\"" + width + "\"" + imageTag.substring(endIndex);
    }

    private static String truncateReadmeHtml(String html) {
        if (html.length() <= README_MAX_HTML_CHARS) {
            return html;
        }
        return html.substring(0, README_MAX_HTML_CHARS)
                + "<p><em>README was truncated for IDE rendering performance.</em></p>";
    }

    private static String escapeHtmlAttribute(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;");
    }

    private record AssetOption(String title, String url, String description, List<String> bevyVersions, String previewUrl) {
        private AssetOption {
            description = description == null ? "" : description.trim();
            if (bevyVersions == null || bevyVersions.isEmpty()) {
                bevyVersions = List.of();
            } else {
                bevyVersions = List.copyOf(bevyVersions);
            }
            previewUrl = previewUrl == null ? "" : previewUrl.trim();
        }
    }

    private final class AssetDetailsDialog extends DialogWrapper {
        private final AssetOption asset;
        private final JEditorPane readmePane = new JEditorPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }
        };

        private AssetDetailsDialog(AssetOption asset) {
            super(true);
            this.asset = asset;
            setTitle(asset.title());
            setResizable(true);
            init();
            getOKAction().putValue(Action.NAME, "Close");
            loadReadmeAsync();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JBPanel<?> panel = new JBPanel<>(new BorderLayout(0, 8));
            panel.setPreferredSize(new Dimension(1120, 820));

            JBPanel<?> infoPanel = new JBPanel<>();
            infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));

            String shortenedUrl = StringUtil.shortenTextWithEllipsis(asset.url(), 120, 0, true);
            JBLabel urlLabel = new JBLabel("<html><a href=''>" + escapeHtml(shortenedUrl) + "</a></html>");
            urlLabel.setToolTipText(asset.url());
            urlLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            urlLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            urlLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getButton() == MouseEvent.BUTTON1) {
                        BrowserUtil.browse(asset.url());
                    }
                }
            });
            infoPanel.add(urlLabel);
            infoPanel.add(Box.createVerticalStrut(4));

            String versionsText = asset.bevyVersions().isEmpty() ? "unknown" : String.join(", ", asset.bevyVersions());
            JBLabel versionsLabel = new JBLabel("Bevy versions: " + versionsText);
            versionsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            infoPanel.add(versionsLabel);
            infoPanel.add(Box.createVerticalStrut(6));

            JBTextArea descriptionArea = new JBTextArea(asset.description().isBlank() ? "No description available." : asset.description());
            descriptionArea.setEditable(false);
            descriptionArea.setLineWrap(true);
            descriptionArea.setWrapStyleWord(true);
            descriptionArea.setOpaque(false);
            descriptionArea.setBorder(JBUI.Borders.empty());
            descriptionArea.setAlignmentX(Component.LEFT_ALIGNMENT);
            infoPanel.add(descriptionArea);

            panel.add(infoPanel, BorderLayout.NORTH);

            readmePane.setEditable(false);
            readmePane.setContentType("text/html");
            readmePane.setText("<html><body>Loading README...</body></html>");
            readmePane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            readmePane.putClientProperty("JEditorPane.w3cLengthUnits", Boolean.TRUE);
            readmePane.addHyperlinkListener(event -> {
                if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
                    BrowserUtil.browse(event.getURL());
                }
            });
            JBScrollPane readmeScrollPane = new JBScrollPane(readmePane);
            readmeScrollPane.setBorder(JBUI.Borders.empty());
            readmeScrollPane.setHorizontalScrollBarPolicy(JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            panel.add(readmeScrollPane, BorderLayout.CENTER);
            return panel;
        }

        @Override
        protected Action @NotNull [] createActions() {
            List<Action> actions = new ArrayList<>();
            actions.add(new OpenLinkAction("Open Asset Page", asset.url()));
            actions.add(getOKAction());
            return actions.toArray(new Action[0]);
        }

        private void loadReadmeAsync() {
            readmePane.setText("<html><body>Loading README...</body></html>");
            CompletableFuture.supplyAsync(() -> fetchRenderedReadmeHtml(asset))
                    .completeOnTimeout(null, 10, TimeUnit.SECONDS)
                    .whenComplete((readmeHtml, throwable) ->
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (throwable != null || readmeHtml == null || readmeHtml.isBlank()) {
                                    String description = asset.description().isBlank()
                                            ? "No description available."
                                            : escapeHtml(asset.description());
                                    readmePane.setText(
                                            "<html><body>" +
                                                    "<h3>README unavailable</h3>" +
                                                    "<p>" + description + "</p>" +
                                                    "<p><a href='" + escapeHtml(asset.url()) + "'>Open asset page</a></p>" +
                                                    "</body></html>"
                                    );
                                    readmePane.setCaretPosition(0);
                                    return;
                                }
                                readmePane.setText(readmeHtml);
                                readmePane.setCaretPosition(0);
                            }, ModalityState.any()));
        }
    }

    private static final class OpenLinkAction extends AbstractAction {
        private final String url;

        private OpenLinkAction(@Nls String text, String url) {
            super(text);
            this.url = url;
        }

        @Override
        public void actionPerformed(java.awt.event.ActionEvent event) {
            BrowserUtil.browse(url);
        }
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
