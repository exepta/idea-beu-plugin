package com.tiltus.beu.plugin.html;

import com.intellij.codeInsight.highlighting.HighlightErrorFilter;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

public final class BeuHtmlHighlightErrorFilter extends HighlightErrorFilter {
    @Override
    public boolean shouldHighlightErrorElement(@NotNull PsiErrorElement element) {
        if (isStyleAttributeTemplateError(element)) {
            return false;
        }
        if (isStyleTagTemplateError(element)) {
            return false;
        }
        return true;
    }

    private static boolean isStyleAttributeTemplateError(PsiElement element) {
        PsiElement anchor = resolveHostElement(element);
        XmlAttributeValue attributeValue = PsiTreeUtil.getParentOfType(anchor, XmlAttributeValue.class, false);
        if (attributeValue == null) {
            return false;
        }
        if (!(attributeValue.getParent() instanceof XmlAttribute attribute)) {
            return false;
        }
        if (!"style".equalsIgnoreCase(attribute.getName())) {
            return false;
        }
        return hasTemplateExpression(attributeValue.getValue());
    }

    private static boolean isStyleTagTemplateError(PsiElement element) {
        PsiElement anchor = resolveHostElement(element);
        XmlTag tag = PsiTreeUtil.getParentOfType(anchor, XmlTag.class, false);
        if (tag == null || !"style".equalsIgnoreCase(tag.getName())) {
            return false;
        }
        return hasTemplateExpression(tag.getText());
    }

    private static boolean hasTemplateExpression(String text) {
        return text != null && text.contains("{{") && text.contains("}}");
    }

    private static PsiElement resolveHostElement(PsiElement element) {
        if (element == null) {
            return null;
        }
        if (PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false) != null
                || PsiTreeUtil.getParentOfType(element, XmlTag.class, false) != null) {
            return element;
        }
        PsiElement host = InjectedLanguageManager.getInstance(element.getProject()).getInjectionHost(element);
        return host == null ? element : host;
    }
}
