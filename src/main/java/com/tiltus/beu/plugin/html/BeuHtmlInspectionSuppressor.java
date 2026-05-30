package com.tiltus.beu.plugin.html;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

public final class BeuHtmlInspectionSuppressor implements InspectionSuppressor {
    private static final String HTML_UNKNOWN_TAG = "HtmlUnknownTag";
    private static final String HTML_UNKNOWN_ATTRIBUTE = "HtmlUnknownAttribute";
    private static final Set<String> JS_UNRESOLVED_IDS = Set.of("JSUnresolvedReference", "TypeScriptUnresolvedReference");

    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (HTML_UNKNOWN_TAG.equals(toolId)) {
            XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class, false);
            return tag != null && isCustomTag(element, tag.getName());
        }

        if (HTML_UNKNOWN_ATTRIBUTE.equals(toolId)) {
            XmlAttribute attribute = PsiTreeUtil.getParentOfType(element, XmlAttribute.class, false);
            if (attribute == null) {
                return false;
            }
            XmlTag tag = attribute.getParent();
            String tagName = tag == null ? null : tag.getName();
            return BeuHtmlEvents.isSupportedForTag(attribute.getName(), tagName)
                    || BeuHtmlWidgets.isSupportedAttribute(attribute.getName(), tagName);
        }

        if (JS_UNRESOLVED_IDS.contains(toolId)) {
            XmlAttributeValue attributeValue = PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false);
            if (attributeValue == null) {
                return false;
            }
            if (!(attributeValue.getParent() instanceof XmlAttribute attribute)) {
                return false;
            }
            // Keep HTML handler values out of JS unresolved checks.
            // Navigation/resolve stays handled by BeU reference contributor.
            return BeuHtmlEvents.isFunctionHandlerAttribute(attribute.getName());
        }

        return false;
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@NotNull PsiElement element, @NotNull String toolId) {
        return new SuppressQuickFix[0];
    }

    private static boolean isCustomTag(PsiElement element, String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return false;
        }
        if ("switch".equalsIgnoreCase(tagName)) {
            return true;
        }
        if (BeuHtmlWidgets.isKnownTag(tagName)) {
            return true;
        }
        // Custom HTML elements are expected to include a dash.
        // This avoids expensive project-wide lookups while typing tag names.
        return tagName.indexOf('-') >= 0;
    }
}
