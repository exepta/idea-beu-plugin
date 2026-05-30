package com.tiltus.beu.plugin.html;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BeuHtmlEvents {
    static final class EventDefinition {
        private final String name;
        private final String description;
        private final List<String> aliases;
        private final boolean formOnly;

        EventDefinition(String name, String description, List<String> aliases, boolean formOnly) {
            this.name = name;
            this.description = description;
            this.aliases = aliases;
            this.formOnly = formOnly;
        }

        String name() {
            return name;
        }

        String description() {
            return description;
        }

        List<String> aliases() {
            return aliases;
        }

        boolean formOnly() {
            return formOnly;
        }
    }

    private static final Map<String, EventDefinition> CANONICAL_BY_NAME = new LinkedHashMap<>();
    private static final Map<String, EventDefinition> RESOLVED_BY_NAME = new LinkedHashMap<>();

    static {
        register("onclick", "Triggered when the element is clicked.");
        register("onmousedown", "Triggered when a mouse button is pressed down.");
        register("onmouseup", "Triggered when a mouse button is released.");
        register("onchange", "Triggered when an input value changes.");
        register("action", "Submit handler name for <form>.", true);
        register("oninit", "Triggered when the element is initialized.");
        register("onmouseover", "Triggered when the pointer enters the element.");
        register("onmouseout", "Triggered when the pointer leaves the element.");
        register("onfocus", "Triggered when the element receives focus.", "onfoucs");
        register("onscroll", "Triggered on scroll updates.");
        register("onwheel", "Triggered on wheel / mouse-wheel interaction.", "onmousewheel");
        register("onkeydown", "Triggered when a key is pressed down.");
        register("onkeyup", "Triggered when a key is released.");
        register("ondragstart", "Triggered at the start of a drag operation.");
        register("ondrag", "Triggered while dragging.");
        register("ondragstop", "Triggered when a drag operation ends.", "ondragend");
        register("ontouchstart", "Triggered when touch interaction starts.");
        register("ontouchmove", "Triggered while touch moves.");
        register("ontouchend", "Triggered when touch interaction ends.");
        register("checked", "Boolean attribute indicating an enabled checked state.");
        register("disabled", "Boolean attribute that disables user interaction.");
        register("hidden", "Boolean attribute that hides the element.");
    }

    private static void register(String name, String description, String... aliases) {
        register(name, description, false, aliases);
    }

    private static void register(String name, String description, boolean formOnly, String... aliases) {
        String normalizedName = normalize(name);
        List<String> aliasList = new ArrayList<>(aliases.length);
        for (String alias : aliases) {
            aliasList.add(normalize(alias));
        }
        EventDefinition definition = new EventDefinition(normalizedName, description, List.copyOf(aliasList), formOnly);
        CANONICAL_BY_NAME.put(normalizedName, definition);
        RESOLVED_BY_NAME.put(normalizedName, definition);
        for (String alias : aliasList) {
            RESOLVED_BY_NAME.put(alias, definition);
        }
    }

    static EventDefinition resolve(String maybeName) {
        if (maybeName == null) {
            return null;
        }
        return RESOLVED_BY_NAME.get(normalize(maybeName));
    }

    static Collection<EventDefinition> allForTag(String tagName) {
        if ("form".equalsIgnoreCase(tagName)) {
            return CANONICAL_BY_NAME.values();
        }
        List<EventDefinition> results = new ArrayList<>();
        for (EventDefinition definition : CANONICAL_BY_NAME.values()) {
            if (!definition.formOnly()) {
                results.add(definition);
            }
        }
        return results;
    }

    static boolean isSupportedForTag(String attributeName, String tagName) {
        EventDefinition definition = resolve(attributeName);
        return definition != null && (!definition.formOnly() || "form".equalsIgnoreCase(tagName));
    }

    public static boolean isFunctionHandlerAttribute(String attributeName) {
        EventDefinition definition = resolve(attributeName);
        if (definition == null) {
            return false;
        }
        String normalized = normalize(attributeName);
        return normalized.startsWith("on") || "action".equals(normalized);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private BeuHtmlEvents() {
    }
}
