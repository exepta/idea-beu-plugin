package com.tiltus.beu.plugin.format;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

final class BeuDirectiveFormatter {
    private static final Pattern DIRECTIVE_OPEN_PATTERN = Pattern.compile("^@(?:if|for)\\b.*\\{\\s*$");
    private static final Pattern ELSE_OPEN_PATTERN = Pattern.compile("^@else\\b.*\\{\\s*$");
    private static final Pattern ELSE_TRANSITION_PATTERN = Pattern.compile("^}\\s*@else\\b.*\\{\\s*$");

    static boolean containsDirectives(String text) {
        return text.contains("@if") || text.contains("@for") || text.contains("@else");
    }

    static String reindentDirectiveBlocks(String text) {
        String[] textLines = text.split("\n", -1);
        StringBuilder result = new StringBuilder(text.length() + 16);
        Deque<Integer> blockBaseIndents = new ArrayDeque<>();

        for (int i = 0; i < textLines.length; i++) {
            String line = textLines[i];
            String trimmedLeft = trimLeading(line);
            String trimmed = trimmedLeft.trim();
            String rewritten = line;

            if (!trimmed.isEmpty() && !blockBaseIndents.isEmpty()) {
                int expectedIndent = startsWithClosingBrace(trimmed) ? blockBaseIndents.peek() : blockBaseIndents.peek() + 4;
                rewritten = " ".repeat(Math.max(0, expectedIndent)) + trimmed;
            }

            if (i > 0) {
                result.append('\n');
            }
            result.append(rewritten);

            if (trimmed.isEmpty()) {
                continue;
            }

            int currentIndent = countLeadingSpaces(rewritten);
            if (isElseTransition(trimmed)) {
                continue;
            }

            if (startsWithClosingBrace(trimmed) && !blockBaseIndents.isEmpty()) {
                blockBaseIndents.pop();
            }

            if (isDirectiveOpen(trimmed) || isElseOpen(trimmed)) {
                blockBaseIndents.push(currentIndent);
            }
        }

        return result.toString();
    }

    static int computeInnerIndentBeforeOffset(Document document, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, document.getTextLength()));
        int caretLine = document.getLineNumber(safeOffset);
        Deque<Integer> blockBaseIndents = new ArrayDeque<>();

        for (int line = 0; line <= caretLine; line++) {
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = (line == caretLine) ? safeOffset : document.getLineEndOffset(line);
            if (lineEnd < lineStart) {
                continue;
            }

            String rawLine = document.getText(TextRange.create(lineStart, lineEnd));
            String trimmed = trimLeading(rawLine).trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            int currentIndent = countLeadingSpaces(rawLine);
            if (isElseTransition(trimmed)) {
                continue;
            }

            if (startsWithClosingBrace(trimmed) && !blockBaseIndents.isEmpty()) {
                blockBaseIndents.pop();
            }

            if (isDirectiveOpen(trimmed) || isElseOpen(trimmed)) {
                blockBaseIndents.push(currentIndent);
            }
        }

        return blockBaseIndents.isEmpty() ? -1 : blockBaseIndents.peek() + 4;
    }

    static String applyBaseIndent(String text, int baseIndent) {
        if (baseIndent <= 0 || text.isEmpty()) {
            return text;
        }

        String prefix = " ".repeat(baseIndent);
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder(text.length() + (lines.length * baseIndent));
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            String line = lines[i];
            if (line.isBlank()) {
                result.append(line);
            } else {
                result.append(prefix).append(trimLeading(line));
            }
        }
        return result.toString();
    }

    static int currentLineIndent(Document document, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, document.getTextLength()));
        int line = document.getLineNumber(safeOffset);
        int lineStart = document.getLineStartOffset(line);
        String linePrefix = document.getText(TextRange.create(lineStart, safeOffset));
        return countLeadingSpaces(linePrefix);
    }

    private static String trimLeading(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return line.substring(index);
    }

    private static int countLeadingSpaces(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private static boolean startsWithClosingBrace(String trimmedLine) {
        return trimmedLine.startsWith("}");
    }

    private static boolean isDirectiveOpen(String trimmedLine) {
        return DIRECTIVE_OPEN_PATTERN.matcher(trimmedLine).matches();
    }

    private static boolean isElseOpen(String trimmedLine) {
        return ELSE_OPEN_PATTERN.matcher(trimmedLine).matches();
    }

    private static boolean isElseTransition(String trimmedLine) {
        return ELSE_TRANSITION_PATTERN.matcher(trimmedLine).matches();
    }

    private BeuDirectiveFormatter() {
    }
}
