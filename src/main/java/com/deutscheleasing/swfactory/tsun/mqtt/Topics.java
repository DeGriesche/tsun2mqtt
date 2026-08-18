package com.deutscheleasing.swfactory.tsun.mqtt;

import java.util.Locale;

/** Topic naming rules. Everything that ends up in a topic goes through {@link #slug(String)}. */
public final class Topics {

    private Topics() {
    }

    /** Lowercases and replaces anything that is not a safe topic character with an underscore. */
    public static String slug(String value) {
        var cleaned = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    /** {@code <base>/<kind>/<id>/state} */
    public static String state(String baseTopic, String kind, String id) {
        return baseTopic + "/" + slug(kind) + "/" + slug(id) + "/state";
    }

    /** {@code <base>/<kind>/<id>/raw} for the untouched API payload. */
    public static String raw(String baseTopic, String kind, String id) {
        return baseTopic + "/" + slug(kind) + "/" + slug(id) + "/raw";
    }
}
