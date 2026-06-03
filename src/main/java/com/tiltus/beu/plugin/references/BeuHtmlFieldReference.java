package com.tiltus.beu.plugin.references;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import com.tiltus.beu.plugin.html.BeuHtmlUseResolver;
import com.tiltus.beu.plugin.index.RustBeuIndex;
import com.tiltus.beu.plugin.index.RustStructFieldIndex;
import org.jetbrains.annotations.NotNull;

final class BeuHtmlFieldReference extends PsiReferenceBase<PsiElement> {
    private final String objectName;
    private final String fieldName;

    BeuHtmlFieldReference(
            @NotNull PsiElement element,
            @NotNull TextRange rangeInElement,
            @NotNull String objectName,
            @NotNull String fieldName
    ) {
        super(element, rangeInElement, true);
        this.objectName = objectName;
        this.fieldName = fieldName;
    }

    @Override
    public PsiElement resolve() {
        PsiFile htmlFile = myElement.getContainingFile();
        if (htmlFile == null) {
            return null;
        }

        RustStructFieldIndex index = RustStructFieldIndex.get(myElement.getProject());
        String fromUse = BeuHtmlUseResolver.resolveStructNameFromUse(htmlFile, objectName);
        String preferredStructName = fromUse != null ? fromUse : BeuHtmlUseResolver.resolveStructName(htmlFile, objectName);
        String structName = index.resolveStructNameForObject(objectName, preferredStructName, false);
        if (structName == null) {
            structName = index.resolveStructNameForObject(objectName, preferredStructName, true);
        }
        if (structName == null || structName.isBlank()) {
            return null;
        }

        RustBeuIndex.StructTarget target = index.fieldTargetForStructAndField(structName, fieldName);
        if (target == null) {
            return null;
        }

        PsiFile targetFile = PsiManager.getInstance(myElement.getProject()).findFile(target.file());
        if (targetFile == null) {
            return null;
        }
        PsiElement targetElement = targetFile.findElementAt(target.offset());
        return targetElement == null ? targetFile : targetElement;
    }

    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
