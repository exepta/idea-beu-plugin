package com.tiltus.beu.plugin.index;

import com.intellij.openapi.project.Project;

import java.util.List;

public final class RustUiComponentIndex {
    private final Project project;

    private RustUiComponentIndex(Project project) {
        this.project = project;
    }

    public static RustUiComponentIndex get(Project project) {
        return new RustUiComponentIndex(project);
    }

    public List<String> tagNames() {
        return RustBeuIndex.get(project).componentTags();
    }
}
