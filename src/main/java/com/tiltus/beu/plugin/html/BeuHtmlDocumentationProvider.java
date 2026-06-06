package com.tiltus.beu.plugin.html;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.tiltus.beu.plugin.index.RustStructFieldIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BeuHtmlDocumentationProvider extends AbstractDocumentationProvider {
    private static final Key<Integer> HOVER_OFFSET_KEY = Key.create("beu.hover.offset");
    private static final String TEMPLATE_SET_DOC = """
            With this method, you can easily and quickly modify a value and react directly to the event. If you are planning something more substantial, please use the `#[html_fn()]` macro to register your own system within Bevy!
            """;
    private static final String TEMPLATE_ADD_DOC = """
            With this method, you can add a value directly to the current target value and react to the event in one step. For larger workflows, use `#[html_fn()]` to implement a custom system.
            """;
    private static final String TEMPLATE_MIN_DOC = """
            With this method, you can subtract a value directly from the current target value and react to the event in one step. For larger workflows, use `#[html_fn()]` to implement a custom system.
            """;
    private static final String TEMPLATE_EVENT_DOC = """
            This is used to directly access the event within the `$set` method. This is necessary to retrieve the element's value or other values.
            """;
    private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
            "if", "else", "for", "use", "as", "match", "let", "crate", "self", "super", "true", "false"
    );

    private static final class ObjectAccessContext {
        private final String objectName;
        private final String fieldName;
        private final boolean hoveringObject;
        private final boolean hoveringField;

        private ObjectAccessContext(String objectName, String fieldName, boolean hoveringObject, boolean hoveringField) {
            this.objectName = objectName;
            this.fieldName = fieldName;
            this.hoveringObject = hoveringObject;
            this.hoveringField = hoveringField;
        }
    }

    @Override
    public String generateDoc(PsiElement element, PsiElement originalElement) {
        String mergedDocumentation = buildMergedExtraDoc(element, originalElement);
        return (mergedDocumentation == null || mergedDocumentation.isBlank()) ? null : mergedDocumentation;
    }

    @Override
    public String generateHoverDoc(PsiElement element, PsiElement originalElement) {
        String mergedDocumentation = buildMergedExtraDoc(element, originalElement);
        return (mergedDocumentation == null || mergedDocumentation.isBlank()) ? null : mergedDocumentation;
    }

    @Override
    public PsiElement getCustomDocumentationElement(Editor editor, PsiFile file, PsiElement contextElement, int targetOffset) {
        if (file == null || !file.getName().toLowerCase(Locale.ROOT).endsWith(".html")) {
            return null;
        }
        if (targetOffset < 0 || targetOffset >= file.getTextLength()) {
            return null;
        }

        PsiElement target = file.findElementAt(targetOffset);
        if (target == null) {
            return null;
        }
        markOffsetOnAncestors(target, file, targetOffset);

        if (PsiTreeUtil.getParentOfType(target, XmlAttributeValue.class, false) != null) {
            String fileText = file.getText();
            return (objectAccessContextAt(fileText, targetOffset) != null || templateHelperAt(fileText, targetOffset) != null)
                    ? target
                    : null;
        }

        XmlAttribute attribute = findAttribute(target);
        if (attribute != null) {
            XmlTag tag = attribute.getParent();
            String tagName = tag == null ? null : tag.getName();
            if (BeuHtmlEvents.isSupportedForTag(attribute.getName(), tagName)
                    || BeuHtmlWidgets.isSupportedAttribute(attribute.getName(), tagName)) {
                return target;
            }
            return null;
        }

        return objectAccessContextAt(file.getText(), targetOffset) != null ? target : null;
    }

    private static String buildMergedExtraDoc(PsiElement element, PsiElement originalElement) {
        List<String> sections = new ArrayList<>();
        String beuDocumentationSection = buildBeuSection(element);
        if (beuDocumentationSection == null && originalElement != null) {
            beuDocumentationSection = buildBeuSection(originalElement);
        }
        if (beuDocumentationSection != null) {
            sections.add(beuDocumentationSection);
        }

        String rustDocumentationSection = null;
        if (originalElement != null) {
            rustDocumentationSection = buildRustSection(originalElement);
        }
        if (rustDocumentationSection == null && element != null) {
            rustDocumentationSection = buildRustSection(element);
        }
        if (rustDocumentationSection != null) {
            sections.add(rustDocumentationSection);
        }

        if (sections.isEmpty()) {
            return null;
        }
        return String.join("<hr/>", sections);
    }

    private static String buildBeuSection(PsiElement element) {
        XmlAttribute attribute = findAttribute(element);
        if (attribute == null) {
            return null;
        }

        PsiFile file = element.getContainingFile();
        if (file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".html")
                && PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false) != null) {
            Integer markedOffset = element.getUserData(HOVER_OFFSET_KEY);
            int offset = markedOffset != null ? markedOffset : element.getTextRange().getStartOffset();
            String text = file.getText();
            String helper = templateHelperAt(text, offset);
            if (helper != null) {
                return "<p><b>beu:</b></p>" + renderMarkdown(templateHelperDoc(helper));
            }
            if (objectAccessContextAt(text, offset) != null) {
                return null;
            }
        }

        XmlTag tag = attribute.getParent();
        String tagName = tag == null ? null : tag.getName();

        BeuHtmlEvents.EventDefinition definition = BeuHtmlEvents.resolve(attribute.getName());
        if (definition != null) {
            if (definition.formOnly() && (tagName == null || !"form".equalsIgnoreCase(tagName))) {
                return null;
            }

            String description = definition.description();
            if (!definition.aliases().isEmpty()) {
                description = description + " Aliases: " + String.join(", ", definition.aliases()) + ".";
            }
            return "<p><b>beu:</b></p><ul><li>" + description + "</li></ul>";
        }

        BeuHtmlWidgets.AttributeDefinition widgetAttribute = BeuHtmlWidgets.resolveAttribute(attribute.getName(), tagName);
        if (widgetAttribute == null) {
            return null;
        }
        return "<p><b>beu:</b></p><ul><li>" + widgetAttribute.description() + "</li></ul>";
    }

    private static String buildRustSection(PsiElement element) {
        if (element == null || element.getTextRange() == null) {
            return null;
        }
        if (findAttribute(element) != null && PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false) == null) {
            return null;
        }

        PsiFile file = element.getContainingFile();
        if (file == null) {
            return null;
        }
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".html")) {
            return null;
        }

        String text = file.getText();
        if (text == null || text.isEmpty()) {
            return null;
        }

        Integer markedOffset = element.getUserData(HOVER_OFFSET_KEY);
        int offset = markedOffset != null ? markedOffset : element.getTextRange().getStartOffset();
        if (templateHelperAt(text, offset) != null) {
            return null;
        }
        ObjectAccessContext context = objectAccessContextAt(text, offset);
        if (context == null) {
            return null;
        }

        RustStructFieldIndex index = RustStructFieldIndex.get(file.getProject());
        String fromUse = BeuHtmlUseResolver.resolveStructNameFromUse(file, context.objectName);
        String preferredStructName = fromUse != null ? fromUse : BeuHtmlUseResolver.resolveStructName(file, context.objectName);
        String structName = index.resolveStructNameForObject(context.objectName, preferredStructName, false);
        if (structName == null) {
            structName = index.resolveStructNameForObject(context.objectName, preferredStructName, true);
        }
        if (structName == null) {
            return null;
        }

        if (context.hoveringField && context.fieldName != null) {
            String variantDoc = index.enumVariantDocForEnumAndVariant(structName, context.fieldName);
            if (variantDoc != null && !variantDoc.isBlank()) {
                return "<p><b>rust:</b></p>" + renderMarkdown(variantDoc);
            }

            String fieldDoc = index.fieldDocForStructAndField(structName, context.fieldName);
            if (fieldDoc != null && !fieldDoc.isBlank()) {
                return "<p><b>rust:</b></p>" + renderMarkdown(fieldDoc);
            }

            String methodDoc = index.methodDocForStructAndMethod(structName, context.fieldName);
            if (methodDoc != null && !methodDoc.isBlank()) {
                return "<p><b>rust:</b></p>" + renderMarkdown(methodDoc);
            }
        }

        if (context.hoveringObject) {
            String typeDoc = index.typeDocForName(structName);
            if (typeDoc != null && !typeDoc.isBlank()) {
                return "<p><b>rust:</b></p>" + renderMarkdown(typeDoc);
            }
        }

        return null;
    }

    @Override
    public String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        XmlAttribute attribute = findAttribute(element);
        if (attribute == null) {
            return null;
        }

        XmlTag tag = attribute.getParent();
        String tagName = tag == null ? null : tag.getName();
        BeuHtmlEvents.EventDefinition definition = BeuHtmlEvents.resolve(attribute.getName());
        if (definition != null) {
            return definition.name() + ": " + definition.description();
        }

        BeuHtmlWidgets.AttributeDefinition widgetAttribute = BeuHtmlWidgets.resolveAttribute(attribute.getName(), tagName);
        if (widgetAttribute != null) {
            return widgetAttribute.name() + ": " + widgetAttribute.description();
        }
        return null;
    }

    private static XmlAttribute findAttribute(PsiElement element) {
        if (element instanceof XmlAttribute xmlAttribute) {
            return xmlAttribute;
        }
        return PsiTreeUtil.getParentOfType(element, XmlAttribute.class, false);
    }

    private static String escapeHtml(String rawText) {
        return rawText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String renderMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder html = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        List<String> listItems = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.stripTrailing();
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                flushParagraph(html, paragraph);
                flushList(html, listItems);
                continue;
            }

            int headingLevel = headingLevel(trimmed);
            if (headingLevel > 0) {
                flushParagraph(html, paragraph);
                flushList(html, listItems);
                String content = trimmed.substring(Math.min(headingLevel + 1, trimmed.length())).trim();
                html.append("<h").append(headingLevel).append(">")
                        .append(renderInlineMarkdown(content))
                        .append("</h").append(headingLevel).append(">");
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph(html, paragraph);
                listItems.add(trimmed.substring(2).trim());
                continue;
            }

            if (!paragraph.isEmpty()) {
                paragraph.append('\n');
            }
            paragraph.append(trimmed);
        }

        flushParagraph(html, paragraph);
        flushList(html, listItems);
        return html.toString();
    }

    private static int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6) {
            return 0;
        }
        if (level < line.length() && !Character.isWhitespace(line.charAt(level))) {
            return 0;
        }
        return level;
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.isEmpty()) {
            return;
        }
        html.append("<p>").append(renderInlineMarkdown(paragraph.toString())).append("</p>");
        paragraph.setLength(0);
    }

    private static void flushList(StringBuilder html, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        html.append("<ul>");
        for (String item : items) {
            html.append("<li>").append(renderInlineMarkdown(item)).append("</li>");
        }
        html.append("</ul>");
        items.clear();
    }

    private static String renderInlineMarkdown(String text) {
        String escaped = escapeHtml(text);
        if (escaped.isEmpty()) {
            return escaped;
        }

        StringBuilder result = new StringBuilder();
        boolean inCode = false;
        for (int i = 0; i < escaped.length(); i++) {
            char ch = escaped.charAt(i);
            if (ch == '`') {
                if (inCode) {
                    result.append("</code>");
                } else {
                    result.append("<code>");
                }
                inCode = !inCode;
                continue;
            }
            result.append(ch);
        }
        if (inCode) {
            result.append("</code>");
        }

        return result.toString().replace("\n", "<br/>");
    }

    private static ObjectAccessContext objectAccessContextAt(String text, int rawOffset) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        int offset = Math.min(Math.max(rawOffset, 0), text.length() - 1);
        if (!isIdentifierChar(text.charAt(offset))) {
            if (offset > 0 && isIdentifierChar(text.charAt(offset - 1))) {
                offset--;
            } else {
                return null;
            }
        }

        int tokenStart = offset;
        while (tokenStart > 0 && isIdentifierChar(text.charAt(tokenStart - 1))) {
            tokenStart--;
        }

        int tokenEnd = offset + 1;
        while (tokenEnd < text.length() && isIdentifierChar(text.charAt(tokenEnd))) {
            tokenEnd++;
        }

        String token = text.substring(tokenStart, tokenEnd);
        int left = skipWhitespaceLeft(text, tokenStart - 1);
        int right = skipWhitespaceRight(text, tokenEnd);

        boolean hasLeftDot = left >= 0 && text.charAt(left) == '.';
        boolean hasRightDot = right < text.length() && text.charAt(right) == '.';
        boolean hasLeftDoubleColon = hasDoubleColonToLeft(text, tokenStart);
        boolean hasRightDoubleColon = hasDoubleColonToRight(text, tokenEnd);

        if (hasLeftDot) {
            int objectEnd = skipWhitespaceLeft(text, left - 1);
            if (objectEnd < 0 || !isIdentifierChar(text.charAt(objectEnd))) {
                return null;
            }

            int objectStart = objectEnd;
            while (objectStart > 0 && isIdentifierChar(text.charAt(objectStart - 1))) {
                objectStart--;
            }

            String objectName = text.substring(objectStart, objectEnd + 1);
            return new ObjectAccessContext(objectName, token, false, true);
        }

        if (hasRightDot) {
            int fieldStart = skipWhitespaceRight(text, right + 1);
            if (fieldStart >= text.length() || !isIdentifierChar(text.charAt(fieldStart))) {
                return new ObjectAccessContext(token, null, true, false);
            }

            int fieldEnd = fieldStart + 1;
            while (fieldEnd < text.length() && isIdentifierChar(text.charAt(fieldEnd))) {
                fieldEnd++;
            }

            String fieldName = text.substring(fieldStart, fieldEnd);
            return new ObjectAccessContext(token, fieldName, true, false);
        }

        if (hasLeftDoubleColon) {
            String enumName = typeNameLeftOfDoubleColon(text, tokenStart);
            if (enumName == null || enumName.isBlank()) {
                return null;
            }
            return new ObjectAccessContext(enumName, token, false, true);
        }

        if (hasRightDoubleColon) {
            return new ObjectAccessContext(token, null, true, false);
        }

        if (RESERVED_IDENTIFIERS.contains(token)) {
            return null;
        }
        return new ObjectAccessContext(token, null, true, false);
    }

    private static int skipWhitespaceLeft(String text, int start) {
        int index = start;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        return index;
    }

    private static int skipWhitespaceRight(String text, int start) {
        int index = Math.max(0, start);
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean hasDoubleColonToLeft(String text, int tokenStart) {
        int firstColon = skipWhitespaceLeft(text, tokenStart - 1);
        if (firstColon < 0 || text.charAt(firstColon) != ':') {
            return false;
        }
        int secondColon = skipWhitespaceLeft(text, firstColon - 1);
        return secondColon >= 0 && text.charAt(secondColon) == ':';
    }

    private static boolean hasDoubleColonToRight(String text, int tokenEnd) {
        int firstColon = skipWhitespaceRight(text, tokenEnd);
        if (firstColon >= text.length() || text.charAt(firstColon) != ':') {
            return false;
        }
        int secondColon = skipWhitespaceRight(text, firstColon + 1);
        return secondColon < text.length() && text.charAt(secondColon) == ':';
    }

    private static String typeNameLeftOfDoubleColon(String text, int tokenStart) {
        int rightColon = skipWhitespaceLeft(text, tokenStart - 1);
        if (rightColon < 0 || text.charAt(rightColon) != ':') {
            return null;
        }
        int leftColon = skipWhitespaceLeft(text, rightColon - 1);
        if (leftColon < 0 || text.charAt(leftColon) != ':') {
            return null;
        }

        int typeEnd = skipWhitespaceLeft(text, leftColon - 1);
        if (typeEnd < 0 || !isIdentifierChar(text.charAt(typeEnd))) {
            return null;
        }
        int typeStart = typeEnd;
        while (typeStart > 0 && isIdentifierChar(text.charAt(typeStart - 1))) {
            typeStart--;
        }
        return text.substring(typeStart, typeEnd + 1);
    }

    private static boolean isIdentifierChar(char ch) {
        return ch == '_' || Character.isLetterOrDigit(ch);
    }

    private static String templateHelperAt(String text, int rawOffset) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        int offset = Math.min(Math.max(rawOffset, 0), text.length() - 1);
        if (!isTemplateHelperChar(text.charAt(offset))) {
            if (offset > 0 && isTemplateHelperChar(text.charAt(offset - 1))) {
                offset--;
            } else {
                return null;
            }
        }

        int tokenStart = offset;
        while (tokenStart > 0 && isTemplateHelperChar(text.charAt(tokenStart - 1))) {
            tokenStart--;
        }

        int tokenEnd = offset + 1;
        while (tokenEnd < text.length() && isTemplateHelperChar(text.charAt(tokenEnd))) {
            tokenEnd++;
        }

        String token = text.substring(tokenStart, tokenEnd);
        return switch (token) {
            case "$set", "$add", "$min", "$event" -> token;
            default -> null;
        };
    }

    private static String templateHelperDoc(String helperToken) {
        if ("$set".equals(helperToken)) {
            return TEMPLATE_SET_DOC;
        }
        if ("$add".equals(helperToken)) {
            return TEMPLATE_ADD_DOC;
        }
        if ("$min".equals(helperToken)) {
            return TEMPLATE_MIN_DOC;
        }
        if ("$event".equals(helperToken)) {
            return TEMPLATE_EVENT_DOC;
        }
        return "";
    }

    private static boolean isTemplateHelperChar(char ch) {
        return ch == '$' || ch == '_' || Character.isLetterOrDigit(ch);
    }

    private static void markOffsetOnAncestors(PsiElement leafElement, PsiFile file, int targetOffset) {
        PsiElement currentElement = leafElement;
        while (currentElement != null && currentElement != file) {
            currentElement.putUserData(HOVER_OFFSET_KEY, targetOffset);
            currentElement = currentElement.getParent();
        }
    }
}
