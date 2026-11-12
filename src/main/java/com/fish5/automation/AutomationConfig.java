package com.fish5.automation;

/** Timing tunables for menu automation (auto forge). Plain static holder. */
public final class AutomationConfig {

    /** Base delay between menu clicks, plus up to this much random jitter. */
    public static long guiClickDelayMs = 350;
    public static long guiClickJitterMs = 150;

    private AutomationConfig() {}
}
