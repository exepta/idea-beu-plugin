package com.tiltus.beu.plugin.project;

import java.util.List;

public record BevyProjectSettings(
        String bevyVersion,
        String rustEdition,
        boolean createGitignore,
        boolean includeBevyExtendedUi,
        String bevyExtendedUiVersion,
        List<String> bevyExtendedUiFeatures,
        List<BevyAssetSelection> selectedAssets,
        boolean useRouting,
        String registryFileName
) {
    public BevyProjectSettings {
        bevyExtendedUiFeatures = List.copyOf(bevyExtendedUiFeatures);
        selectedAssets = List.copyOf(selectedAssets);
    }

    public record BevyAssetSelection(String title, String url) {
    }
}
