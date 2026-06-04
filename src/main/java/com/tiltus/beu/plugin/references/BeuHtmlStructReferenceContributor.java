package com.tiltus.beu.plugin.references;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlText;
import com.intellij.util.ProcessingContext;
import com.tiltus.beu.plugin.html.BeuHtmlEvents;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BeuHtmlStructReferenceContributor extends PsiReferenceContributor {
    private enum ReferenceKind {
        STRUCT,
        FIELD,
        ENUM_VARIANT
    }

    private static final class ReferenceCandidate {
        private final int start;
        private final int end;
        private final ReferenceKind kind;
        private final String objectName;
        private final String explicitTypeName;
        private final String fieldName;
        private final String enumName;
        private final String variantName;

        private ReferenceCandidate(
                int start,
                int end,
                ReferenceKind kind,
                String objectName,
                String explicitTypeName,
                String fieldName,
                String enumName,
                String variantName
        ) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.objectName = objectName;
            this.explicitTypeName = explicitTypeName;
            this.fieldName = fieldName;
            this.enumName = enumName;
            this.variantName = variantName;
        }
    }

    private static final class UseSegment {
        private final int startOffset;
        private final int endOffset;

        private UseSegment(int startOffset, int endOffset) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    private static final Pattern USE_PATTERN = Pattern.compile(
            "@use\\s+\"(?<path>[^\"]+)\"(?:\\s+as\\s+(?<alias>[A-Za-z_][\\w]*))?\\s*;?"
    );
    private static final Pattern OBJECT_ACCESS_PATTERN = Pattern.compile(
            "\\b(?<object>[A-Za-z_][\\w]*)\\b\\s*\\.\\s*(?<member>[A-Za-z_][\\w]*)"
    );
    private static final Pattern TYPE_PATH_PATTERN = Pattern.compile(
            "\\b(?<type>[A-Z][\\w]*)\\b\\s*::\\s*(?<member>[A-Za-z_][\\w]*)"
    );
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "\\b(?<name>[a-z_][\\w]*)\\b"
    );
    private static final Set<String> RESERVED_OBJECT_KEYWORDS = Set.of(
            "if", "else", "for", "use", "as", "match", "let", "crate", "self", "super", "true", "false"
    );

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        PsiReferenceProvider provider = new PsiReferenceProvider() {
            @Override
            public PsiReference @NotNull [] getReferencesByElement(@NotNull com.intellij.psi.PsiElement element, @NotNull ProcessingContext context) {
                if (!isHtmlFile(element)) {
                    return PsiReference.EMPTY_ARRAY;
                }

                XmlAttributeValue attributeValue = element instanceof XmlAttributeValue
                        ? (XmlAttributeValue) element
                        : PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false);
                if (attributeValue != null) {
                    return referencesFromAttributeValue(element, attributeValue);
                }
                if (PsiTreeUtil.getParentOfType(element, XmlAttribute.class, false) != null) {
                    return PsiReference.EMPTY_ARRAY;
                }

                XmlText xmlText = element instanceof XmlText
                        ? (XmlText) element
                        : PsiTreeUtil.getParentOfType(element, XmlText.class, false);
                if (xmlText == null) {
                    return PsiReference.EMPTY_ARRAY;
                }

                String text = xmlText.getText();
                if (text == null || text.isBlank()) {
                    return PsiReference.EMPTY_ARRAY;
                }

                int windowStartInText;
                int windowEndInText;
                int relativeShift;
                if (element == xmlText) {
                    windowStartInText = 0;
                    windowEndInText = text.length();
                    relativeShift = 0;
                } else {
                    if (element.getTextRange() == null || xmlText.getTextRange() == null) {
                        return PsiReference.EMPTY_ARRAY;
                    }
                    windowStartInText = element.getTextRange().getStartOffset() - xmlText.getTextRange().getStartOffset();
                    windowEndInText = windowStartInText + element.getTextLength();
                    if (windowStartInText < 0 || windowEndInText > text.length() || windowStartInText >= windowEndInText) {
                        return PsiReference.EMPTY_ARRAY;
                    }
                    relativeShift = -windowStartInText;
                }

                List<ReferenceCandidate> candidates = new ArrayList<>();
                collectUseStructReferences(text, candidates);
                collectObjectAndMemberReferences(text, candidates);
                collectTypePathReferences(text, candidates);
                collectStandaloneObjectReferences(text, candidates);
                return buildReferences(element, candidates, windowStartInText, windowEndInText, relativeShift);
            }
        };

        registrar.registerReferenceProvider(PlatformPatterns.psiElement(), provider);
    }

    private static PsiReference @NotNull [] referencesFromAttributeValue(PsiElement element, XmlAttributeValue attributeValue) {
        String text = attributeValue.getValue();
        if (text == null || text.isBlank()) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (!shouldResolveInsideAttributeValue(attributeValue, text)) {
            return PsiReference.EMPTY_ARRAY;
        }
        if (attributeValue.getTextRange() == null) {
            return PsiReference.EMPTY_ARRAY;
        }

        TextRange valueRange = attributeValue.getValueTextRange();
        int windowStartInText;
        int windowEndInText;
        int relativeShift;
        if (element == attributeValue) {
            windowStartInText = 0;
            windowEndInText = text.length();
            relativeShift = valueRange.getStartOffset() - attributeValue.getTextRange().getStartOffset();
        } else {
            if (element.getTextRange() == null) {
                return PsiReference.EMPTY_ARRAY;
            }
            windowStartInText = element.getTextRange().getStartOffset() - valueRange.getStartOffset();
            windowEndInText = windowStartInText + element.getTextLength();
            if (windowStartInText < 0 || windowEndInText > text.length() || windowStartInText >= windowEndInText) {
                return PsiReference.EMPTY_ARRAY;
            }
            relativeShift = -windowStartInText;
        }

        List<ReferenceCandidate> candidates = new ArrayList<>();
        collectObjectAndMemberReferences(text, candidates);
        collectTypePathReferences(text, candidates);
        collectStandaloneObjectReferences(text, candidates);
        return buildReferences(element, candidates, windowStartInText, windowEndInText, relativeShift);
    }

    private static boolean shouldResolveInsideAttributeValue(XmlAttributeValue attributeValue, String text) {
        XmlAttribute attribute = attributeValue.getParent() instanceof XmlAttribute xmlAttribute ? xmlAttribute : null;
        String attributeName = attribute == null ? null : attribute.getName();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("{{") || lower.contains("$set(") || lower.contains("$event.")) {
            return true;
        }
        if (attributeName != null && BeuHtmlEvents.isFunctionHandlerAttribute(attributeName)) {
            return lower.contains("::") || lower.contains(".");
        }
        return false;
    }

    private static PsiReference @NotNull [] buildReferences(
            PsiElement element,
            List<ReferenceCandidate> candidates,
            int windowStartInText,
            int windowEndInText,
            int relativeShift
    ) {
        List<PsiReference> references = new ArrayList<>();
        for (ReferenceCandidate candidate : candidates) {
            if (candidate.start < windowStartInText || candidate.end > windowEndInText) {
                continue;
            }
            int relativeStart = candidate.start + relativeShift;
            int relativeEnd = candidate.end + relativeShift;
            if (relativeStart < 0 || relativeEnd <= relativeStart || relativeEnd > element.getTextLength()) {
                continue;
            }
            TextRange rangeInElement = TextRange.create(relativeStart, relativeEnd);
            references.add(buildReference(element, rangeInElement, candidate));
        }
        return references.toArray(PsiReference.EMPTY_ARRAY);
    }

    private static PsiReference buildReference(PsiElement element, TextRange rangeInElement, ReferenceCandidate candidate) {
        return switch (candidate.kind) {
            case STRUCT -> new BeuHtmlStructReference(element, rangeInElement, candidate.objectName, candidate.explicitTypeName);
            case FIELD -> new BeuHtmlFieldReference(element, rangeInElement, candidate.objectName, candidate.fieldName);
            case ENUM_VARIANT -> new BeuHtmlEnumVariantReference(element, rangeInElement, candidate.enumName, candidate.variantName);
        };
    }

    private static void collectUseStructReferences(String text, List<ReferenceCandidate> references) {
        Matcher matcher = USE_PATTERN.matcher(text);
        while (matcher.find()) {
            String path = matcher.group("path");
            TypePathInfo info = normalizeTypePath(path);
            if (info == null || info.typeName.isBlank()) {
                continue;
            }

            int pathStart = matcher.start("path");
            int typeStart = pathStart + info.typeOffset;
            int typeEnd = typeStart + info.typeName.length();
            references.add(new ReferenceCandidate(
                    typeStart,
                    typeEnd,
                    ReferenceKind.STRUCT,
                    null,
                    info.typeName,
                    null,
                    null,
                    null
            ));

            String alias = matcher.group("alias");
            if (alias != null && !alias.isBlank()) {
                int aliasStart = matcher.start("alias");
                int aliasEnd = matcher.end("alias");
                references.add(new ReferenceCandidate(
                        aliasStart,
                        aliasEnd,
                        ReferenceKind.STRUCT,
                        alias,
                        null,
                        null,
                        null,
                        null
                ));
            }

            for (UseSegment segment : usePathPrefixSegments(path, info.typeOffset)) {
                int segmentStart = pathStart + segment.startOffset;
                int segmentEnd = pathStart + segment.endOffset;
                references.add(new ReferenceCandidate(
                        segmentStart,
                        segmentEnd,
                        ReferenceKind.STRUCT,
                        null,
                        info.typeName,
                        null,
                        null,
                        null
                ));
            }
        }
    }

    private static List<UseSegment> usePathPrefixSegments(String rawPath, int typeOffset) {
        if (rawPath == null || rawPath.isBlank() || typeOffset <= 0) {
            return List.of();
        }

        int scanEnd = Math.max(0, Math.min(typeOffset, rawPath.length()));
        String prefix = rawPath.substring(0, scanEnd);
        List<UseSegment> segments = new ArrayList<>();
        int index = 0;
        while (index < prefix.length()) {
            while (index < prefix.length() && Character.isWhitespace(prefix.charAt(index))) {
                index++;
            }
            if (index >= prefix.length()) {
                break;
            }

            int segmentStart = index;
            while (index < prefix.length()) {
                char ch = prefix.charAt(index);
                if (ch == ':' || Character.isWhitespace(ch)) {
                    break;
                }
                index++;
            }
            int segmentEnd = index;
            if (segmentEnd > segmentStart) {
                segments.add(new UseSegment(segmentStart, segmentEnd));
            }

            while (index < prefix.length() && Character.isWhitespace(prefix.charAt(index))) {
                index++;
            }
            if (index + 1 < prefix.length() && prefix.charAt(index) == ':' && prefix.charAt(index + 1) == ':') {
                index += 2;
            } else if (index < prefix.length() && prefix.charAt(index) == ':') {
                index++;
            }
        }
        return segments;
    }

    private static void collectObjectAndMemberReferences(String text, List<ReferenceCandidate> references) {
        Matcher matcher = OBJECT_ACCESS_PATTERN.matcher(text);
        while (matcher.find()) {
            String objectName = matcher.group("object");
            String memberName = matcher.group("member");
            if (objectName == null || objectName.isBlank()) {
                continue;
            }
            int objectStart = matcher.start("object");
            if (objectStart > 0 && text.charAt(objectStart - 1) == '$') {
                continue;
            }

            references.add(new ReferenceCandidate(
                    objectStart,
                    matcher.end("object"),
                    ReferenceKind.STRUCT,
                    objectName,
                    null,
                    null,
                    null,
                    null
            ));

            if (memberName == null || memberName.isBlank()) {
                continue;
            }
            references.add(new ReferenceCandidate(
                    matcher.start("member"),
                    matcher.end("member"),
                    ReferenceKind.FIELD,
                    objectName,
                    null,
                    memberName,
                    null,
                    null
            ));
        }
    }

    private static void collectTypePathReferences(String text, List<ReferenceCandidate> references) {
        Matcher matcher = TYPE_PATH_PATTERN.matcher(text);
        while (matcher.find()) {
            String typeName = matcher.group("type");
            String memberName = matcher.group("member");
            if (typeName == null || typeName.isBlank()) {
                continue;
            }
            references.add(new ReferenceCandidate(
                    matcher.start("type"),
                    matcher.end("type"),
                    ReferenceKind.STRUCT,
                    null,
                    typeName,
                    null,
                    null,
                    null
            ));
            if (memberName == null || memberName.isBlank()) {
                continue;
            }
            references.add(new ReferenceCandidate(
                    matcher.start("member"),
                    matcher.end("member"),
                    ReferenceKind.ENUM_VARIANT,
                    null,
                    null,
                    null,
                    typeName,
                    memberName
            ));
        }
    }

    private static void collectStandaloneObjectReferences(String text, List<ReferenceCandidate> references) {
        Matcher matcher = IDENTIFIER_PATTERN.matcher(text);
        while (matcher.find()) {
            String objectName = matcher.group("name");
            if (objectName == null || objectName.isBlank()) {
                continue;
            }
            if (RESERVED_OBJECT_KEYWORDS.contains(objectName)) {
                continue;
            }

            int start = matcher.start("name");
            int end = matcher.end("name");
            if (isInsideDoubleQuotedString(text, start)) {
                continue;
            }
            if (touchesReferenceRange(references, start, end)) {
                continue;
            }
            if (isPartOfQualifiedPath(text, start, end)) {
                continue;
            }
            if (start > 0 && text.charAt(start - 1) == '@') {
                continue;
            }
            if (start > 0 && text.charAt(start - 1) == '$') {
                continue;
            }

            references.add(new ReferenceCandidate(
                    start,
                    end,
                    ReferenceKind.STRUCT,
                    objectName,
                    null,
                    null,
                    null,
                    null
            ));
        }
    }

    private static boolean touchesReferenceRange(List<ReferenceCandidate> references, int start, int end) {
        for (ReferenceCandidate candidate : references) {
            if (start < candidate.end && end > candidate.start) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPartOfQualifiedPath(String text, int start, int end) {
        int left = skipWhitespaceLeft(text, start - 1);
        int right = skipWhitespaceRight(text, end);
        if (left >= 0 && (text.charAt(left) == '.' || text.charAt(left) == ':')) {
            return true;
        }
        return right < text.length() && (text.charAt(right) == '.' || text.charAt(right) == ':');
    }

    private static boolean isInsideDoubleQuotedString(String text, int offset) {
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < text.length() && i < offset; i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            }
        }
        return inString;
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

    private static boolean isHtmlFile(PsiElement element) {
        if (element.getContainingFile() == null) {
            return false;
        }
        String fileName = element.getContainingFile().getName();
        return fileName.toLowerCase(Locale.ROOT).endsWith(".html");
    }

    private static TypePathInfo normalizeTypePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0) {
            trimmed = trimmed.substring(0, genericStart).trim();
        }
        if (trimmed.isEmpty()) {
            return null;
        }

        int separator = trimmed.lastIndexOf("::");
        String typeName = separator >= 0 ? trimmed.substring(separator + 2).trim() : trimmed;
        if (typeName.isEmpty()) {
            return null;
        }

        int typeOffset = rawPath.lastIndexOf(typeName);
        if (typeOffset < 0) {
            typeOffset = 0;
        }
        return new TypePathInfo(typeName, typeOffset);
    }

    private record TypePathInfo(String typeName, int typeOffset) {
    }
}
