package com.tiltus.beu.plugin.html;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

public final class BeuHtmlInspectionSuppressor implements InspectionSuppressor {
    private static final String HTML_UNKNOWN_TAG = "HtmlUnknownTag";
    private static final String HTML_UNKNOWN_ATTRIBUTE = "HtmlUnknownAttribute";
    private static final Set<String> HTML_UNKNOWN_TARGET_IDS = Set.of(
            "HtmlUnknownTarget",
            "XmlPathReference"
    );
    private static final Set<String> URL_LIKE_ATTRIBUTES = Set.of(
            "src", "href", "action", "poster", "data", "srcset"
    );
    private static final Set<String> JS_UNRESOLVED_IDS = Set.of("JSUnresolvedReference", "TypeScriptUnresolvedReference");

    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (HTML_UNKNOWN_TAG.equals(toolId)) {
            XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class, false);
            return tag != null && isCustomTag(tag.getName());
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

        if (HTML_UNKNOWN_TARGET_IDS.contains(toolId) || toolId.contains("UnknownTarget")) {
            XmlAttributeValue attributeValue = PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false);
            if (attributeValue == null) {
                return false;
            }
            if (!(attributeValue.getParent() instanceof XmlAttribute attribute)) {
                return false;
            }
            String attributeName = attribute.getName() == null ? "" : attribute.getName().toLowerCase(Locale.ROOT);
            if (!URL_LIKE_ATTRIBUTES.contains(attributeName)) {
                return false;
            }

            String value = attributeValue.getValue();
            if (value == null) {
                return false;
            }
            return value.contains("{{") && value.contains("}}");
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

        if (toolId.startsWith("Css")) {
            if (isStyleAttributeTemplateValue(element)) {
                return true;
            }
            if (isInsideStyleTagTemplate(element)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@NotNull PsiElement element, @NotNull String toolId) {
        return new SuppressQuickFix[0];
    }

    private static boolean isCustomTag(String tagName) {
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

    private static boolean isStyleAttributeTemplateValue(PsiElement element) {
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

    private static boolean isInsideStyleTagTemplate(PsiElement element) {
        PsiElement anchor = resolveHostElement(element);
        XmlTag tag = PsiTreeUtil.getParentOfType(anchor, XmlTag.class, false);
        if (tag == null || !"style".equalsIgnoreCase(tag.getName())) {
            return false;
        }
        return hasTemplateExpression(tag.getText());
    }

    private static boolean hasTemplateExpression(String value) {
        return value != null && value.contains("{{") && value.contains("}}");
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
