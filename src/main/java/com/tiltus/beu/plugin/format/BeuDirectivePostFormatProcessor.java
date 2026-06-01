package com.tiltus.beu.plugin.format;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class BeuDirectivePostFormatProcessor implements PostFormatProcessor {
    @Override
    public @NotNull PsiElement processElement(@NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
        return source;
    }

    @Override
    public boolean isWhitespaceOnly() {
        return false;
    }

    @Override
    public @NotNull TextRange processText(@NotNull PsiFile source, @NotNull TextRange rangeToReformat, @NotNull CodeStyleSettings settings) {
        if (!source.getName().toLowerCase(Locale.ROOT).endsWith(".html")) {
            return rangeToReformat;
        }

        Document document = PsiDocumentManager.getInstance(source.getProject()).getDocument(source);
        if (document == null) {
            return rangeToReformat;
        }

        String originalText = document.getText();
        if (!BeuDirectiveFormatter.containsDirectives(originalText)) {
            return rangeToReformat;
        }

        String formattedText = BeuDirectiveFormatter.reindentDirectiveBlocks(originalText);
        if (originalText.equals(formattedText)) {
            return rangeToReformat;
        }

        document.setText(formattedText);
        PsiDocumentManager.getInstance(source.getProject()).commitDocument(document);
        return new TextRange(0, formattedText.length());
    }
}
