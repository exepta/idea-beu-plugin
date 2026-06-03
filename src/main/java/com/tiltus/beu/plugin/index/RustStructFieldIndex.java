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

    public List<String> methodsForStructName(String structName) {
        return RustBeuIndex.get(project).methodsForStructName(structName);
    }

    public String structDocForStructName(String structName) {
        return RustBeuIndex.get(project).structDocForStructName(structName);
    }

    public String typeDocForName(String typeName) {
        return RustBeuIndex.get(project).typeDocForName(typeName);
    }

    public String fieldDocForStructAndField(String structName, String fieldName) {
        return RustBeuIndex.get(project).fieldDocForStructAndField(structName, fieldName);
    }

    public String fieldTypeForStructAndField(String structName, String fieldName) {
        return RustBeuIndex.get(project).fieldTypeForStructAndField(structName, fieldName);
    }

    public String methodDocForStructAndMethod(String structName, String methodName) {
        return RustBeuIndex.get(project).methodDocForStructAndMethod(structName, methodName);
    }

    public String methodParametersForStructAndMethod(String structName, String methodName) {
        return RustBeuIndex.get(project).methodParametersForStructAndMethod(structName, methodName);
    }

    public String methodReturnTypeForStructAndMethod(String structName, String methodName) {
        return RustBeuIndex.get(project).methodReturnTypeForStructAndMethod(structName, methodName);
    }

    public List<String> variantsForEnumName(String enumName) {
        return RustBeuIndex.get(project).variantsForEnumName(enumName);
    }

    public RustBeuIndex.StructTarget typeTargetForName(String typeName) {
        return RustBeuIndex.get(project).typeTargetForName(typeName);
    }

    public RustBeuIndex.StructTarget enumVariantTargetForEnumAndVariant(String enumName, String variantName) {
        return RustBeuIndex.get(project).enumVariantTargetForEnumAndVariant(enumName, variantName);
    }

    public String enumVariantDocForEnumAndVariant(String enumName, String variantName) {
        return RustBeuIndex.get(project).enumVariantDocForEnumAndVariant(enumName, variantName);
    }

    public RustBeuIndex.StructTarget structTargetForStructName(String structName) {
        return RustBeuIndex.get(project).structTargetForStructName(structName);
    }

    public RustBeuIndex.StructTarget fieldTargetForStructAndField(String structName, String fieldName) {
        return RustBeuIndex.get(project).fieldTargetForStructAndField(structName, fieldName);
    }

    public String resolveStructNameForObject(String objectName, String preferredStructName, boolean allowDeepSearch) {
        return RustBeuIndex.get(project).resolveStructNameForObject(objectName, preferredStructName, allowDeepSearch);
    }
}
