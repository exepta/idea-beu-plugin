package com.tiltus.beu.plugin.html;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BeuHtmlWidgets {
    static final class AttributeDefinition {
        private final String name;
        private final String description;

        AttributeDefinition(String name, String description) {
            this.name = name;
            this.description = description;
        }

        String name() {
            return name;
        }

        String description() {
            return description;
        }
    }

    private static final class WidgetDefinition {
        private final String tag;
        private final Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();

        private WidgetDefinition(String tag) {
            this.tag = tag;
        }

        void addAttribute(AttributeDefinition definition) {
            attributes.put(definition.name(), definition);
        }

        Collection<AttributeDefinition> attributes() {
            return attributes.values();
        }

        AttributeDefinition attribute(String name) {
            return attributes.get(name);
        }
    }

    private static final Map<String, WidgetDefinition> WIDGETS = new LinkedHashMap<>();
    private static final Set<String> TAGS = new LinkedHashSet<>();

    static {
        register("body");
        register("div");
        register("form",
                attr("action", "Submit handler name for the form."),
                attr("validate", "Validation mode: always, send, or interact.")
        );
        register("button", attr("type", "Button type: button, submit, or reset."));
        register("checkbox",
                attr("icon", "Icon path rendered next to the checkbox label."),
                attr("checked", "Boolean checked state."),
                attr("disabled", "Boolean disabled state."),
                attr("hidden", "Boolean hidden state.")
        );
        register("select");
        register("option",
                attr("value", "Option value submitted by the select."),
                attr("selected", "Marks this option as selected.")
        );
        register("divider", attr("alignment", "Divider orientation: horizontal or vertical."));
        register("fieldset",
                attr("mode", "Selection mode: single or multi."),
                attr("allow-none", "Whether no selected option is allowed.")
        );
        register("h1");
        register("h2");
        register("h3");
        register("h4");
        register("h5");
        register("h6");
        register("img",
                attr("src", "Image source path."),
                attr("alt", "Alternative text."),
                attr("preview", "Input id that drives live image preview.")
        );
        register("input",
                attr("id", "Element id."),
                attr("name", "Input name in form submit payload."),
                attr("label", "Input label text."),
                attr("placeholder", "Placeholder text."),
                attr("icon", "Optional icon path."),
                attr("type", "Input type: text, email, date, password, number, file."),
                attr("maxlength", "Maximum text length."),
                attr("folder", "File input: allow folder selection."),
                attr("extensions", "File input: allowed extensions list."),
                attr("show-size", "File input: show selected file size."),
                attr("required", "Marks the input as required."),
                attr("value", "Current input value.")
        );
        register("date-picker",
                attr("id", "Element id."),
                attr("name", "Input name in form submit payload."),
                attr("label", "Date picker label text."),
                attr("placeholder", "Placeholder text."),
                attr("value", "Selected date value in YYYY-MM-DD."),
                attr("min", "Minimum selectable date in YYYY-MM-DD."),
                attr("max", "Maximum selectable date in YYYY-MM-DD."),
                attr("format", "Display format: mdy, dmy, or ymd."),
                attr("for", "Target input id used by this date picker.")
        );
        register("p");
        register("tool-tip",
                attr("for", "Target widget id for tooltip binding."),
                attr("variant", "Tooltip variant: point or follow."),
                attr("prio", "Preferred side: top, bottom, left, or right."),
                attr("alignment", "Alignment mode: vertical or horizontal."),
                attr("trigger", "Activation mode: hover, click, or drag.")
        );
        register("progressbar",
                attr("min", "Minimum progress value."),
                attr("max", "Maximum progress value."),
                attr("value", "Current progress value.")
        );
        register("radio",
                attr("value", "Selected value for the radio option."),
                attr("selected", "Marks this radio option as selected.")
        );
        register("scroll",
                attr("alignment", "Scroll orientation: vertical or horizontal."),
                attr("min", "Minimum scroll value."),
                attr("max", "Maximum scroll value."),
                attr("step", "Scroll step size."),
                attr("value", "Current scroll value.")
        );
        register("slider",
                attr("min", "Minimum slider value."),
                attr("max", "Maximum slider value."),
                attr("value", "Current slider value."),
                attr("step", "Slider step size.")
        );
        register("colorpicker",
                attr("value", "Initial color value (#hex, rgb(), or rgba())."),
                attr("alpha", "Initial alpha value."),
                attr("onchange", "Handler called when color changes.")
        );
        register("switch",
                attr("icon", "Optional icon path."),
                attr("checked", "Boolean checked state."),
                attr("disabled", "Boolean disabled state."),
                attr("hidden", "Boolean hidden state.")
        );
        register("toggle",
                attr("value", "Toggle value used in form data."),
                attr("selected", "Boolean selected state."),
                attr("icon", "Optional icon path.")
        );
        register("label", attr("for", "Target input id."));
        register("icon", attr("src", "Icon source path."));
    }

    private static void register(String tag, AttributeDefinition... attributes) {
        String normalizedTag = normalize(tag);
        WidgetDefinition definition = new WidgetDefinition(normalizedTag);
        for (AttributeDefinition attribute : attributes) {
            definition.addAttribute(attribute);
        }
        WIDGETS.put(normalizedTag, definition);
        TAGS.add(normalizedTag);
    }

    private static AttributeDefinition attr(String attributeName, String description) {
        return new AttributeDefinition(normalize(attributeName), description);
    }

    static boolean isKnownTag(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return false;
        }
        return TAGS.contains(normalize(tagName));
    }

    static List<String> allTagNames() {
        return List.copyOf(TAGS);
    }

    static boolean isSupportedAttribute(String attributeName, String tagName) {
        return resolveAttribute(attributeName, tagName) != null;
    }

    static List<AttributeDefinition> attributesForTag(String tagName) {
        if (tagName == null || tagName.isBlank()) {
            return List.of();
        }
        WidgetDefinition definition = WIDGETS.get(normalize(tagName));
        if (definition == null) {
            return List.of();
        }
        return new ArrayList<>(definition.attributes());
    }

    static AttributeDefinition resolveAttribute(String attributeName, String tagName) {
        if (attributeName == null || attributeName.isBlank() || tagName == null || tagName.isBlank()) {
            return null;
        }
        WidgetDefinition definition = WIDGETS.get(normalize(tagName));
        if (definition == null) {
            return null;
        }
        return definition.attribute(normalize(attributeName));
    }

    private static String normalize(String input) {
        return input.toLowerCase(Locale.ROOT).trim();
    }

    private BeuHtmlWidgets() {
    }
}
