// Ported from aether (https://github.com/mizly/aether), GPL-3.0-or-later.
// Modified for fish5; see com/fish5/PORTED.md.
package com.fish5.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// pure classification of a disconnect reason: no client state, no minecraft classes
public final class HypixelBanDetector {
    // parsed punishment text only; the ban id is never extracted or kept
    public record DetectedBan(boolean temporary, String reason, String duration) {
        public DetectedBan {
            reason = reason == null ? "" : reason;
            duration = duration == null ? "" : duration;
        }
    }

    private static final Pattern FORMATTING_CODE = Pattern.compile("(?i)\\u00A7[0-9A-FK-OR]");
    private static final Pattern EXOTIC_SPACE = Pattern.compile("[\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]");
    private static final Pattern TEMPORARY_FALLBACK = Pattern.compile("(?i)\\bbanned\\b.*\\bfor\\b.*\\bfrom this server\\b");
    private static final Pattern DURATION_BOUNDED = Pattern.compile("(?i)banned for\\s+(.+?)\\s+from this server");
    private static final Pattern DURATION_OPEN = Pattern.compile("(?i)banned for\\s+(.+?)\\s*$");
    private static final Pattern REASON_LINE = Pattern.compile("(?i)^reason\\s*:\\s*(.*)$");
    private static final Pattern BAN_ID_LABEL = Pattern.compile("(?i)\\bban\\s*id\\s*:");
    private static final Pattern MARKER_LINE = Pattern.compile("(?i)^(reason|find out more|ban id)\\s*:");

    private HypixelBanDetector() {
    }

    // null unless both ban wording and a hypixel punishment marker are present, so kicks and timeouts fall through
    public static DetectedBan detect(String rawReason) {
        List<String> lines = normalizeLines(rawReason);
        if (lines.isEmpty()) {
            return null;
        }

        String lower = String.join("\n", lines).toLowerCase(Locale.ROOT);
        Boolean temporary = classifyBanKind(lower);
        if (temporary == null || !hasHypixelPunishmentMarker(lower)) {
            return null;
        }

        return new DetectedBan(temporary, parseReason(lines), temporary ? parseDuration(lines) : "");
    }

    // strips legacy colour codes and exotic spacing, then yields trimmed non-empty lines
    static List<String> normalizeLines(String rawReason) {
        List<String> lines = new ArrayList<>();
        if (rawReason == null || rawReason.isBlank()) {
            return lines;
        }

        String stripped = FORMATTING_CODE.matcher(rawReason).replaceAll("");
        stripped = EXOTIC_SPACE.matcher(stripped).replaceAll(" ");
        for (String line : stripped.split("\\R")) {
            String collapsed = line.replaceAll("\\s+", " ").trim();
            if (!collapsed.isEmpty()) {
                lines.add(collapsed);
            }
        }
        return lines;
    }

    // true = temporary, false = permanent, null = not ban-shaped
    private static Boolean classifyBanKind(String lower) {
        if (lower.contains("temporarily banned") || TEMPORARY_FALLBACK.matcher(lower).find()) {
            return Boolean.TRUE;
        }
        if (lower.contains("permanently banned") || lower.contains("banned permanently")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean hasHypixelPunishmentMarker(String lower) {
        return lower.contains("hypixel.net/appeal") || BAN_ID_LABEL.matcher(lower).find();
    }

    private static String parseDuration(List<String> lines) {
        for (String line : lines) {
            Matcher bounded = DURATION_BOUNDED.matcher(line);
            if (bounded.find()) {
                return cleanDuration(bounded.group(1));
            }
        }
        for (String line : lines) {
            Matcher open = DURATION_OPEN.matcher(line);
            if (open.find()) {
                return cleanDuration(open.group(1));
            }
        }
        return "";
    }

    private static String cleanDuration(String raw) {
        return raw.replaceAll("[!.\\s]+$", "").trim();
    }

    private static String parseReason(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = REASON_LINE.matcher(lines.get(i));
            if (!matcher.matches()) {
                continue;
            }
            String inline = matcher.group(1).trim();
            if (!inline.isEmpty()) {
                return inline;
            }
            // wrapped component: the reason text landed on the following line
            if (i + 1 < lines.size() && !MARKER_LINE.matcher(lines.get(i + 1)).find()) {
                return lines.get(i + 1).trim();
            }
            return "";
        }
        return "";
    }
}
