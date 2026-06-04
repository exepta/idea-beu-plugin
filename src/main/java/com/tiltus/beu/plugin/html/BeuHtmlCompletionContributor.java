package com.tiltus.beu.plugin.html;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.InsertionContext;
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
import com.intellij.util.PlatformIcons;
import com.intellij.util.ProcessingContext;
import com.tiltus.beu.plugin.index.RustStructFieldIndex;
import com.tiltus.beu.plugin.index.RustUiComponentIndex;
import com.tiltus.beu.plugin.index.RustUsePathIndex;
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

    private static final class EnumAccessPrefix {
        private final String enumName;
        private final String memberPrefix;

        private EnumAccessPrefix(String enumName, String memberPrefix) {
            this.enumName = enumName;
            this.memberPrefix = memberPrefix;
        }
    }

    private static final class UsePathContext {
        private final String typedPrefix;

        private UsePathContext(String typedPrefix) {
            this.typedPrefix = typedPrefix;
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

                ObjectAccessPrefix objectAccess = objectAccessPrefix(parameters);
                if (objectAccess != null) {
                    addRustStructFieldCompletions(parameters, result, objectAccess);
                    result.stopHere();
                    return;
                }

                EnumAccessPrefix enumAccess = enumAccessPrefix(parameters);
                if (enumAccess != null) {
                    addRustEnumMemberCompletions(parameters, result, enumAccess);
                    result.stopHere();
                    return;
                }

                if (isExpressionTextContext(position)) {
                    addUseDirectivePathCompletions(parameters, result);
                    addDirectiveCompletions(result);
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
        ObjectAccessPrefix objectAccess = objectAccessPrefix(parameters);
        if (objectAccess == null) {
            return;
        }
        addRustStructFieldCompletions(parameters, result, objectAccess);
    }

    private static void addRustStructFieldCompletions(
            CompletionParameters parameters,
            CompletionResultSet result,
            ObjectAccessPrefix objectAccess
    ) {

        String htmlText = parameters.getEditor().getDocument().getText();
        RustStructFieldIndex index = RustStructFieldIndex.get(parameters.getOriginalFile().getProject());
        String fromUse = BeuHtmlUseResolver.resolveStructNameFromUse(htmlText, objectAccess.objectName);
        String preferredStructName = fromUse != null ? fromUse : BeuHtmlUseResolver.resolveStructName(htmlText, objectAccess.objectName);
        String structName = index.resolveStructNameForObject(objectAccess.objectName, preferredStructName, false);
        if (structName == null) {
            structName = index.resolveStructNameForObject(objectAccess.objectName, preferredStructName, true);
        }
        if (structName == null || structName.isBlank()) {
            return;
        }

        CompletionResultSet prefixedResult = result.withPrefixMatcher(objectAccess.fieldPrefix);

        List<String> fields = index.fieldsForStructName(structName);
        List<String> methods = index.methodsForStructName(structName);
        Set<String> emitted = new LinkedHashSet<>();
        for (String field : fields) {
            if (!emitted.add(field)) {
                continue;
            }
            String fieldType = index.fieldTypeForStructAndField(structName, field);
            prefixedResult.addElement(
                    LookupElementBuilder.create(field)
                            .withIcon(PlatformIcons.FIELD_ICON)
                            .withTypeText(fieldType == null || fieldType.isBlank() ? "value" : fieldType, true)
            );
        }
        for (String method : methods) {
            if (!emitted.add(method)) {
                continue;
            }
            String params = index.methodParametersForStructAndMethod(structName, method);
            String paramsTail = params == null ? "()" : "(" + params + ")";
            String returnType = index.methodReturnTypeForStructAndMethod(structName, method);
            prefixedResult.addElement(
                    LookupElementBuilder.create(method)
                            .withIcon(PlatformIcons.METHOD_ICON)
                            .withTypeText(returnType == null || returnType.isBlank() ? "void" : returnType, true)
                            .withTailText(paramsTail, true)
                            .withInsertHandler((insertionContext, item) -> ensureMethodCallSuffix(insertionContext))
            );
        }
    }

    private static void addUseDirectivePathCompletions(CompletionParameters parameters, CompletionResultSet result) {
        UsePathContext context = usePathContext(parameters);
        if (context == null) {
            return;
        }

        CompletionResultSet prefixedResult = result.withPrefixMatcher(context.typedPrefix);
        List<RustUsePathIndex.PathVariant> variants = RustUsePathIndex.get(parameters.getOriginalFile().getProject())
                .complete(context.typedPrefix);

        Set<String> emitted = new LinkedHashSet<>();
        for (RustUsePathIndex.PathVariant variant : variants) {
            String insertText = variant.insertText();
            if (!emitted.add(insertText)) {
                continue;
            }
            prefixedResult.addElement(
                    LookupElementBuilder.create(insertText)
                            .withTypeText(variant.module() ? "mod" : "item", true)
            );
        }
    }

    private static void addRustEnumMemberCompletions(CompletionParameters parameters, CompletionResultSet result) {
        EnumAccessPrefix enumAccess = enumAccessPrefix(parameters);
        if (enumAccess == null) {
            return;
        }
        addRustEnumMemberCompletions(parameters, result, enumAccess);
    }

    private static void addRustEnumMemberCompletions(
            CompletionParameters parameters,
            CompletionResultSet result,
            EnumAccessPrefix enumAccess
    ) {

        RustStructFieldIndex index = RustStructFieldIndex.get(parameters.getOriginalFile().getProject());
        CompletionResultSet prefixedResult = result.withPrefixMatcher(enumAccess.memberPrefix);
        Set<String> emitted = new LinkedHashSet<>();

        for (String variant : index.variantsForEnumName(enumAccess.enumName)) {
            if (!emitted.add(variant)) {
                continue;
            }
            prefixedResult.addElement(
                    LookupElementBuilder.create(variant)
                            .withIcon(PlatformIcons.ENUM_ICON)
                            .withTypeText(enumAccess.enumName, true)
            );
        }

        for (String method : index.methodsForStructName(enumAccess.enumName)) {
            if (!emitted.add(method)) {
                continue;
            }
            String params = index.methodParametersForStructAndMethod(enumAccess.enumName, method);
            String paramsTail = params == null ? "()" : "(" + params + ")";
            String returnType = index.methodReturnTypeForStructAndMethod(enumAccess.enumName, method);
            prefixedResult.addElement(
                    LookupElementBuilder.create(method)
                            .withIcon(PlatformIcons.METHOD_ICON)
                            .withTypeText(returnType == null || returnType.isBlank() ? "void" : returnType, true)
                            .withTailText(paramsTail, true)
                            .withInsertHandler((insertionContext, item) -> ensureMethodCallSuffix(insertionContext))
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
        CharSequence documentChars = parameters.getEditor().getDocument().getCharsSequence();
        int caretOffset = Math.min(parameters.getEditor().getCaretModel().getOffset(), documentChars.length());
        if (caretOffset <= 0) {
            return null;
        }

        int fieldEnd = caretOffset;
        int fieldStart = fieldEnd;
        while (fieldStart > 0 && isIdentifierChar(documentChars.charAt(fieldStart - 1))) {
            fieldStart--;
        }

        int dotIndex = fieldStart - 1;
        while (dotIndex >= 0 && Character.isWhitespace(documentChars.charAt(dotIndex))) {
            dotIndex--;
        }
        if (dotIndex < 0 || documentChars.charAt(dotIndex) != '.') {
            return null;
        }

        int objectEnd = dotIndex - 1;
        while (objectEnd >= 0 && Character.isWhitespace(documentChars.charAt(objectEnd))) {
            objectEnd--;
        }
        if (objectEnd < 0 || !isIdentifierChar(documentChars.charAt(objectEnd))) {
            return null;
        }

        int objectStart = objectEnd;
        while (objectStart > 0 && isIdentifierChar(documentChars.charAt(objectStart - 1))) {
            objectStart--;
        }

        String objectName = documentChars.subSequence(objectStart, objectEnd + 1).toString();
        String fieldPrefix = documentChars.subSequence(fieldStart, fieldEnd).toString();
        if (objectName.isBlank()) {
            return null;
        }
        return new ObjectAccessPrefix(objectName, fieldPrefix);
    }

    private static EnumAccessPrefix enumAccessPrefix(CompletionParameters parameters) {
        CharSequence documentChars = parameters.getEditor().getDocument().getCharsSequence();
        int caretOffset = Math.min(parameters.getEditor().getCaretModel().getOffset(), documentChars.length());
        if (caretOffset <= 0) {
            return null;
        }

        int memberEnd = caretOffset;
        int memberStart = memberEnd;
        while (memberStart > 0 && isIdentifierChar(documentChars.charAt(memberStart - 1))) {
            memberStart--;
        }

        int index = memberStart - 1;
        while (index >= 0 && Character.isWhitespace(documentChars.charAt(index))) {
            index--;
        }
        if (index < 0 || documentChars.charAt(index) != ':') {
            return null;
        }

        index--;
        while (index >= 0 && Character.isWhitespace(documentChars.charAt(index))) {
            index--;
        }
        if (index < 0 || documentChars.charAt(index) != ':') {
            return null;
        }

        index--;
        while (index >= 0 && Character.isWhitespace(documentChars.charAt(index))) {
            index--;
        }
        if (index < 0 || !isIdentifierChar(documentChars.charAt(index))) {
            return null;
        }

        int enumEnd = index;
        int enumStart = enumEnd;
        while (enumStart > 0 && isIdentifierChar(documentChars.charAt(enumStart - 1))) {
            enumStart--;
        }

        String enumName = documentChars.subSequence(enumStart, enumEnd + 1).toString();
        String memberPrefix = documentChars.subSequence(memberStart, memberEnd).toString();
        if (enumName.isBlank()) {
            return null;
        }
        return new EnumAccessPrefix(enumName, memberPrefix);
    }

    private static String tagPrefix(CompletionParameters parameters) {
        String prefixWindow = textBeforeCaret(parameters, 200);
        int tagStartIndex = Math.max(prefixWindow.lastIndexOf('<'), prefixWindow.lastIndexOf("</"));
        if (tagStartIndex < 0) {
            return null;
        }

        String tagToken = prefixWindow.substring(tagStartIndex);
        if (!(tagToken.startsWith("<") || tagToken.startsWith("</"))) {
            return null;
        }
        if (tagToken.contains(">")) {
            return null;
        }

        String rawTagName = tagToken.startsWith("</") ? tagToken.substring(2) : tagToken.substring(1);
        if (rawTagName.isEmpty()) {
            return "";
        }
        for (int i = 0; i < rawTagName.length(); i++) {
            char ch = rawTagName.charAt(i);
            if (!isTagNameChar(ch)) {
                return null;
            }
        }
        return rawTagName;
    }

    private static UsePathContext usePathContext(CompletionParameters parameters) {
        CharSequence chars = parameters.getEditor().getDocument().getCharsSequence();
        int caretOffset = Math.min(parameters.getEditor().getCaretModel().getOffset(), chars.length());
        int lineStart = findLineStart(chars, caretOffset);
        int lineEnd = findLineEnd(chars, caretOffset);
        String lineText = chars.subSequence(lineStart, lineEnd).toString();
        int offsetInLine = caretOffset - lineStart;

        int useIndex = lineText.indexOf("@use");
        if (useIndex < 0 || useIndex > offsetInLine) {
            return null;
        }

        int quoteStart = lineText.indexOf('"', useIndex + 4);
        if (quoteStart < 0 || offsetInLine <= quoteStart) {
            return null;
        }

        int quoteEnd = lineText.indexOf('"', quoteStart + 1);
        if (quoteEnd >= 0 && offsetInLine > quoteEnd) {
            return null;
        }

        String typedPrefix = lineText.substring(quoteStart + 1, offsetInLine);
        return new UsePathContext(typedPrefix);
    }

    private static int findLineStart(CharSequence text, int offset) {
        if (text.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, offset - 1);
        while (index >= 0) {
            if (text.charAt(index) == '\n') {
                return index + 1;
            }
            index--;
        }
        return 0;
    }

    private static int findLineEnd(CharSequence text, int offset) {
        int index = Math.max(0, offset);
        while (index < text.length()) {
            if (text.charAt(index) == '\n') {
                return index;
            }
            index++;
        }
        return text.length();
    }

    private static boolean isIdentifierChar(char ch) {
        return ch == '_' || Character.isLetterOrDigit(ch);
    }

    private static boolean isTagNameChar(char ch) {
        return ch == '_' || ch == '-' || Character.isLetterOrDigit(ch);
    }

    private static void ensureMethodCallSuffix(InsertionContext context) {
        int tailOffset = context.getTailOffset();
        CharSequence chars = context.getDocument().getCharsSequence();
        if (tailOffset < chars.length() && chars.charAt(tailOffset) == '(') {
            return;
        }

        context.getDocument().insertString(tailOffset, "()");
        context.getEditor().getCaretModel().moveToOffset(tailOffset + 2);
        context.setTailOffset(tailOffset + 2);
    }
}
