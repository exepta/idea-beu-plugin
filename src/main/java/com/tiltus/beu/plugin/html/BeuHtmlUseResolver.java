package com.tiltus.beu.plugin.html;

import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BeuHtmlUseResolver {
    private static final Pattern USE_PATTERN = Pattern.compile(
            "@use\\s+\"([^\"]+)\"\\s+as\\s+([A-Za-z_][\\w]*)\\s*;?"
    );

    @Nullable
    public static String resolveStructName(@NotNull PsiFile htmlFile, @NotNull String objectName) {
        return resolveStructName(htmlFile.getText(), objectName);
    }

    @Nullable
    public static String resolveStructName(@Nullable String htmlText, @NotNull String objectName) {
        if (objectName.isBlank()) {
            return null;
        }
        Map<String, String> bindings = parseBindings(htmlText);
        String resolved = bindings.get(objectName.toLowerCase(Locale.ROOT));
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return toPascalCase(objectName);
    }

    @Nullable
    public static String resolveStructNameFromUse(@NotNull PsiFile htmlFile, @NotNull String objectName) {
        return resolveStructNameFromUse(htmlFile.getText(), objectName);
    }

    @Nullable
    public static String resolveStructNameFromUse(@Nullable String htmlText, @NotNull String objectName) {
        if (objectName.isBlank()) {
            return null;
        }
        Map<String, String> bindings = parseBindings(htmlText);
        return bindings.get(objectName.toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> parseBindings(String htmlText) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (htmlText == null || htmlText.isEmpty() || !htmlText.contains("@use")) {
            return bindings;
        }

        Matcher matcher = USE_PATTERN.matcher(htmlText);
        while (matcher.find()) {
            String structName = matcher.group(1);
            String alias = matcher.group(2);
            if (structName == null || structName.isBlank() || alias == null || alias.isBlank()) {
                continue;
            }
            String normalizedStructName = normalizeTypeName(structName);
            if (normalizedStructName != null && !normalizedStructName.isBlank()) {
                bindings.put(alias.toLowerCase(Locale.ROOT), normalizedStructName);
            }
        }
        return bindings;
    }

    private static String toPascalCase(String value) {
        String[] parts = value.split("_+");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            result.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                result.append(part.substring(1));
            }
        }
        return result.isEmpty() ? value : result.toString();
    }

    private static String normalizeTypeName(String rawType) {
        if (rawType == null) {
            return null;
        }
        String value = rawType.trim();
        if (value.isEmpty()) {
            return null;
        }

        int genericStart = value.indexOf('<');
        if (genericStart >= 0) {
            value = value.substring(0, genericStart).trim();
        }

        int pathStart = value.lastIndexOf("::");
        if (pathStart >= 0 && pathStart + 2 < value.length()) {
            value = value.substring(pathStart + 2).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private BeuHtmlUseResolver() {
    }
}
