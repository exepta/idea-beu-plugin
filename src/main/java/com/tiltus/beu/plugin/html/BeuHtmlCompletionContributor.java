package com.tiltus.beu.plugin.html;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.util.ProcessingContext;
import com.tiltus.beu.plugin.index.RustStructFieldIndex;
import com.tiltus.beu.plugin.index.RustUiComponentIndex;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BeuHtmlCompletionContributor extends CompletionContributor {
    private static final class ObjectAccessPrefix {
        private final String objectName;
        private final String fieldPrefix;

        private ObjectAccessPrefix(String objectName, String fieldPrefix) {
            this.objectName = objectName;
            this.fieldPrefix = fieldPrefix;
        }
    }

    public BeuHtmlCompletionContributor() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<>() {
            @Override
            protected void addCompletions(
                    @NotNull CompletionParameters parameters,
                    @NotNull ProcessingContext context,
                    @NotNull CompletionResultSet result
            ) {
                PsiElement position = parameters.getPosition();
                if (isExpressionTextContext(position)) {
                    addDirectiveCompletions(result);
                    addRustStructFieldCompletions(parameters, result);
                }

                if (isInAttributeNameArea(position)) {
                    addAttributeCompletions(position, result);
                }

                addComponentTagCompletions(parameters, result);
            }
        });
    }

    private static boolean isExpressionTextContext(PsiElement position) {
        if (PsiTreeUtil.getParentOfType(position, XmlAttributeValue.class, false) != null) {
            return false;
        }
        if (PsiTreeUtil.getParentOfType(position, XmlAttribute.class, false) != null) {
            return false;
        }
        return PsiTreeUtil.getParentOfType(position, XmlText.class, false) != null || PsiTreeUtil.getParentOfType(position, XmlTag.class, false) == null;
    }

    private static boolean isInAttributeNameArea(PsiElement position) {
        if (PsiTreeUtil.getParentOfType(position, XmlText.class, false) != null) {
            return false;
        }
        if (PsiTreeUtil.getParentOfType(position, XmlAttributeValue.class, false) != null) {
            return false;
        }
        return PsiTreeUtil.getParentOfType(position, XmlAttribute.class, false) != null;
    }

    private static void addDirectiveCompletions(CompletionResultSet result) {
        result.addElement(LookupElementBuilder.create("@use").withTypeText("BeU directive"));
        result.addElement(LookupElementBuilder.create("@if").withTypeText("BeU directive"));
        result.addElement(LookupElementBuilder.create("@else").withTypeText("BeU directive"));
        result.addElement(LookupElementBuilder.create("@for").withTypeText("BeU directive"));
    }

    private static void addAttributeCompletions(PsiElement position, CompletionResultSet result) {
        XmlTag tag = PsiTreeUtil.getParentOfType(position, XmlTag.class, false);
        String tagName = tag == null ? null : tag.getName();

        Set<String> emitted = new LinkedHashSet<>();
        for (BeuHtmlEvents.EventDefinition definition : BeuHtmlEvents.allForTag(tagName)) {
            if (emitted.add(definition.name())) {
                result.addElement(LookupElementBuilder.create(definition.name()).withTypeText("BeU attribute"));
            }
            for (String alias : definition.aliases()) {
                if (emitted.add(alias)) {
                    result.addElement(
                            LookupElementBuilder.create(alias)
                                    .withTypeText("Alias of " + definition.name())
                    );
                }
            }
        }

        for (BeuHtmlWidgets.AttributeDefinition definition : BeuHtmlWidgets.attributesForTag(tagName)) {
            if (emitted.add(definition.name())) {
                result.addElement(
                        LookupElementBuilder.create(definition.name())
                                .withTypeText("BeU widget attribute")
                );
            }
        }
    }

    private static void addRustStructFieldCompletions(CompletionParameters parameters, CompletionResultSet result) {
        ObjectAccessPrefix access = objectAccessPrefix(parameters);
        if (access == null) {
            return;
        }

        String htmlText = parameters.getEditor().getDocument().getText();
        RustStructFieldIndex index = RustStructFieldIndex.get(parameters.getOriginalFile().getProject());
        String fromUse = BeuHtmlUseResolver.resolveStructNameFromUse(htmlText, access.objectName);
        String preferredStructName = fromUse != null ? fromUse : BeuHtmlUseResolver.resolveStructName(htmlText, access.objectName);
        String structName = index.resolveStructNameForObject(access.objectName, preferredStructName, false);
        if (structName == null) {
            structName = index.resolveStructNameForObject(access.objectName, preferredStructName, true);
        }
        if (structName == null || structName.isBlank()) {
            return;
        }

        CompletionResultSet prefixedResult = result.withPrefixMatcher(access.fieldPrefix);

        List<String> fields = index.fieldsForStructName(structName);
        for (String field : fields) {
            prefixedResult.addElement(
                    LookupElementBuilder.create(field)
                            .withTypeText("Rust struct field", true)
            );
        }
    }

    private static void addComponentTagCompletions(CompletionParameters parameters, CompletionResultSet result) {
        String typedPrefix = tagPrefix(parameters);
        if (typedPrefix == null) {
            return;
        }

        CompletionResultSet prefixedResult = result.withPrefixMatcher(typedPrefix);

        Set<String> tags = new LinkedHashSet<>();
        tags.addAll(BeuHtmlWidgets.allTagNames());
        // Typing in tag context can trigger frequent auto-popup completion.
        // Keep this path lightweight and avoid project-wide Rust scans on autopopup.
        if (!parameters.isAutoPopup()) {
            tags.addAll(RustUiComponentIndex.get(parameters.getOriginalFile().getProject()).tagNames());
        }

        for (String tag : tags) {
            prefixedResult.addElement(
                    LookupElementBuilder.create(tag)
                            .withTypeText("BeU component tag", true)
            );
        }
    }

    private static String textBeforeCaret(CompletionParameters parameters, int maxLength) {
        CharSequence chars = parameters.getEditor().getDocument().getCharsSequence();
        int offset = Math.min(parameters.getEditor().getCaretModel().getOffset(), chars.length());
        int start = Math.max(0, offset - maxLength);
        return chars.subSequence(start, offset).toString();
    }

    private static ObjectAccessPrefix objectAccessPrefix(CompletionParameters parameters) {
        CharSequence chars = parameters.getEditor().getDocument().getCharsSequence();
        int caret = Math.min(parameters.getEditor().getCaretModel().getOffset(), chars.length());
        if (caret <= 0) {
            return null;
        }

        int fieldEnd = caret;
        int fieldStart = fieldEnd;
        while (fieldStart > 0 && isIdentifierChar(chars.charAt(fieldStart - 1))) {
            fieldStart--;
        }

        int dotIndex = fieldStart - 1;
        while (dotIndex >= 0 && Character.isWhitespace(chars.charAt(dotIndex))) {
            dotIndex--;
        }
        if (dotIndex < 0 || chars.charAt(dotIndex) != '.') {
            return null;
        }

        int objectEnd = dotIndex - 1;
        while (objectEnd >= 0 && Character.isWhitespace(chars.charAt(objectEnd))) {
            objectEnd--;
        }
        if (objectEnd < 0 || !isIdentifierChar(chars.charAt(objectEnd))) {
            return null;
        }

        int objectStart = objectEnd;
        while (objectStart > 0 && isIdentifierChar(chars.charAt(objectStart - 1))) {
            objectStart--;
        }

        String objectName = chars.subSequence(objectStart, objectEnd + 1).toString();
        String fieldPrefix = chars.subSequence(fieldStart, fieldEnd).toString();
        if (objectName.isBlank()) {
            return null;
        }
        return new ObjectAccessPrefix(objectName, fieldPrefix);
    }

    private static String tagPrefix(CompletionParameters parameters) {
        String prefixWindow = textBeforeCaret(parameters, 200);
        int lt = Math.max(prefixWindow.lastIndexOf('<'), prefixWindow.lastIndexOf("</"));
        if (lt < 0) {
            return null;
        }

        String tail = prefixWindow.substring(lt);
        if (!(tail.startsWith("<") || tail.startsWith("</"))) {
            return null;
        }
        if (tail.contains(">")) {
            return null;
        }

        String raw = tail.startsWith("</") ? tail.substring(2) : tail.substring(1);
        if (raw.isEmpty()) {
            return "";
        }
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (!isTagNameChar(ch)) {
                return null;
            }
        }
        return raw;
    }

    private static boolean isIdentifierChar(char ch) {
        return ch == '_' || Character.isLetterOrDigit(ch);
    }

    private static boolean isTagNameChar(char ch) {
        return ch == '_' || ch == '-' || Character.isLetterOrDigit(ch);
    }
}
