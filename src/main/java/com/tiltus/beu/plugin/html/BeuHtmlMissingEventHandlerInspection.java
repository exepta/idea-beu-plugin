package com.tiltus.beu.plugin.html;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.tiltus.beu.plugin.index.RustBeuIndex;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BeuHtmlMissingEventHandlerInspection extends LocalInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new XmlElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue value) {
                PsiFile file = value.getContainingFile();
                if (file == null || !file.getName().toLowerCase(Locale.ROOT).endsWith(".html")) {
                    return;
                }
                if (!(value.getParent() instanceof XmlAttribute attribute)) {
                    return;
                }
                if (!BeuHtmlEvents.isFunctionHandlerAttribute(attribute.getName())) {
                    return;
                }

                String functionName = value.getValue();
                if (!isPlainFunctionName(functionName)) {
                    return;
                }

                if (!RustBeuIndex.get(file.getProject()).htmlFunctionTargets(functionName).isEmpty()) {
                    return;
                }

                String eventType = BeuHtmlEvents.rustEventTypeForAttribute(attribute.getName());
                if (eventType == null || eventType.isBlank()) {
                    return;
                }

                holder.registerProblem(
                        value,
                        "Missing BeU event handler '" + functionName + "'.",
                        new GenerateEventQuickFix(functionName, eventType)
                );
            }
        };
    }

    private static boolean isPlainFunctionName(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (!Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(0) != '_') {
            return false;
        }
        for (int i = 1; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch != '_' && !Character.isLetterOrDigit(ch)) {
                return false;
            }
        }
        return true;
    }

    private static final class GenerateEventQuickFix implements LocalQuickFix {
        private static final Pattern HTML_FN_PATTERN = Pattern.compile(
                "(?ms)#\\[html_fn\\(\"(?<name>[A-Za-z_][\\w]*)\"\\)\\]\\s*(?:pub\\s+)?(?:async\\s+)?fn\\s+(?<fn>[A-Za-z_][\\w]*)"
        );
        private final String handlerName;
        private final String eventType;

        private GenerateEventQuickFix(String handlerName, String eventType) {
            this.handlerName = handlerName;
            this.eventType = eventType;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Generate event";
        }

        @Override
        public @NotNull String getName() {
            return "Generate event";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            PsiFile htmlFile = element == null ? null : element.getContainingFile();
            if (htmlFile == null || htmlFile.getVirtualFile() == null || htmlFile.getVirtualFile().getParent() == null) {
                return;
            }

            String htmlFileName = htmlFile.getVirtualFile().getName();
            if (!htmlFileName.toLowerCase(Locale.ROOT).endsWith(".html")) {
                return;
            }
            String rsFileName = htmlFileName.substring(0, htmlFileName.length() - ".html".length()) + ".rs";

            var rsVirtualFile = htmlFile.getVirtualFile().getParent().findChild(rsFileName);
            if (rsVirtualFile == null) {
                return;
            }

            PsiFile rsPsiFile = PsiManager.getInstance(project).findFile(rsVirtualFile);
            if (rsPsiFile == null) {
                return;
            }

            Document document = PsiDocumentManager.getInstance(project).getDocument(rsPsiFile);
            if (document == null) {
                return;
            }

            String existing = document.getText();
            String marker = "#[html_fn(\"" + handlerName + "\")]";
            if (existing.contains(marker)) {
                int existingOffset = findFunctionNameOffset(existing, handlerName);
                if (existingOffset >= 0) {
                    new OpenFileDescriptor(project, rsVirtualFile, existingOffset).navigate(true);
                }
                return;
            }

            String functionName = toRustIdentifier(handlerName);
            String snippet = "\n#[html_fn(\"" + handlerName + "\")]\n"
                    + "pub fn " + functionName + "(In(_): In<" + eventType + ">) {\n"
                    + "    \n"
                    + "}\n";
            int functionNameOffsetInSnippet = snippet.indexOf("pub fn " + functionName);
            if (functionNameOffsetInSnippet >= 0) {
                functionNameOffsetInSnippet += "pub fn ".length();
            } else {
                functionNameOffsetInSnippet = 0;
            }
            final int functionNameOffsetInSnippetFinal = functionNameOffsetInSnippet;
            AtomicInteger targetOffset = new AtomicInteger(-1);

            WriteCommandAction.runWriteCommandAction(project, () -> {
                int insertOffset = document.getTextLength();
                if (insertOffset > 0 && !document.getText().endsWith("\n")) {
                    document.insertString(insertOffset, "\n");
                    insertOffset = document.getTextLength();
                }
                document.insertString(insertOffset, snippet);
                targetOffset.set(insertOffset + functionNameOffsetInSnippetFinal);
                PsiDocumentManager.getInstance(project).commitDocument(document);
            });

            if (targetOffset.get() >= 0) {
                new OpenFileDescriptor(project, rsVirtualFile, targetOffset.get()).navigate(true);
            }
        }

        private static String toRustIdentifier(String raw) {
            if (raw == null || raw.isBlank()) {
                return "_generated_event";
            }
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char ch = raw.charAt(i);
                if (ch == '_' || Character.isLetterOrDigit(ch)) {
                    result.append(ch);
                } else {
                    result.append('_');
                }
            }
            if (result.isEmpty()) {
                return "_generated_event";
            }
            if (Character.isDigit(result.charAt(0))) {
                result.insert(0, '_');
            }
            return result.toString();
        }

        private static int findFunctionNameOffset(String text, String htmlFnName) {
            if (text == null || text.isBlank() || htmlFnName == null || htmlFnName.isBlank()) {
                return -1;
            }
            Matcher matcher = HTML_FN_PATTERN.matcher(text);
            while (matcher.find()) {
                String name = matcher.group("name");
                if (name == null || !name.equals(htmlFnName)) {
                    continue;
                }
                return matcher.start("fn");
            }
            return -1;
        }
    }
}
