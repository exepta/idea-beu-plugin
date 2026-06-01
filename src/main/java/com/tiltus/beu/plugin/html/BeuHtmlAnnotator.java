package com.tiltus.beu.plugin.html;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BeuHtmlAnnotator implements Annotator {
    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile("@(use|for|if|else)\\b");
    private static final Pattern USE_STATEMENT_PATTERN = Pattern.compile("@use\\s+(\"(?:[^\"\\\\]|\\\\.)*\")\\s+(as)\\b");
    private static final Pattern OBJECT_ATTRIBUTE_PATTERN = Pattern.compile("\\b([A-Za-z_][\\w]*)\\.([A-Za-z_][\\w]*)\\b");

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof XmlAttribute xmlAttribute) {
            annotateAttribute(xmlAttribute, holder);
            return;
        }

        if (!(element instanceof PsiFile psiFile)) {
            return;
        }

        String fileName = psiFile.getName().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".html")) {
            return;
        }

        String fileText = psiFile.getText();
        if (fileText == null || fileText.isEmpty() || (!fileText.contains("@") && !fileText.contains("."))) {
            return;
        }

        annotateInlineText(fileText, 0, holder);
    }

    private static void annotateInlineText(String text, int startOffset, AnnotationHolder holder) {
        Matcher directiveMatcher = DIRECTIVE_PATTERN.matcher(text);
        while (directiveMatcher.find()) {
            annotateRange(
                    holder,
                    startOffset + directiveMatcher.start(),
                    startOffset + directiveMatcher.end(),
                    BeuHtmlHighlighting.DIRECTIVE_KEYWORD
            );
        }

        Matcher useMatcher = USE_STATEMENT_PATTERN.matcher(text);
        while (useMatcher.find()) {
            annotateRange(
                    holder,
                    startOffset + useMatcher.start(1),
                    startOffset + useMatcher.end(1),
                    BeuHtmlHighlighting.USE_STRING
            );
            annotateRange(
                    holder,
                    startOffset + useMatcher.start(2),
                    startOffset + useMatcher.end(2),
                    BeuHtmlHighlighting.DIRECTIVE_KEYWORD
            );
        }

        Matcher objectAttributeMatcher = OBJECT_ATTRIBUTE_PATTERN.matcher(text);
        while (objectAttributeMatcher.find()) {
            annotateRange(
                    holder,
                    startOffset + objectAttributeMatcher.start(1),
                    startOffset + objectAttributeMatcher.end(1),
                    BeuHtmlHighlighting.OBJECT_REFERENCE
            );
            annotateRange(
                    holder,
                    startOffset + objectAttributeMatcher.start(2),
                    startOffset + objectAttributeMatcher.end(2),
                    BeuHtmlHighlighting.OBJECT_ATTRIBUTE
            );
        }
    }

    private static void annotateAttribute(XmlAttribute xmlAttribute, AnnotationHolder holder) {
        XmlTag tag = xmlAttribute.getParent();
        String tagName = tag == null ? null : tag.getName();
        if (!BeuHtmlEvents.isSupportedForTag(xmlAttribute.getName(), tagName)
                && !BeuHtmlWidgets.isSupportedAttribute(xmlAttribute.getName(), tagName)) {
            return;
        }

        int nameStart = xmlAttribute.getTextRange().getStartOffset();
        annotateRange(
                holder,
                nameStart,
                nameStart + xmlAttribute.getName().length(),
                BeuHtmlHighlighting.EVENT_ATTRIBUTE
        );
    }

    private static void annotateRange(
            AnnotationHolder holder,
            int startOffset,
            int endOffset,
            com.intellij.openapi.editor.colors.TextAttributesKey textAttributesKey
    ) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange.create(startOffset, endOffset))
                .textAttributes(textAttributesKey)
                .create();
    }
}
