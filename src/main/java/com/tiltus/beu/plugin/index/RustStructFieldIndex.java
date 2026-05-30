package com.tiltus.beu.plugin.index;

import com.intellij.openapi.project.Project;

import java.util.List;

public final class RustStructFieldIndex {
    private final Project project;

    private RustStructFieldIndex(Project project) {
        this.project = project;
    }

    public static RustStructFieldIndex get(Project project) {
        return new RustStructFieldIndex(project);
    }

    public List<String> fieldsForStructName(String structName) {
        return RustBeuIndex.get(project).fieldsForStructName(structName);
    }

    public String structDocForStructName(String structName) {
        return RustBeuIndex.get(project).structDocForStructName(structName);
    }

    public String fieldDocForStructAndField(String structName, String fieldName) {
        return RustBeuIndex.get(project).fieldDocForStructAndField(structName, fieldName);
    }

    public String resolveStructNameForObject(String objectName, String preferredStructName, boolean allowDeepPreferredLookup) {
        return RustBeuIndex.get(project).resolveStructNameForObject(objectName, preferredStructName, allowDeepPreferredLookup);
    }
}
