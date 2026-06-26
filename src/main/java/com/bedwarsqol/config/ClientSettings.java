package com.bedwarsqol.config;

import org.lwjgl.input.Keyboard;

public class ClientSettings {

    public int defaultTextSize = 1;
    /** Global Text/Image style for every HUD element that supports it. 0 = text, 1 = icons + numbers. */
    public int hudDisplayMode = 1;
    /** Font for HUD text. 0 = modern (bundled Inter atlas), 1 = vanilla Minecraft font. */
    public int hudFont = 0;
    /** Size of the settings GUI panel. 0 = small, 1 = medium, 2 = large. */
    public int guiSize = 2;
    /** Selected module-card colour theme (index into SettingsGui.THEMES; 0 = default grayscale, no change). */
    public int moduleTheme = 0;

    public boolean potionStatusEnabled = true;
    /** Only render this HUD while in an active BedWars game (off = render everywhere). */
    public boolean potionInGameOnly = false;
    public int potionHudX = 5;
    public int potionHudY = 5;
    public int potionHudAnchor = 0;
    public float potionHudScale = 1.0f;
    /** Draw a modern translucent panel behind this HUD element. */
    public boolean potionBackgroundEnabled = false;

    public boolean armorTypeEnabled = true;
    public boolean armorInGameOnly = false;
    public int armorHudX = 5;
    public int armorHudY = 34;
    public int armorHudAnchor = 0;
    public float armorHudScale = 1.0f;
    public boolean armorBackgroundEnabled = false;

    public boolean infoEnabled = false;
    public int infoHudX = 5;
    public int infoHudY = 58;
    public int infoHudAnchor = 0;
    public float infoHudScale = 1.0f;
    public boolean infoBackgroundEnabled = false;

    // --- BedWars HUDs (only render in an active BedWars game) ---

    public boolean inventoryHudEnabled = false;
    public boolean inventoryInGameOnly = false;
    public int inventoryHudX = 5;
    public int inventoryHudY = 5;
    public int inventoryHudAnchor = 6; // bottom-left by default
    public float inventoryHudScale = 1.0f;
    public boolean inventoryBackgroundEnabled = false;

    // One toggle controls both gen timers; each stays independently draggable below.
    public boolean genTimersEnabled = false;
    /** One shared toggle: draws a matching panel behind BOTH the diamond and emerald timer boxes. */
    public boolean genTimersBackgroundEnabled = false;
    public int diamondTimerHudX = 5;
    public int diamondTimerHudY = 5;
    public int diamondTimerHudAnchor = 2; // top-right by default
    public float diamondTimerHudScale = 1.0f;

    public int emeraldTimerHudX = 5;
    public int emeraldTimerHudY = 27;
    public int emeraldTimerHudAnchor = 2;
    public float emeraldTimerHudScale = 1.0f;

    public boolean keystrokesEnabled = false;
    public boolean keystrokesInGameOnly = false;
    public int keystrokesHudX = -10;
    public int keystrokesHudY = -20;
    public int keystrokesHudAnchor = 8; // bottom-right by default
    public float keystrokesHudScale = 1.0f;
    public boolean keystrokesBackgroundEnabled = false;

    public boolean playerStats = false;
    /** Nametag/tab stat overlays no longer have toggles — forced on with Player Stats (see sanitize). */
    public boolean playerStatsNametag = true;
    public boolean playerStatsTab = true;
    public boolean playerStatsShowLevel = true;
    public boolean playerStatsShowRank = true;
    /** Hovering a player's name in chat appends their BedWars stats to the hover card (lobby/queue/game). */
    public boolean playerStatsChatHover = true;
    /** When in an active Bedwars game, broadcast the sweatiest enemy teams to party chat once. */
    public boolean statsSweatReport = false;

    /** Auto GG: say "gg" in chat once each time a BedWars game ends. */
    public boolean autoGg = false;

    // Stats come from a Cloudflare Worker that each user self-hosts (see server/stats-worker). No
    // public backend is shipped — never commit a real URL or token here. Users set their own via
    // /bedwarsqol statsurl <url> and (optionally) /bedwarsqol statstoken <token>.
    /** No default backend: empty until the user points the mod at their own self-hosted Worker. */
    public static final String DEFAULT_STATS_BACKEND_URL = "";
    /** Base URL of the user's stats Worker. Empty = stats disabled until set. */
    public String statsBackendUrl = DEFAULT_STATS_BACKEND_URL;
    /** No default token. */
    public static final String DEFAULT_STATS_BACKEND_TOKEN = "";
    /** Optional secret sent as the {@code X-BedwarsQol-Token} header, matching the Worker's STATS_TOKEN
     *  secret. Empty = send no token (open backend). */
    public String statsBackendToken = DEFAULT_STATS_BACKEND_TOKEN;

    public int settingsKeyCode = Keyboard.KEY_RSHIFT;

    /**
     * Suppress the hardcoded Esc -> pause-menu open while in-world, so an accidental tap in combat no
     * longer opens the menu (or fumbles onward into Options/Language). Esc still closes open screens;
     * the menu can be reopened via the "Open Game Menu" KeyBinding in Minecraft's Controls menu.
     */
    public boolean suppressEscMenu = false;

    // --- Visual / gameplay tweaks ---

