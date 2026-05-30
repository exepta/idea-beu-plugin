package com.tiltus.beu.plugin.format;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class BeuDirectiveEnterHandler extends EnterHandlerDelegateAdapter {
    private static final Key<Integer> INDENT_SIZE = Key.create("beu.directive.enter.indent.size");

    @Override
    public @NotNull EnterHandlerDelegate.Result preprocessEnter(
            @NotNull PsiFile file,
            @NotNull Editor editor,
            @NotNull Ref<Integer> caretOffset,
            @NotNull Ref<Integer> caretAdvance,
            @NotNull DataContext dataContext,
            EditorActionHandler originalHandler
    ) {
        editor.putUserData(INDENT_SIZE, computeIndentAfterEnter(file, editor, caretOffset.get()));
        return EnterHandlerDelegate.Result.Continue;
    }

    @Override
    public @NotNull EnterHandlerDelegate.Result postProcessEnter(@NotNull PsiFile file, @NotNull Editor editor, @NotNull DataContext dataContext) {
        Integer indentSize = editor.getUserData(INDENT_SIZE);
        editor.putUserData(INDENT_SIZE, null);
        if (indentSize == null || indentSize < 0) {
            return EnterHandlerDelegate.Result.Continue;
        }

        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();
        int line = document.getLineNumber(offset);
        int lineStart = document.getLineStartOffset(line);
        String currentPrefix = document.getText(TextRange.create(lineStart, offset));
        if (!currentPrefix.isBlank()) {
            return EnterHandlerDelegate.Result.Continue;
        }

        String indent = " ".repeat(indentSize);
        document.replaceString(lineStart, offset, indent);
        editor.getCaretModel().moveToOffset(lineStart + indentSize);
        return EnterHandlerDelegate.Result.Stop;
    }

    private static Integer computeIndentAfterEnter(PsiFile file, Editor editor, int offset) {
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".html")) {
            return -1;
        }

        Document document = editor.getDocument();
        return BeuDirectiveFormatter.computeInnerIndentBeforeOffset(document, offset);
    }
}
