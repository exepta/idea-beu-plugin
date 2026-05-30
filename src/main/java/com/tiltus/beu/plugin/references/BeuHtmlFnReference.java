package com.tiltus.beu.plugin.references;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import com.tiltus.beu.plugin.index.RustBeuIndex;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

final class BeuHtmlFnReference extends PsiReferenceBase<XmlAttributeValue> {
    private final String functionName;

    BeuHtmlFnReference(@NotNull XmlAttributeValue element, @NotNull String functionName) {
        super(element, rangeForValue(element), true);
        this.functionName = functionName;
    }

    @Override
    public PsiElement resolve() {
        List<RustBeuIndex.HtmlFunctionTarget> targets = RustBeuIndex.get(myElement.getProject()).htmlFunctionTargets(functionName);
        if (targets.isEmpty()) {
            return null;
        }
        return resolveTarget(targets.get(0));
    }

    @Override
    public Object @NotNull [] getVariants() {
        List<String> names = RustBeuIndex.get(myElement.getProject()).htmlFunctionNames();
        List<Object> variants = new ArrayList<>(names.size());
        variants.addAll(names);
        return variants.toArray();
    }

    private static TextRange rangeForValue(XmlAttributeValue value) {
        TextRange valueRange = value.getValueTextRange();
        int start = valueRange.getStartOffset() - value.getTextRange().getStartOffset();
        return TextRange.from(start, valueRange.getLength());
    }

    private PsiElement resolveTarget(RustBeuIndex.HtmlFunctionTarget target) {
        PsiFile psiFile = PsiManager.getInstance(myElement.getProject()).findFile(target.file());
        if (psiFile == null) {
            return null;
        }
        PsiElement direct = psiFile.findElementAt(target.offset());
        if (direct != null) {
            return direct;
        }
        return psiFile;
    }
}
