package com.tiltus.beu.plugin.format;

import com.intellij.codeInsight.editorActions.CopyPastePreProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RawText;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

import java.util.Locale;

public final class BeuDirectiveCopyPastePreProcessor implements CopyPastePreProcessor {
    @Override
    public String preprocessOnCopy(PsiFile file, int[] startOffsets, int[] endOffsets, String text) {
        return text;
    }

    @Override
    public String preprocessOnPaste(Project project, PsiFile file, Editor editor, String text, RawText rawText) {
        if (text == null || file == null || editor == null) {
            return text;
        }
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".html")) {
            return text;
        }
        if (!BeuDirectiveFormatter.containsDirectives(text)) {
            return text;
        }

        String normalized = BeuDirectiveFormatter.reindentDirectiveBlocks(text);
        int caretOffset = editor.getCaretModel().getOffset();
        int indent = BeuDirectiveFormatter.computeInnerIndentBeforeOffset(editor.getDocument(), caretOffset);
        if (indent < 0) {
            indent = BeuDirectiveFormatter.currentLineIndent(editor.getDocument(), caretOffset);
        }

        return BeuDirectiveFormatter.applyBaseIndent(normalized, Math.max(0, indent));
    }
}
