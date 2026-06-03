package com.tiltus.beu.plugin.references;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import com.tiltus.beu.plugin.index.RustBeuIndex;
import com.tiltus.beu.plugin.index.RustStructFieldIndex;
import org.jetbrains.annotations.NotNull;

final class BeuHtmlEnumVariantReference extends PsiReferenceBase<PsiElement> {
    private final String enumName;
    private final String variantName;

    BeuHtmlEnumVariantReference(
            @NotNull PsiElement element,
            @NotNull TextRange rangeInElement,
            @NotNull String enumName,
            @NotNull String variantName
    ) {
        super(element, rangeInElement, true);
        this.enumName = enumName;
        this.variantName = variantName;
    }

    @Override
    public PsiElement resolve() {
        RustStructFieldIndex index = RustStructFieldIndex.get(myElement.getProject());
        RustBeuIndex.StructTarget target = index.enumVariantTargetForEnumAndVariant(enumName, variantName);
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
