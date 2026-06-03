package com.tiltus.beu.plugin.html;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;

final class BeuHtmlHighlighting {
    static final TextAttributesKey DIRECTIVE_KEYWORD = TextAttributesKey.createTextAttributesKey(
            "BEU_DIRECTIVE_KEYWORD",
            DefaultLanguageHighlighterColors.KEYWORD
    );

    static final TextAttributesKey OBJECT_REFERENCE = TextAttributesKey.createTextAttributesKey(
            "BEU_OBJECT_REFERENCE",
            DefaultLanguageHighlighterColors.LOCAL_VARIABLE
    );

    static final TextAttributesKey OBJECT_ATTRIBUTE = TextAttributesKey.createTextAttributesKey(
            "BEU_OBJECT_ATTRIBUTE",
            DefaultLanguageHighlighterColors.INSTANCE_FIELD
    );

    static final TextAttributesKey EVENT_ATTRIBUTE = TextAttributesKey.createTextAttributesKey(
            "BEU_EVENT_ATTRIBUTE",
            DefaultLanguageHighlighterColors.METADATA
    );

    static final TextAttributesKey USE_STRING = TextAttributesKey.createTextAttributesKey(
            "BEU_USE_STRING",
            DefaultLanguageHighlighterColors.STRING
    );

    static final TextAttributesKey USE_PUNCTUATION = TextAttributesKey.createTextAttributesKey(
            "BEU_USE_PUNCTUATION",
            DefaultLanguageHighlighterColors.SEMICOLON
    );

    static final TextAttributesKey ENUM_VARIANT = TextAttributesKey.createTextAttributesKey(
            "BEU_ENUM_VARIANT",
            DefaultLanguageHighlighterColors.CONSTANT
    );

    private BeuHtmlHighlighting() {
    }
}