    /** Custom highlight on the block you're looking at. */
    public boolean blockOverlayEnabled = false;
    public int blockOverlayColor = 0x804A90E2;  // ARGB
    public int blockOverlayStyle = 2;           // 0 = outline, 1 = fill, 2 = both
    public boolean blockOverlaySeeThrough = false;
    public float blockOverlayLineWidth = 2.0f;

    /** Center-screen countdown for nearby primed TNT. */
    public boolean tntFuseEnabled = false;
    public int tntFuseRadius = 10;

    /** First-person held-item position offset (X/Y/Z, eye space) + size scale, gated by the master toggle. */
    public boolean handPositionEnabled = false;
    public float handPosX = 0f;
    public float handPosY = 0f;
    public float handPosZ = 0f;
    public float handScale = 1.0f;

    /** Vanilla scoreboard sidebar size. 0 = small, 1 = medium, 2 = large (the original full size). */
    public int scoreboardSize = 2;

    /** Vanilla tab player list size. 0 = small, 1 = medium, 2 = large (the original full size). */
    public int styledTabListSize = 2;
    /** Hide the server-sent header/footer text above and below the tab player list. */
    public boolean tabHideHeaderFooter = false;
    /** Show each player's latency as a number ("123ms") in the tab list instead of the vanilla signal-bar icon. */
    public boolean tabNumericPing = true;

    // --- Debug ---

    /** Spawn clientside "Test Dummy" practice players with a keybind (real hittable entities in singleplayer). */
    public boolean dummyEnabled = false;
    /** Key that spawns a dummy at the block you're looking at (default unbound). */
    public int dummySpawnKeyCode = Keyboard.KEY_NONE;

    public void sanitize() {
        defaultTextSize = clamp(defaultTextSize, 0, 2);
        hudDisplayMode = clamp(hudDisplayMode, 0, 1);
        hudFont = clamp(hudFont, 0, 1);
        guiSize = clamp(guiSize, 0, 2);
        if (moduleTheme < 0) moduleTheme = 0; // upper bound clamped GUI-side against THEMES.length
        potionHudAnchor = clamp(potionHudAnchor, 0, 8);
        armorHudAnchor = clamp(armorHudAnchor, 0, 8);
        if (potionHudScale < 0.3f || potionHudScale > 10.0f) potionHudScale = defaultTextSizeScale();
        if (armorHudScale < 0.3f || armorHudScale > 10.0f) armorHudScale = defaultTextSizeScale();
        infoHudAnchor = clamp(infoHudAnchor, 0, 8);
        if (infoHudScale < 0.3f || infoHudScale > 10.0f) infoHudScale = defaultTextSizeScale();

        inventoryHudAnchor = clamp(inventoryHudAnchor, 0, 8);
        if (inventoryHudScale < 0.3f || inventoryHudScale > 10.0f) inventoryHudScale = defaultTextSizeScale();
        diamondTimerHudAnchor = clamp(diamondTimerHudAnchor, 0, 8);
        if (diamondTimerHudScale < 0.3f || diamondTimerHudScale > 10.0f) diamondTimerHudScale = defaultTextSizeScale();
        emeraldTimerHudAnchor = clamp(emeraldTimerHudAnchor, 0, 8);
        if (emeraldTimerHudScale < 0.3f || emeraldTimerHudScale > 10.0f) emeraldTimerHudScale = defaultTextSizeScale();
        keystrokesHudAnchor = clamp(keystrokesHudAnchor, 0, 8);
        if (keystrokesHudScale < 0.3f || keystrokesHudScale > 10.0f) keystrokesHudScale = defaultTextSizeScale();
        scoreboardSize = clamp(scoreboardSize, 0, 2);
        styledTabListSize = clamp(styledTabListSize, 0, 2);

        blockOverlayStyle = clamp(blockOverlayStyle, 0, 2);
        if (blockOverlayLineWidth < 0.5f || blockOverlayLineWidth > 10.0f) blockOverlayLineWidth = 2.0f;
        tntFuseRadius = clamp(tntFuseRadius, 1, 64);
        handPosX = clampf(handPosX, -1.0f, 1.0f);
        handPosY = clampf(handPosY, -1.0f, 1.0f);
        handPosZ = clampf(handPosZ, -1.0f, 1.0f);
        handScale = clampf(handScale, 0.5f, 2.0f);

        if (statsBackendUrl == null) statsBackendUrl = "";
        statsBackendUrl = statsBackendUrl.trim();
        if (statsBackendToken == null) statsBackendToken = "";
        statsBackendToken = statsBackendToken.trim();
        if (settingsKeyCode < 0) settingsKeyCode = Keyboard.KEY_RSHIFT;
        if (dummySpawnKeyCode < 0) dummySpawnKeyCode = Keyboard.KEY_NONE;
    }

    public float defaultTextSizeScale() {
        switch (defaultTextSize) {
            case 0: return 0.75f;
            case 2: return 1.5f;
            default: return 1.0f;
        }
    }

    public void applyDefaultTextSize() {
        float scale = defaultTextSizeScale();
        potionHudScale = scale;
        armorHudScale = scale;
        infoHudScale = scale;
        inventoryHudScale = scale;
        diamondTimerHudScale = scale;
        emeraldTimerHudScale = scale;
        keystrokesHudScale = scale;
    }

    public void save() {
        sanitize();
        SettingsManager.save(this);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private static float clampf(float value, float min, float max) {
        if (value < min) return min;
        return Math.min(value, max);
    }
}
