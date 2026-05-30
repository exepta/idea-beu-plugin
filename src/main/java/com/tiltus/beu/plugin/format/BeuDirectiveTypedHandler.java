package com.tiltus.beu.plugin.format;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlText;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class BeuDirectiveTypedHandler extends TypedHandlerDelegate {
    @Override
    public @NotNull Result checkAutoPopup(char charTyped, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (charTyped != '@' && charTyped != '.') {
            return Result.CONTINUE;
        }
        if (!isHtmlFile(file)) {
            return Result.CONTINUE;
        }

        int offset = Math.max(0, editor.getCaretModel().getOffset() - 1);
        PsiElement element = file.findElementAt(offset);
        if (!isExpressionContext(element)) {
            return Result.CONTINUE;
        }

        AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
        return Result.STOP;
    }

    private static boolean isHtmlFile(PsiFile file) {
        return file.getName().toLowerCase(Locale.ROOT).endsWith(".html");
    }

    private static boolean isExpressionContext(PsiElement element) {
        if (element == null) {
            return false;
        }
        if (PsiTreeUtil.getParentOfType(element, XmlAttribute.class, false) != null) {
            return false;
        }
        if (PsiTreeUtil.getParentOfType(element, XmlAttributeValue.class, false) != null) {
            return false;
        }
        return PsiTreeUtil.getParentOfType(element, XmlText.class, false) != null;
    }
}
