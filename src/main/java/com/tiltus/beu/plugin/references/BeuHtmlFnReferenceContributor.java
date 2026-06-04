package com.tiltus.beu.plugin.references;

import com.intellij.patterns.XmlPatterns;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.ProcessingContext;
import com.tiltus.beu.plugin.html.BeuHtmlEvents;
import org.jetbrains.annotations.NotNull;

public final class BeuHtmlFnReferenceContributor extends PsiReferenceContributor {
    private static boolean isPlainFunctionName(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (!Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch != '_' && !Character.isLetterOrDigit(ch)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(XmlPatterns.xmlAttributeValue(), new PsiReferenceProvider() {
            @Override
            public com.intellij.psi.PsiReference @NotNull [] getReferencesByElement(@NotNull com.intellij.psi.PsiElement element, @NotNull ProcessingContext context) {
                if (!(element instanceof XmlAttributeValue attributeValue)) {
                    return com.intellij.psi.PsiReference.EMPTY_ARRAY;
                }

                if (!(attributeValue.getParent() instanceof XmlAttribute attribute)) {
                    return com.intellij.psi.PsiReference.EMPTY_ARRAY;
                }
                if (!BeuHtmlEvents.isFunctionHandlerAttribute(attribute.getName())) {
                    return com.intellij.psi.PsiReference.EMPTY_ARRAY;
                }

                String functionName = attributeValue.getValue();
                if (functionName == null || functionName.isBlank()) {
                    return com.intellij.psi.PsiReference.EMPTY_ARRAY;
                }
                if (!isPlainFunctionName(functionName)) {
                    return com.intellij.psi.PsiReference.EMPTY_ARRAY;
                }

                return new com.intellij.psi.PsiReference[]{new BeuHtmlFnReference(attributeValue, functionName)};
            }
        });
    }
}
