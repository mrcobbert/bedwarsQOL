package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.feature.dummy.TestDummyHandler;
import com.bedwarsqol.gui.render.BedwarsQolFont;
import com.bedwarsqol.gui.render.GuiBlur;
import com.bedwarsqol.gui.render.GuiRender;
import com.bedwarsqol.gui.render.Icons;
import com.bedwarsqol.gui.render.Theme;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom-rendered settings screen — a premium, minimal, grayscale panel. Left sidebar navigation
 * (one section visible at a time) with an "Edit HUD" action pinned to its bottom; the right pane
 * shows that section's controls as a clean list of switches and steppers. Built on {@link GuiRender}
 * + the Inter font ({@link BedwarsQolFont}, Regular for values / Medium for labels). Everything is sized as
 * a clamped fraction of the screen with a consistent spacing rhythm, so it stays balanced from tiny
 * GUI-scale-4 windows up to large ones. Grayscale by design: elevation and hairlines convey depth,
 * not color. The open keybind lives in Minecraft's native Controls menu; ESC closes (and saves).
 */
public class SettingsGui extends GuiScreen {

    // control kinds
    private static final int K_POTION = 1, K_ARMOR = 2, K_INFO = 3,
            K_INVENTORY = 5, K_GENTIMERS = 6, K_STATS = 7, K_LEVEL = 8, K_RANK = 9,
            K_NAMETAG = 10, K_TAB = 11, K_KEYSTROKES = 12, K_HANDPOS = 13, K_HANDSCALE = 14,
            K_BLOCKOVERLAY = 15, K_SEETHROUGH = 16, K_HANDX = 17, K_HANDY = 18,
            K_HANDZ = 19, K_TNTFUSE = 23;
    private static final int K_HUDSIZE = 20, K_DISPLAY = 21, K_GUISIZE = 22,
            K_OVERLAYSTYLE = 24, K_TNTRADIUS = 25, K_OVERLAYCOLOR = 26, K_OVERLAYOPACITY = 27;
    private static final int K_SWEATREPORT = 28;
    private static final int K_TAB_HEADERFOOTER = 44;
    private static final int K_TAB_PING = 52;
    private static final int K_CHATHOVER = 53;
    // Per-module "Background" sub-toggles (draw a panel behind the HUD element).
    private static final int K_POTION_BG = 60, K_ARMOR_BG = 61, K_INFO_BG = 62,
            K_INVENTORY_BG = 63, K_GENTIMERS_BG = 64, K_KEYSTROKES_BG = 65;
    // HUD text font: modern (Inter) vs vanilla Minecraft.
    private static final int K_HUDFONT = 66;
    // Auto GG: say "gg" once each time a BedWars game ends.
    private static final int K_AUTOGG = 67;
    private static final int K_SCOREBOARD_SIZE = 46, K_STYLEDTAB_SIZE = 47;
    private static final int K_SUPPRESSESC = 48;
    private static final int K_DUMMY = 49, K_DUMMY_KEY = 50, K_DUMMY_CLEAR = 51;
    private static final int K_EDITHUD = 30;
    // "In Game Only" sub-toggles: render the HUD only during an active BedWars game.
    private static final int K_POTION_INGAME = 31, K_ARMOR_INGAME = 32,
            K_INVENTORY_INGAME = 33, K_KEYSTROKES_INGAME = 34;

    private static final String[] GUI_SIZES = {"Small", "Medium", "Large"};
    private static final String[] TEXT_SIZES = {"Small", "Medium", "Large"};
    private static final String[] DISPLAY_MODES = {"Text", "Image"};
    private static final String[] FONT_MODES = {"Modern", "Minecraft"};
    private static final String[] OVERLAY_STYLES = {"Outline", "Fill", "Both"};
    private static final String[] OVERLAY_COLORS = {"White", "Red", "Green", "Blue", "Yellow", "Aqua", "Pink"};
    private static final int[] OVERLAY_COLOR_RGB = {0xFFFFFF, 0xFF5555, 0x55FF55, 0x5599FF, 0xFFD24A, 0x55FFFF, 0xFF7AC6};
    private static final String[] OVERLAY_OPACITIES = {"Low", "Medium", "High"};
    private static final int[] OVERLAY_ALPHA = {0x40, 0x80, 0xC0};
    private static final String[] TNT_RADII = {"5", "10", "15", "20", "30"};
    private static final int[] TNT_RADIUS_VALUES = {5, 10, 15, 20, 30};
    private static final String[] SIZES = {"Small", "Medium", "Large"};
    // ACTION-button caption for the Debug "Remove Dummies" row (chip label lives in options[0]).
    private static final String[] DUMMY_CLEAR_LABEL = {"Clear"};

    private static final BedwarsQolFont.Weight MED = BedwarsQolFont.Weight.MEDIUM;

    // Header band: the version (left) + the global Edit HUD action (right). The version tracks
    // BedwarsQol.VERSION; the brand name was removed so only the version identifies the header.
    private static final String EDIT_HUD_LABEL = "Edit HUD";

    private enum RowType { TOGGLE, STEPPER, ACTION, SLIDER, KEYBIND }

    private static final class RowDef {
        final RowType type;
        final String label;
        final String desc;     // module-card description (null for children / plain rows)
        final int kind;
        final String[] options;
        final float min, max;  // SLIDER range
        final boolean child;   // indented sub-setting
        final int parentKind;  // master toggle this depends on (0 = top-level)

        RowDef(RowType type, String label, int kind, String[] options) {
            this(type, label, null, kind, options, 0, 0f, 0f);
        }

        RowDef(RowType type, String label, int kind, String[] options, int parentKind) {
            this(type, label, null, kind, options, parentKind, 0f, 0f);
        }

        RowDef(RowType type, String label, int kind, float min, float max, int parentKind) {
            this(type, label, null, kind, null, parentKind, min, max);
        }

        /** Module card: title + description, top-level toggle. */
        RowDef(RowType type, String label, String desc, int kind) {
            this(type, label, desc, kind, null, 0, 0f, 0f);
        }

        private RowDef(RowType type, String label, String desc, int kind, String[] options, int parentKind, float min, float max) {
            this.type = type;
            this.label = label;
            this.desc = desc;
            this.kind = kind;
            this.options = options;
            this.parentKind = parentKind;
            this.child = parentKind != 0;
            this.min = min;
            this.max = max;
        }
    }

    private static final class Section {
        final String tab;
        final RowDef[] rows;
        final boolean cards;   // render modules as collapsible cards (vs the flat row engine)

        Section(String tab, RowDef... rows) {
            this(tab, false, rows);
        }

        Section(String tab, boolean cards, RowDef... rows) {
            this.tab = tab;
            this.cards = cards;
            this.rows = rows;
        }
    }

    private static final Section[] SECTIONS = {
            new Section("HUD", true,
                    new RowDef(RowType.TOGGLE, "Potion", "Active effects and timers", K_POTION),
                    new RowDef(RowType.TOGGLE, "In Game Only", K_POTION_INGAME, null, K_POTION),
                    new RowDef(RowType.TOGGLE, "Background", K_POTION_BG, null, K_POTION),
                    new RowDef(RowType.TOGGLE, "Armor", "Equipped armor type", K_ARMOR),
                    new RowDef(RowType.TOGGLE, "In Game Only", K_ARMOR_INGAME, null, K_ARMOR),
                    new RowDef(RowType.TOGGLE, "Background", K_ARMOR_BG, null, K_ARMOR),
                    new RowDef(RowType.TOGGLE, "Info", "FPS, CPS, TPS, ping", K_INFO),
                    new RowDef(RowType.TOGGLE, "Background", K_INFO_BG, null, K_INFO),
                    new RowDef(RowType.TOGGLE, "Inventory", "Stored items at a glance", K_INVENTORY),
                    new RowDef(RowType.TOGGLE, "In Game Only", K_INVENTORY_INGAME, null, K_INVENTORY),
                    new RowDef(RowType.TOGGLE, "Background", K_INVENTORY_BG, null, K_INVENTORY),
                    new RowDef(RowType.TOGGLE, "Gen Timers", "Diamond and emerald timers", K_GENTIMERS),
                    new RowDef(RowType.TOGGLE, "Background", K_GENTIMERS_BG, null, K_GENTIMERS),
                    new RowDef(RowType.TOGGLE, "Keystrokes", "WASD and spacebar keys", K_KEYSTROKES),
                    new RowDef(RowType.TOGGLE, "In Game Only", K_KEYSTROKES_INGAME, null, K_KEYSTROKES),
                    new RowDef(RowType.TOGGLE, "Background", K_KEYSTROKES_BG, null, K_KEYSTROKES)),
            new Section("Combat", true,
                    new RowDef(RowType.TOGGLE, "Hand Position", "Move and resize held item", K_HANDPOS),
                    new RowDef(RowType.SLIDER, "X", K_HANDX, -1.0f, 1.0f, K_HANDPOS),
                    new RowDef(RowType.SLIDER, "Y", K_HANDY, -1.0f, 1.0f, K_HANDPOS),
                    new RowDef(RowType.SLIDER, "Z", K_HANDZ, -1.0f, 1.0f, K_HANDPOS),
                    new RowDef(RowType.SLIDER, "Scale", K_HANDSCALE, 0.5f, 2.0f, K_HANDPOS),
                    new RowDef(RowType.TOGGLE, "TNT Countdown", "Fuse timer for nearby TNT", K_TNTFUSE),
                    new RowDef(RowType.STEPPER, "Radius", K_TNTRADIUS, TNT_RADII, K_TNTFUSE),
                    new RowDef(RowType.TOGGLE, "Disable Esc Menu", "Stop Esc pausing the game", K_SUPPRESSESC)),
            new Section("Visuals", true,
                    new RowDef(RowType.TOGGLE, "Block Overlay", "Highlight targeted block", K_BLOCKOVERLAY),
                    new RowDef(RowType.TOGGLE, "See-Through", K_SEETHROUGH, null, K_BLOCKOVERLAY),
                    new RowDef(RowType.STEPPER, "Style", K_OVERLAYSTYLE, OVERLAY_STYLES, K_BLOCKOVERLAY),
                    new RowDef(RowType.STEPPER, "Color", K_OVERLAYCOLOR, OVERLAY_COLORS, K_BLOCKOVERLAY),
                    new RowDef(RowType.STEPPER, "Opacity", K_OVERLAYOPACITY, OVERLAY_OPACITIES, K_BLOCKOVERLAY),
                    new RowDef(RowType.TOGGLE, "Hide Tab Header/Footer", "Hide tab header and footer", K_TAB_HEADERFOOTER),
                    new RowDef(RowType.TOGGLE, "Tab Numeric Ping", "Show ping as a number", K_TAB_PING)),
            new Section("Hypixel", true,
                    new RowDef(RowType.TOGGLE, "Hypixel Stats", "BedWars stats on screen", K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Nametag", K_NAMETAG, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Tab", K_TAB, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Chat Hover", K_CHATHOVER, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Level", K_LEVEL, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Rank", K_RANK, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Party Report", K_SWEATREPORT, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Auto GG", "Say gg when a game ends", K_AUTOGG)),
            new Section("Settings",
                    new RowDef(RowType.STEPPER, "GUI Size", K_GUISIZE, GUI_SIZES),
                    new RowDef(RowType.STEPPER, "HUD Size", K_HUDSIZE, TEXT_SIZES),
                    new RowDef(RowType.STEPPER, "Scoreboard Size", K_SCOREBOARD_SIZE, SIZES),
                    new RowDef(RowType.STEPPER, "Tab List Size", K_STYLEDTAB_SIZE, SIZES),
                    new RowDef(RowType.STEPPER, "Display", K_DISPLAY, DISPLAY_MODES),
                    new RowDef(RowType.STEPPER, "Font", K_HUDFONT, FONT_MODES)),
            new Section("Debug", true,
                    new RowDef(RowType.TOGGLE, "Test Dummy", "Spawn practice players", K_DUMMY),
                    new RowDef(RowType.KEYBIND, "Spawn Key", K_DUMMY_KEY, null, K_DUMMY),
                    new RowDef(RowType.ACTION, "Remove Dummies", K_DUMMY_CLEAR, DUMMY_CLEAR_LABEL, K_DUMMY)),
    };

    /** Index of the Settings section (its own flat-row render path) in {@link #SECTIONS}. */
    private static final int SETTINGS_SECTION = 4; // the only flat-row page; hosts the theme carousel
    // The Settings page renders its rows + dropdowns 30% smaller than the base control sizes (the theme
    // band keeps its size).
    private static final float SETTINGS_SCALE = 0.7f;
    // Magnifier icon cell (atlas index 0) drawn inside the header search bar.
    private static final int SEARCH_ICON = 0;
    // Sidebar icons live in a fixed atlas baked in the original section order (0 Search/magnifier,
    // … 4 Stats, 5 Keybinds, 6 Settings, 7 Debug). The Search page is gone (the bar lives in the header)
    // and the Keybinds page was removed, so each live section maps to its atlas cell, skipping the unused
    // Search (0) and Keybinds (5) cells.
    private static final int[] SECTION_ICON = {1, 2, 3, 4, 6, 7};

    private static final int MAX_ROWS = 6;
    // Sidebar pill insets: gap from the panel edge to the pill, and from the pill to its label.
    private static final int NAV_INSET = 6;
    private static final int NAV_TEXT_PAD = 10;

    // ---- grayscale palette (Radix-style neutral ramp; depth via luminance + hairlines) ----
    // Shared constants live in gui/render/Theme so HUD elements (scoreboard, tab list) match exactly.
    // Semi-transparent main background: the same near-black grey as the rest of the GUI, just at a lower
    // alpha so the (dimmed) game shows through. Module cards keep their own opaque CARD_OFF_BG fill
    // (header + expanded sub-options), so cards and their expanded forms read as solid surfaces on top.
    private static final int PANEL = 0xB01A1A1A;
    // Header bar fill: the main GUI background colour, a touch darker, INDEPENDENT of the module theme
    // (so the header reads identically on every preset). Slightly more opaque than PANEL so the band
    // reads as a defined header strip rather than blending into the body.
    private static final int HEADER_BG = 0xC8141414;
    // Full-screen scrim drawn over the blurred world (when GuiBlur is active): a lighter dim than the
    // vanilla veil so the blur shows through clearly while the panel stays readable. The non-blur
    // fallback (no world / no framebuffer support) uses the vanilla darker drawDefaultBackground veil.
    private static final int SCRIM = 0x73000000;
    private static final int PANEL_BORDER = Theme.PANEL_BORDER; // whiteish keyline (dropdowns / active chips)
    private static final int DIVIDER = Theme.DIVIDER;
    private static final int SEPARATOR = Theme.SEPARATOR;
    private static final int NAV_SEL = 0xFF2C2C2C;
    private static final int NAV_HOVER = 0x14FFFFFF;
    private static final int ROW_HOVER = Theme.ROW_HOVER;
    private static final int BTN_BG = 0xFF242424;
    private static final int BTN_HOVER = 0xFF2E2E2E;
    private static final int BTN_BORDER = 0x14FFFFFF;
    private static final int TRACK_OFF = 0xFF2E2E2E;
    private static final int TRACK_ON = 0xFFDADADA;
    private static final int KNOB_OFF = 0xFFB5B5B5;
    private static final int KNOB_ON = 0xFFFFFFFF;
    private static final int KNOB_RING = 0x4D000000;
    private static final int TRACK_DISABLED = 0xFF262626; // sub-setting switch when its parent is off
    private static final int KNOB_DISABLED = 0xFF555555;
    private static final int SWITCH_DISABLED_BORDER = 0x66FFFFFF; // whiteish keyline on a disabled control
    private static final int CHECKBOX_BORDER = 0x70FFFFFF;        // keyline around an unchecked checkbox
    private static final int TEXT_HI = Theme.TEXT_HI;
    private static final int TEXT_MID = Theme.TEXT_MID;
    private static final int TEXT_LO = Theme.TEXT_LO;
    private static final int NAV_HOVER_TEXT = 0xFFC8C8C8; // inactive nav item on hover (grey, brighter than mid)
    private static final int PANEL_R = Theme.PANEL_R;
    // ---- module cards (accordion) ----
    // Translucent card fill: more opaque than the PANEL background (0x90 alpha) so cards still read as
    // raised surfaces, but no longer fully solid. Backs module cards (header + expanded body) and the
    // search bar; the dropdown popup keeps its own opaque DD_BG so menus stay readable.
    private static final int CARD_OFF_BG = 0xC81C1C1C;
    // Header search field fill: a lighter grey than the cards/header so the bar stands out on its own.
    private static final int SEARCH_BAR_BG = 0xD0343434;
    private static final int CARD_OFF_BORDER = 0x14FFFFFF;
    private static final int CARD_ON_BORDER = 0x3DFFFFFF;
    private static final int CARD_R = 2;

    /** A module-card colour preset: card fill + border (off / enabled), picked from the Settings carousel. */
    private static final class ThemeDef {
        final String name;
        final int bg, borderOff, borderOn;
        ThemeDef(String name, int bg, int borderOff, int borderOn) {
            this.name = name; this.bg = bg; this.borderOff = borderOff; this.borderOn = borderOn;
        }
    }

    // ---- module-card colour themes (the carousel on the Settings page) ----
    // Each preset recolours ONLY the module-card fill + border — never the panel, the search bar or the
    // HUD. Index 0 reproduces today's grayscale EXACTLY (CARD_OFF_BG / CARD_OFF_BORDER / CARD_ON_BORDER),
    // so existing configs are visually unchanged. Coloured presets bake a higher bg alpha (0xE6) so vivid
    // hues stay clean over the dimmed world, and pair a same-hue border at a faint (off) / strong (on)
    // alpha — the "stronger border when enabled" cue.
    private static final ThemeDef[] THEMES = {
            new ThemeDef("Graphite", 0xC81C1C1C, 0x14FFFFFF, 0x3DFFFFFF), // default == today's grey
            new ThemeDef("Slate",    0xE61E2530, 0x40647A93, 0xB08FA3BC),
            new ThemeDef("Steel",    0xE61E2429, 0x40708595, 0xB098AEBE),
            new ThemeDef("Midnight", 0xE61A2233, 0x405E7BB0, 0xB07FA8E0),
            new ThemeDef("Indigo",   0xE61E1A3A, 0x406E5EC4, 0xB0937FE6),
            new ThemeDef("Violet",   0xE6261A33, 0x40925EC4, 0xB0B98FF0),
            new ThemeDef("Magenta",  0xE6311A30, 0x40B05EA8, 0xB0E68FD0),
            new ThemeDef("Rose",     0xE6301821, 0x40C46F86, 0xB0F08FB0),
            new ThemeDef("Crimson",  0xE6331616, 0x40C45353, 0xB0F08080),
            new ThemeDef("Sunset",   0xE6331E14, 0x40C47A4F, 0xB0F0A36F),
            new ThemeDef("Amber",    0xE62F2616, 0x40C49A4F, 0xB0F0C070),
            new ThemeDef("Gold",     0xE62E2A12, 0x40C4B04F, 0xB0F0DC70),
            new ThemeDef("Lime",     0xE6202E14, 0x408AC44F, 0xB0AEE66F),
            new ThemeDef("Emerald",  0xE6112B1F, 0x404FB37A, 0xB06FE0A0),
            new ThemeDef("Forest",   0xE6132714, 0x4055A85A, 0xB07ACC7F),
            new ThemeDef("Teal",     0xE6112B2A, 0x404FB3A8, 0xB06FE0CF),
            new ThemeDef("Ocean",    0xE6112B30, 0x404FB3C4, 0xB06FE0F0),
            new ThemeDef("Sky",      0xE6172A33, 0x405BA8C4, 0xB082CEE6),
            new ThemeDef("Mocha",    0xE62A2018, 0x40A07A55, 0xB0C9A37F),
            new ThemeDef("Sand",     0xE62B2A20, 0x40B0A578, 0xB0E0D2A8),
    };
    // ---- dropdown (<select>) menu ----
    private static final int DD_BG = 0xFF1E1E1E;            // menu surface (fully opaque, slightly elevated)
    // White keyline matching the main GUI panel border. Drawn LAST in drawDropdownPopup, on top of the
    // row fills, so it frames the box and every row edge consistently; the fills are inset to its inner
    // edge (see drawDropdownPopup) so none spills past it. Linked to the panel border so they always match.
    private static final int DD_BORDER = PANEL_BORDER;      // == Theme.PANEL_BORDER (0xCCFFFFFF white keyline)
    // Opaque row fills, framed by the white keyline above: a subtle grey selection and a brighter hover
    // (== 0x33 / 0x4D white resolved over DD_BG). (Recompute these if DD_BG changes.)
    private static final int DD_ITEM_SELECTED = 0xFF4B4B4B; // subtle grey selection
    private static final int DD_ITEM_HOVER = 0xFF626262;    // brighter active-row (hovered) tone
    // Always-reserved gutter on the right of the content column so card width is identical whether
    // or not the scrollbar is showing (the 3px scrollbar lives inside this band).
    private static final int SCROLL_GUTTER = 7;

    private static final class Row {
        final RowDef def;
        int x, y, w, h;

        Row(RowDef def) {
            this.def = def;
        }

        boolean hit(int mx, int my) {
            return GuiRender.inside(mx, my, x, y, x + w, y + h);
        }
    }

    /** A collapsible module card: a header (title + description) plus its sub-option rows when expanded. */
    private static final class Card {
        final RowDef module;
        final boolean hasSub;
        boolean expanded;
        int x, y, w, headerH, h;
        final List<Row> children = new ArrayList<Row>();

        Card(RowDef module, boolean hasSub) {
            this.module = module;
            this.hasSub = hasSub;
        }
    }

    /** A module shown on the Search page: a top-level toggle RowDef plus the section it belongs to. */
    private static final class SearchModule {
        final RowDef def;     // def.label / def.desc / def.kind
        final String section; // owning section tab (HUD / Combat / Visuals / Stats)

        SearchModule(RowDef def, String section) {
            this.def = def;
            this.section = section;
        }
    }

    private int selectedSection = 0; // open to HUD by default; search (header bar) overlays any section
    private final List<Row> rows = new ArrayList<Row>();
    private final List<Card> cards = new ArrayList<Card>();
    // Which module cards are expanded (by module kind). Empty = all collapsed (the default each open).
    private final java.util.Set<Integer> expandedModules = new java.util.HashSet<Integer>();

    // ---- module-card theme (resolved from settings().moduleTheme by resolveTheme) ----
    // The card fill + the off/enabled border colours used by drawCards. Default (index 0) == today's grey.
    private int themeCardBg = CARD_OFF_BG, themeCardBorderOff = CARD_OFF_BORDER, themeCardBorderOn = CARD_ON_BORDER;
    // Theme carousel (Settings page): a rounded-rectangle swatch strip in a band above the stepper rows,
    // with its OWN horizontal scroll independent of the vertical row scroll. flatRowsTop is where the rows
    // begin (below the band). Geometry is computed in layoutThemeCarousel; swatch rects derive from it in
    // themeSwatchRect. Each swatch is sized to the longest theme name so the name centres inside it.
    private int carouselTop, carouselBottom, flatRowsTop;
    private int themeSwatchW, themeSwatchH, themeSwatchGap, themeStripX0;
    private int themeScroll, themeScrollMax;
    private float themeNameScale;
    // Left-aligned "Themes" heading above the swatch strip + the y where the swatch row begins.
    private float themeHeaderScale;
    private int themeHeaderH, themeSwatchTop;
    // Last cursor position seen by drawScreen — used by handleMouseInput to route the wheel to the carousel
    // only while the cursor is over its band (handleMouseInput isn't handed mouse coordinates).
    private int lastMouseX, lastMouseY;
    /** GL multiplier that locks the panel to the "Large" (guiScale 3) density on any host GUI Scale, so the
     *  settings GUI renders at one fixed physical size regardless of the client's Minecraft GUI Scale. 1.0
     *  when the host is already at Large. Set in {@link #initGui()}, applied in {@link #drawScreen}, and the
     *  cursor is divided by it in every mouse handler so hit-testing stays aligned with the scaled render. */
    private float uiScale = 1f;

    // geometry / rhythm (computed in initGui)
    private int panelX, panelY, panelW, panelH, pad, sidebarW;
    private int contentX, contentRight, contentTop, contentH, rowH;
    // Full-width header band across the panel top (name + version + Edit HUD button); the sidebar nav
    // and content pane start below it. headerTitleScale sizes the (SemiBold) brand name.
    private int headerY1, headerY2, headerH;
    private float headerTitleScale;
    private int navStartY, navItemH;
    // Edit HUD button pinned to the bottom of the sidebar (its own footer band, below the nav items).
    private int sidebarFooterY1, sidebarFooterY2;
    private float labelScale, valueScale, navScale;
    // Module-card typography: a slightly smaller header and a noticeably smaller subtext than the base
    // label scale, so cards read compact/sleek and the (shortened) descriptions fit on a single line.
    private float cardTitleScale, cardDescScale;
    // Dropdown typography: a notch smaller than the value scale so triggers and option rows read compact.
    private float ddFontScale;
    // Settings-page row label scale. Its dropdown triggers/menus render their value at THIS scale too, so
    // the button text matches the setting title to its left (computed in initGui).
    private float settingsRowLabelScale;
    // Widest stepper option label on the current page — shared so every stepper's arrows align.
    private float pageStepperTextW;
    // Nav icon geometry (computed in initGui), drawn left of each sidebar label.
    private float navIconSize, navIconGap;
    // Sub-setting rows are shorter and indented under their parent; total block height for the scrollbar.
    private int childRowH, childIndent, rowsBlockH;
    // Viewport the card list is laid out into (top/bottom). The per-section view uses the full content
    // band; the search view uses the band below the search bar. Set in buildCards, read by drawCards/clicks.
    private int cardsViewTop, cardsViewBottom;
    // Active slider drag (0 = none) and the cached track geometry/range for it.
    private int draggingSliderKind;
    private float dragTrackX1, dragTrackX2, dragMin, dragMax;
    // Inline numeric entry on a slider's value text: editingSliderKind == 0 means not editing.
    private int editingSliderKind;
    private final StringBuilder editSliderBuf = new StringBuilder();
    private float editSliderMin, editSliderMax;

    // Debug section: capturing the Test Dummy spawn key (the next keypress binds it; ESC cancels).
    private boolean capturingDummyKey;
    private int caretBlink;
    // The search field is focused only after a click inside it (caret shows); a click anywhere else blurs it.
    private boolean searchFocused;
    // Shared vertical scroll for whichever section is showing (the row list or a card grid).
    // Reset to 0 on section switch; maxScroll == 0 means the section fits and never scrolls.
    private int scroll;
    private int maxScroll;
    // Smooth scroll: `scroll` is the instant target the wheel/thumb set; scrollRender eases toward it
    // each frame (System.nanoTime delta) so motion is smooth and frame-rate-independent. On macOS the
    // ease is skipped and scrollRender tracks the target 1:1 (see advanceScroll for why). Layout and
    // hit-testing stay on the integer target; only rendering uses the eased value (via a GL translate).
    private float scrollRender;
    private float scrollAccum; // fractional wheel accumulator; whole pixels are committed to `scroll`
    private long lastFrameNanos;
    private boolean draggingThumb;
    private float thumbGrabDy; // cursor's offset within the thumb when grabbed
    // Shared bottom "action bar" button band — pinned to the bottom of the content area, used when a
    // flat section pulls an ACTION row out to the bottom (sized & positioned in initGui/layoutContent).
    private int bottomBtnY1, bottomBtnY2;
    private RowDef pinnedAction; // ACTION row pulled out of a flat section to the bottom band (or null)
    // Bottom of the flat-row scrollable viewport: the full content band, minus the pinned-button band
    // when an ACTION is pinned. The scrollbar must use this (not contentTop+contentH) so it stops above
    // the pinned button instead of overrunning it. Set in layoutContent, read by drawRows/scrollbarGeom.
    private int rowsViewBottom;

    // ---- Dropdown (<select>) popup state ----
    // A stepper's options are chosen from a click-to-open dropdown instead of < > arrows. Only one is
    // open at a time, keyed by stepper kind (0 = none). The trigger's screen rect is captured at open
    // time so the popup stays anchored even though rows live inside a scrolled/translated matrix; the
    // popup is drawn last (on top of everything) and is modal — it absorbs the next click (pick or dismiss).
    private int openDropdownKind;
    private String[] ddOptions;
    private float ddX1, ddY1, ddX2, ddY2; // anchor = the trigger button's screen rect
    private float ddScale;                // option text scale (matches the trigger)
    private float ddPad;                  // horizontal padding (matches the trigger)

    // ---- Search section state ----
    private final StringBuilder searchQuery = new StringBuilder();
    private final List<SearchModule> allModules = new ArrayList<SearchModule>();    // collected once in initGui
    private final List<SearchModule> searchResults = new ArrayList<SearchModule>(); // current filtered + ranked view
    private int searchBarX1, searchBarX2, searchBarY1, searchBarY2;
    private int searchClearX1, searchClearX2; // clear-x hit zone
    private int searchListTop, searchListBottom;

    @Override
    public void initGui() {
        buttonList.clear();
        closeDropdown(); // never carry an open popup across a resize / panel re-layout
        // Key repeat so backspace/arrows repeat while editing a macro message; reset on close.
        Keyboard.enableRepeatEvents(true);
        float gf = guiFactor(settings().guiSize);
        // Lock the panel to the "Large" (guiScale 3) physical size on ANY host GUI Scale, so the settings
        // GUI looks identical everywhere — the mod's own Small/Normal/Large is the only size control. We lay
        // the panel out against vW/vH (the scaled resolution that Large WOULD give on this display) and apply
        // uiScale as a GL multiplier in drawScreen, remapping the cursor to match. At guiScale 3 uiScale == 1
        // and vW/vH == width/height, so the Forge look is byte-for-byte unchanged.
        int actualSf = Math.max(1, new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor());
        uiScale = largeScaleFactor() / (float) actualSf;
        int vW = Math.round(width / uiScale);
        int vH = Math.round(height / uiScale);
        // The responsive "Large" size, then scaled down for Medium / Small. Everything below derives
        // from panelW/panelH, so the whole panel scales proportionally; the min-clamps are low enough
        // that the tallest (6-row) section still fits at Small.
        int largeW = clamp(Math.round(vW * 0.62f), 330, 480);
        // 15% shorter than before (height factor and both clamps scaled by 0.85), so the panel is no
        // taller than it needs to be at every GUI size.
        int largeH = clamp(Math.round(vH * 0.51f), 160, 272);
        panelW = Math.round(largeW * gf);
        // Small GUI mode gets a little extra height (width unchanged) so the sidebar items aren't cramped.
        float smallHeightBoost = settings().guiSize == 0 ? 1.12f : 1.0f;
        panelH = Math.round(largeH * gf * smallHeightBoost);
        panelX = (vW - panelW) / 2;
        panelY = (vH - panelH) / 2;
        pad = clamp(Math.round(panelW * 0.040f), 10, 18);

        contentRight = panelX + panelW - pad;
        int innerTop = panelY + pad;
        int innerBottom = panelY + panelH - pad;
        // Row rhythm is derived from the FULL inner height, so rows are sized identically with or
        // without the header; the header band is then carved off the top of the body below.
        rowH = clamp((innerBottom - innerTop) / MAX_ROWS, 16, 40);

        // Typography is derived from rowH up front so the header bar can be sized to hug its own text.
        labelScale = clampf(rowH * 0.046f, 1.0f, 1.85f);
        valueScale = labelScale * 0.96f;
        // Brand name a notch larger than the base label, drawn in SemiBold.
        headerTitleScale = clampf(labelScale * 0.7125f, 0.75f, 1.125f); // 25% smaller header text

        // Full-width themed header bar, flush with the panel's top edge (brand + version left, Edit HUD
        // button right, divider beneath). headerH hugs its content — the brand title (or the slightly
        // smaller Edit HUD label) — with a slim symmetric pad, so there's no dead space above or below it.
        float headerContentH = Math.max(BedwarsQolFont.height(headerTitleScale),
                BedwarsQolFont.height(valueScale * 0.595f));
        headerH = Math.round(headerContentH) + 2 * clamp(Math.round(rowH * 0.22f), 4, 7);
        headerH = Math.round(headerH * 1.265f); // taller so the search bar fits comfortably (15% + 10%)
        headerY1 = panelY;
        headerY2 = headerY1 + headerH;
        // Persistent header search bar (right side of the header band): fill always, thin border only
        // while focused. The version sits at the left; this is right-aligned to the content edge.
        int sbPadY = clamp(Math.round(headerH * 0.16f), 2, 5);
        int sbH = Math.round((headerH - 2 * sbPadY) * 0.8f); // 20% shorter than the padded band
        // Optically centre the bar: the bright divider closing the band's bottom makes a geometrically
        // centred bar read a touch high, so bias it down by a hair (a previous larger bias read too low).
        // Clamped to always keep a pixel of clearance above the divider.
        int sbDrop = Math.min(Math.round(headerH * 0.035f), (headerH - sbH) / 2 - 1);
        searchBarY1 = headerY1 + (headerH - sbH) / 2 + Math.max(0, sbDrop);
        searchBarY2 = searchBarY1 + sbH;
        int sbW = clamp(Math.round(panelW * 0.224f), 63, Math.round(panelW * 0.5f)); // 30% narrower
        searchBarX2 = panelX + panelW - pad;
        searchBarX1 = searchBarX2 - sbW;
        int searchClearW = clamp(Math.round((searchBarY2 - searchBarY1) * 0.7f), 10, 18);
        searchClearX2 = searchBarX2 - 5;
        searchClearX1 = searchClearX2 - searchClearW;
        // Gap below the header divider == the bottom pad above the panel border (innerBottom is pad above
        // the bottom edge), so the body is inset symmetrically top and bottom.
        int bodyTop = headerY2 + pad;

        contentTop = bodyTop;
        contentH = innerBottom - bodyTop;
        childRowH = clamp(Math.round(rowH * 0.66f), 12, 26); // compact sub-setting rows
        childIndent = clamp(Math.round(rowH * 0.7f), 12, 28);
        ddFontScale = valueScale * 0.74f; // dropdowns smaller than other value text
        settingsRowLabelScale = labelScale * 0.72f * SETTINGS_SCALE * 1.375f; // Settings row label (& its dropdown text)
        cardTitleScale = labelScale * 0.7425f; // 25% smaller, then +10% (0.675 * 1.10)
        cardDescScale = labelScale * 0.5445f;  // tracks the title (+10%, 0.495 * 1.10) to keep hierarchy
        // Sidebar vertical layout: every SECTIONS item must fit inside the panel at every GUI size.
        // The nav now starts below the header band, so its available height is reduced accordingly.
        int navTop = bodyTop;
        // Reserve a footer band at the bottom of the sidebar for the (small) Edit HUD button.
        int sidebarFooterH = clamp(Math.round(rowH * 0.5f), 11, 18);
        sidebarFooterY2 = innerBottom;
        sidebarFooterY1 = sidebarFooterY2 - sidebarFooterH;
        int navBottom = sidebarFooterY1 - clamp(Math.round(rowH * 0.3f), 4, 10);
        int navAvail = navBottom - navTop;
        // Floor low enough that all items fit at Small even with the header eating the top of the panel;
        // cap the block to the available height so the last item (Debug) can never spill past navBottom.
        navItemH = clamp(navAvail / SECTIONS.length, 10, 30);
        int navBlockH = Math.min(navItemH * SECTIONS.length, navAvail);
        navStartY = navTop + Math.max(0, (navAvail - navBlockH) / 2);

        // Nav glyphs track the row height so they stay proportionate (and never overflow) in a short pill
        // at Small; at Large navItemH hits 30 so min() keeps the previous look unchanged.
        navScale = clampf(Math.min(labelScale * 0.82f, navItemH * 0.040f), 0.78f, 1.4f);
        // A small square icon precedes each nav label; sized to the label's cap height.
        navIconSize = BedwarsQolFont.height(navScale) + 1f;
        navIconGap = clampf(navIconSize * 0.5f, 5f, 8f);

        // Sidebar width = icon + gap + widest nav label (measured in the heavier Medium weight used
        // for the selected item) + the pill inset and symmetric text padding on each side. No dead space.
        float maxNav = 0f;
        for (Section s : SECTIONS) maxNav = Math.max(maxNav, GuiRender.textWidth(s.tab, navScale, MED));
        sidebarW = clamp(Math.round(maxNav + navIconSize + navIconGap) + 2 * (NAV_INSET + NAV_TEXT_PAD), 56, Math.round(panelW * 0.5f));
        contentX = panelX + sidebarW + pad;

        // Shared bottom action-button band, pinned to the bottom of the content area.
        int bottomBtnH = clamp(Math.round(rowH * 0.72f), 13, 24);
        bottomBtnY2 = contentTop + contentH;
        bottomBtnY1 = bottomBtnY2 - bottomBtnH;

        if (allModules.isEmpty()) collectModules();
        resolveTheme();
        layoutContent();
        GuiBlur.begin(); // start the world-blur fade-in behind the panel (same raw-GL path on both builds)
    }

    private static float guiFactor(int guiSize) {
        switch (guiSize) {
            case 0: return 0.70f;  // Small
            case 1: return 0.85f;  // Medium
            default: return 1.0f;  // Large
        }
    }

    /** The scaleFactor vanilla Minecraft would pick at GUI Scale = Large (guiScale 3) on this display. The
     *  panel locks to this density (see {@link #uiScale}) so it renders at the same physical size at any host
     *  GUI Scale. Mirrors 1.8.9 ScaledResolution's loop, capped at 3; on a display too small to reach 3 it
     *  returns whatever Large would actually give, so the panel never overflows the screen. */
    private int largeScaleFactor() {
        int gw = mc.displayWidth, gh = mc.displayHeight;
        int sf = 1;
        while (sf < 3 && gw / (sf + 1) >= 320 && gh / (sf + 1) >= 240) sf++;
        return sf;
    }

    private void layoutContent() {
        rows.clear();
        cards.clear();
        pinnedAction = null;
        if (searchActive()) {
            layoutSearchResults();
            return;
        }
        if (SECTIONS[selectedSection].cards) {
            layoutCards();
            return;
        }
        Section section = SECTIONS[selectedSection];
        // The Settings page carries a theme carousel in a band at the top; its rows begin below it.
        // Other (hypothetical) flat sections start at contentTop as before.
        flatRowsTop = contentTop;
        if (selectedSection == SETTINGS_SECTION) layoutThemeCarousel();
        int rowsTop = flatRowsTop;
        // Compact the flat "Settings" rows: a shorter top-level row height than the global rowH.
        int flatRowH = clamp(Math.round(rowH * 0.45f), 13, 18); // packed tighter — much smaller vertical gap
        // Pull any ACTION row out to the shared pinned bottom band; lay out the rest (steppers).
        // Widest stepper option label is shared so every stepper's arrows align.
        pageStepperTextW = 0f;
        int blockH = 0;
        for (RowDef rd : section.rows) {
            if (rd.type == RowType.ACTION) { pinnedAction = rd; continue; }
            if (rd.type == RowType.STEPPER && rd.options != null) {
                // Triggers render their value at settingsRowLabelScale here, so measure there too.
                for (String opt : rd.options) {
                    pageStepperTextW = Math.max(pageStepperTextW, GuiRender.textWidth(opt, settingsRowLabelScale));
                }
            }
            blockH += rd.child ? childRowH : flatRowH;
        }
        rowsBlockH = blockH;
        // Rows fill the area above the pinned button (if any) and always start from the TOP, not centered.
        int listBottom = pinnedAction != null
                ? bottomBtnY1 - clamp(Math.round(rowH * 0.35f), 4, 10) : contentTop + contentH;
        rowsViewBottom = listBottom;
        int availH = listBottom - rowsTop;
        boolean scrollable = blockH > availH;
        maxScroll = scrollable ? blockH - availH : 0;
        scroll = clamp(scroll, 0, maxScroll);
        int contentW = contentRight - contentX - SCROLL_GUTTER;
        int y = rowsTop - scroll; // top-aligned, below the carousel band on the Settings page
        for (RowDef rd : section.rows) {
            if (rd.type == RowType.ACTION) continue; // drawn as a pinned bottom button
            Row row = new Row(rd);
            int indent = rd.child ? childIndent : 0;
            row.x = contentX + indent;
            row.y = y;
            row.w = contentW - indent;
            row.h = rd.child ? childRowH : flatRowH;
            rows.add(row);
            y += row.h;
        }
    }

    /** Builds the collapsible module cards for the current section. */
    private void layoutCards() {
        Section section = SECTIONS[selectedSection];
        // Group rows into modules (top-level) + their child defs (a child follows its parent).
        List<RowDef> moduleDefs = new ArrayList<RowDef>();
        List<List<RowDef>> childDefs = new ArrayList<List<RowDef>>();
        for (RowDef rd : section.rows) {
            if (!rd.child) {
                moduleDefs.add(rd);
                childDefs.add(new ArrayList<RowDef>());
            } else {
                childDefs.get(childDefs.size() - 1).add(rd);
            }
        }
        buildCards(moduleDefs, childDefs, contentTop, contentH);
    }

    /** Re-runs whichever layout owns the current {@link #cards} (the search view or a section view),
     *  so card clicks that expand/collapse re-flow correctly on either page. */
    private void relayoutCards() {
        if (searchActive()) layoutSearchResults();
        else layoutCards();
    }

    /** All sub-option defs for a module, found by {@code parentKind} (module kinds are globally unique,
     *  so we can resolve children regardless of which section they live in — needed for the search view). */
    private List<RowDef> childrenOf(RowDef module) {
        List<RowDef> kids = new ArrayList<RowDef>();
        for (Section s : SECTIONS) {
            if (!s.cards) continue;
            for (RowDef rd : s.rows) {
                if (rd.child && rd.parentKind == module.kind) kids.add(rd);
            }
        }
        return kids;
    }

    /** Lays {@link #cards} out from parallel module/child lists into a viewport at {@code top} of height
     *  {@code viewH}. Shared by {@link #layoutCards} (per section) and {@link #layoutSearch} (search
     *  results) so both pages render and behave identically. */
    private void buildCards(List<RowDef> moduleDefs, List<List<RowDef>> childDefs, int top, int viewH) {
        cards.clear();
        cardsViewTop = top;
        cardsViewBottom = top + viewH;
        // Widest child stepper option, so their chevrons align (measured at the child scale).
        pageStepperTextW = 0f;
        for (List<RowDef> kids : childDefs) {
            for (RowDef rd : kids) {
                if (rd.type == RowType.STEPPER && rd.options != null) {
                    for (String opt : rd.options) {
                        pageStepperTextW = Math.max(pageStepperTextW, GuiRender.textWidth(opt, ddFontScale));
                    }
                }
            }
        }
        int cardGap = clamp(Math.round(rowH * 0.20f), 3, 8);
        int innerPad = clamp(Math.round(rowH * 0.16f), 3, 7);
        int cIndent = clamp(Math.round(rowH * 0.5f), 10, 22);
        int cardHeaderH = Math.round(BedwarsQolFont.height(cardTitleScale) + 1f + BedwarsQolFont.height(cardDescScale))
                + clamp(Math.round(rowH * 0.4f), 7, 14);

        // Total height first (for the scrollbar / scroll clamp).
        int total = 0;
        for (int m = 0; m < moduleDefs.size(); m++) {
            boolean exp = !childDefs.get(m).isEmpty() && expandedModules.contains(moduleDefs.get(m).kind);
            int childrenH = exp ? childDefs.get(m).size() * childRowH + 2 * innerPad : 0;
            total += cardHeaderH + childrenH;
            if (m < moduleDefs.size() - 1) total += cardGap;
        }
        rowsBlockH = total;
        maxScroll = Math.max(0, total - viewH);
        scroll = clamp(scroll, 0, maxScroll);
        int cw = contentRight - contentX - SCROLL_GUTTER;
        int y = top - scroll; // top-aligned: modules stack from the top, not centered

        for (int m = 0; m < moduleDefs.size(); m++) {
            RowDef mod = moduleDefs.get(m);
            List<RowDef> defs = childDefs.get(m);
            Card card = new Card(mod, !defs.isEmpty());
            card.expanded = card.hasSub && expandedModules.contains(mod.kind);
            card.x = contentX;
            card.w = cw;
            card.y = y;
            card.headerH = cardHeaderH;
            int childrenH = 0;
            if (card.expanded) {
                int cy = y + cardHeaderH + innerPad;
                for (RowDef cd : defs) {
                    Row r = new Row(cd);
                    r.x = contentX + cIndent;
                    r.y = cy;
                    r.w = cw - cIndent - innerPad;
                    r.h = childRowH;
                    card.children.add(r);
                    cy += childRowH;
                }
                childrenH = defs.size() * childRowH + 2 * innerPad;
            }
            card.h = cardHeaderH + childrenH;
            cards.add(card);
            y += card.h + cardGap;
        }
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        GuiBlur.update(); // render the world blur onto the framebuffer before anything draws on top
        advanceScroll();
        // Remap the host cursor into the fixed-"Large" virtual space the panel is laid out in (identity at
        // guiScale 3). Everything below — hover, the off-screen-cursor trick, the dropdown popup — then runs
        // in that space, matching the geometry built in initGui().
        mouseX = Math.round(mouseX / uiScale);
        mouseY = Math.round(mouseY / uiScale);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        // Scrim over the (now blurred) world: a lighter dim while the blur is active so the blur reads
        // clearly; fall back to the vanilla darker veil when there's no blur (no world / no framebuffer).
        // Drawn in real screen coords (full screen) BEFORE the panel's "Large"-density transform below.
        if (GuiBlur.isActive()) GuiRender.rect(0, 0, width, height, SCRIM);
        else drawDefaultBackground();

        // Lock the panel render to the "Large" density; popped after the dropdown popup (the last panel layer).
        GlStateManager.pushMatrix();
        GlStateManager.scale(uiScale, uiScale, 1f);
        GuiRender.scissorScale = uiScale; // glScissor runs outside this matrix — fold the factor into the clip

        int px2 = panelX + panelW, py2 = panelY + panelH;
        // The keyline outline (drawn last, below) is the panel's OUTERMOST edge. The translucent fills are
        // inset by 0.25px (half the AA fringe) so their outer feather ends exactly where the stroke's outer
        // feather ends — the fill never pokes past the border, so nothing (fill or background) bleeds beyond
        // the keyline — while the fill still reaches under the stroke's solid core ([edge+0.25, edge+0.5])
        // so the background can't show through the border either. Radius shrinks 0.25 to stay concentric.
        GuiRender.roundedRect(panelX + 0.25f, panelY + 0.25f, px2 - 0.25f, py2 - 0.25f, PANEL_R - 0.25f, PANEL);
        // Header bar fill: HEADER_BG over the top portion, inset the same 0.25 on its three outer sides;
        // its bottom stays flush at headerY2 over the body fill. Drawn before the keyline.
        GuiRender.roundedRect(panelX + 0.25f, panelY + 0.25f, px2 - 0.25f, headerY2, PANEL_R - 0.25f, PANEL_R - 0.25f, 0f, 0f, HEADER_BG);
        GuiRender.roundedRectOutline(panelX, panelY, px2, py2, PANEL_R, 0.75f, CARD_ON_BORDER); // the panel keyline — the outermost edge; fills sit just inside it

        // While a dropdown is open it's modal: feed the rest of the GUI an off-screen cursor so nothing
        // behind the menu hover-highlights (other triggers, rows) or scales (sidebar items).
        int hx = openDropdownKind != 0 ? -1 : mouseX;
        int hy = openDropdownKind != 0 ? -1 : mouseY;

        // Header band (name + version + Edit HUD); its hairline frames the top, the sidebar/content
        // divider drops from that hairline down to the panel's bottom inset.
        drawHeader(hx, hy);
        GuiRender.rect(panelX + sidebarW, headerY2, panelX + sidebarW + 1, py2 - pad, DIVIDER);

        drawSidebar(hx, hy);
        if (searchActive()) drawSearchResults(hx, hy);
        else if (SECTIONS[selectedSection].cards) drawCards(hx, hy);
        else drawRows(hx, hy);

        // The open dropdown menu floats above all section content (and the scrollbar).
        drawDropdownPopup(mouseX, mouseY);

        GuiRender.scissorScale = 1f;
        GlStateManager.popMatrix();
    }

    /** Full-width header band: brand name + version (left), the global Edit HUD button (right), and a
     *  thin divider beneath. Drawn on every section. The band's background is the themed bar filled in
     *  {@link #drawScreen} (before the panel keyline); here we draw its contents and the divider. */
    private void drawHeader(int mouseX, int mouseY) {
        // Header identity is just the version (left); the persistent search bar sits at the right.
        float titleY = vcenterOptical(headerY1, headerH, headerTitleScale, BedwarsQolFont.Weight.REGULAR) + headerContentDrop();
        GuiRender.text("v" + BedwarsQol.VERSION, panelX + pad, titleY, headerTitleScale, TEXT_MID, BedwarsQolFont.Weight.REGULAR);

        drawSearchBar(mouseX, mouseY);

        // Divider beneath the header: a horizontal rule meeting both side borders flush. rect rasterizes
        // with a half-open [x1,x2) fill rule — the LEFT edge is pixel-inclusive, the RIGHT edge exclusive —
        // so a symmetric ±0.5 inset leaves the right end clean but the left end paints the border's boundary
        // pixel (the slight overlap). The right end stays at the border's inner solid edge (px2 - 0.5); the
        // left end is pushed in one extra device pixel (1/scaleFactor GUI px) so it clears that boundary
        // pixel at every GUI scale, leaving both ends flush with no overlap and no gap.
        int scaleFactor = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        GuiRender.rect(panelX + 0.5f + 1f / scaleFactor, headerY2, (panelX + panelW) - 0.5f, headerY2 + 0.75f, CARD_ON_BORDER);
    }

    /** Persistent header search bar: a translucent rounded field (magnifier + text + clear-x). The border
     *  appears only while the field is focused (clicked into); there is no blinking caret. Typing into it
     *  switches the content area to the search results (see {@link #searchActive}). */
    private void drawSearchBar(int mouseX, int mouseY) {
        String q = searchQuery.toString();
        GuiRender.roundedRect(searchBarX1, searchBarY1, searchBarX2, searchBarY2, CARD_R, SEARCH_BAR_BG);
        if (searchFocused) {
            GuiRender.roundedRectOutline(searchBarX1, searchBarY1, searchBarX2, searchBarY2, CARD_R, 0.5f, PANEL_BORDER);
        }
        float barCy = (searchBarY1 + searchBarY2) / 2f;
        float iconSize = (searchBarY2 - searchBarY1) * 0.5f;
        Icons.draw(SEARCH_ICON, searchBarX1 + 7 + iconSize / 2f, barCy, iconSize, TEXT_MID);
        float textX = searchBarX1 + 7 + iconSize + 6;
        float textRight = searchClearX1 - 6;
        // Text is sized to the (slim) bar so it stays comfortably inset rather than filling it.
        float sScale = Math.min(valueScale, (searchBarY2 - searchBarY1) * 0.62f / BedwarsQolFont.height(1f));
        float ty = vcenter(searchBarY1, searchBarY2 - searchBarY1, sScale);
        if (q.isEmpty()) {
            GuiRender.text("Search...", textX, ty, sScale, TEXT_LO);
        } else {
            GuiRender.text(ellipsize(q, sScale, textRight - textX), textX, ty, sScale, TEXT_HI);
        }
        if (!q.isEmpty()) {
            boolean xHover = GuiRender.inside(mouseX, mouseY, searchClearX1, searchBarY1, searchClearX2, searchBarY2);
            drawCross((searchClearX1 + searchClearX2) / 2f, barCy, (searchBarY2 - searchBarY1) * 0.16f,
                    xHover ? TEXT_HI : TEXT_MID);
        }
    }

    /** Small downward optical correction for header content (version label + Edit HUD label/hit rect).
     *  vcenterOptical places equal pixel gaps above and below the caps, but the band is closed at the
     *  bottom by a bright divider rule while its top is open (the panel's rounded corners blend into the
     *  scrim) — the heavier bottom edge makes perfectly cap-centred content read slightly high. Biasing
     *  it down by a few percent of the band height restores the perceived centre. */
    private float headerContentDrop() {
        return headerH * 0.07f;
    }

    private void drawSidebar(int mouseX, int mouseY) {
        int pillX1 = panelX + NAV_INSET;
        for (int i = 0; i < SECTIONS.length; i++) {
            int y = navStartY + i * navItemH;
            boolean selected = i == selectedSection;
            boolean hover = !selected && GuiRender.inside(mouseX, mouseY, panelX, y, panelX + sidebarW, y + navItemH);
            // No background — just icon + label. Active = white & slightly larger; hovering an inactive
            // item gives the same slight enlarge (with a faint brighten); otherwise greyed at base size.
            boolean enlarge = selected || hover;
            float scale = enlarge ? navScale * 1.12f : navScale;
            float iconSize = enlarge ? navIconSize * 1.12f : navIconSize;
            int navColor = selected ? TEXT_HI : (hover ? NAV_HOVER_TEXT : TEXT_MID);
            drawNavIcon(i, pillX1 + NAV_TEXT_PAD + navIconSize / 2f, y + navItemH / 2f, iconSize, navColor);
            float tx = pillX1 + NAV_TEXT_PAD + navIconSize + navIconGap;
            float ty = vcenter(y, navItemH, scale);
            GuiRender.text(SECTIONS[i].tab, tx, ty, scale, navColor, selected ? MED : BedwarsQolFont.Weight.REGULAR);
        }
        drawSidebarFooter(mouseX, mouseY);
    }

    /** Edit HUD action pinned to the bottom of the sidebar as a footer button (own band, below the nav). */
    private void drawSidebarFooter(int mouseX, int mouseY) {
        float[] b = sidebarEditBtnRect();
        boolean hover = GuiRender.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
        GuiRender.roundedRect(b[0], b[1], b[2], b[3], CARD_R, hover ? BTN_HOVER : BTN_BG);
        GuiRender.roundedRectOutline(b[0], b[1], b[2], b[3], CARD_R, 0.75f, BTN_BORDER);
        float s = navScale * 0.72f;
        GuiRender.textCentered(EDIT_HUD_LABEL, (b[0] + b[2]) / 2f, vcenter(b[1], b[3] - b[1], s), s,
                hover ? TEXT_HI : TEXT_MID, MED);
    }

    /** Screen rect [x1,y1,x2,y2] of the sidebar's small Edit HUD footer button: sized to its label and
     *  centered in the footer band. */
    private float[] sidebarEditBtnRect() {
        float s = navScale * 0.72f;
        float w = GuiRender.textWidth(EDIT_HUD_LABEL, s, MED) + 12f;
        float h = Math.min(sidebarFooterY2 - sidebarFooterY1, BedwarsQolFont.height(s) + 6f);
        float cx = panelX + sidebarW / 2f;
        float cy = (sidebarFooterY1 + sidebarFooterY2) / 2f;
        return new float[]{cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f};
    }

    /** Section icon from the Tabler (MIT) alpha atlas, tinted by {@code color}. See {@link Icons}.
     *  The slight upscale compensates for the icon set's built-in grid padding so it matches the label. */
    private void drawNavIcon(int section, float cx, float cy, float size, int color) {
        int cell = (section >= 0 && section < SECTION_ICON.length) ? SECTION_ICON[section] : section;
        Icons.draw(cell, cx, cy, size * 1.1f, color);
    }

    private void drawCards(int mouseX, int mouseY) {
        ClientSettings cfg = settings();
        int viewTop = cardsViewTop, viewBottom = cardsViewBottom;
        boolean clip = maxScroll > 0;
        float scrollDy = scroll - scrollRender; // shift baked (target) positions to the eased offset
        if (clip) GuiRender.beginScissor(contentX, viewTop, contentRight, viewBottom);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, scrollDy, 0f);
        int padX = clamp(Math.round(rowH * 0.30f), 7, 13);
        float titleH = BedwarsQolFont.height(cardTitleScale);
        float descH = BedwarsQolFont.height(cardDescScale);
        float chevW = clampf(rowH * 0.34f, 6f, 11f);
        for (Card card : cards) {
            if (clip && (card.y + card.h + scrollDy < viewTop || card.y + scrollDy > viewBottom)) continue;
            boolean on = toggleValue(cfg, card.module.kind);
            GuiRender.gradientRoundedRectH(card.x, card.y, card.x + card.w, card.y + card.h, CARD_R,
                    gradHi(themeCardBg), gradLo(themeCardBg)); // left→right sheen on the card fill
            GuiRender.roundedRectOutline(card.x, card.y, card.x + card.w, card.y + card.h, CARD_R, 0.5f,
                    on ? themeCardBorderOn : themeCardBorderOff);

            // title + description block, vertically centered in the header band
            float chevSpace = card.hasSub ? (chevW + padX) : 0f;
            float textMaxW = card.w - 2 * padX - chevSpace;
            float blockH = titleH + 1f + descH;
            float top = card.y + (card.headerH - blockH) / 2f;
            GuiRender.text(ellipsize(card.module.label, cardTitleScale, textMaxW), card.x + padX, top,
                    cardTitleScale, on ? TEXT_HI : TEXT_MID, MED);
            if (card.module.desc != null) {
                GuiRender.text(ellipsize(card.module.desc, cardDescScale, textMaxW), card.x + padX, top + titleH + 1f,
                        cardDescScale, TEXT_LO);
            }
            // expand indicator for modules with sub-options ("+" collapsed / "-" expanded)
            if (card.hasSub) {
                drawExpandIcon(card.x + card.w - padX - chevW / 2f, card.y + card.headerH / 2f, chevW * 0.5f,
                        card.expanded, on ? TEXT_MID : TEXT_LO);
            }
            // sub-options (present only when expanded)
            for (Row r : card.children) {
                drawControl(r, mouseX, mouseY, cfg);
            }
        }
        GlStateManager.popMatrix();
        if (clip) GuiRender.endScissor();
        if (maxScroll > 0) drawScrollbar(viewTop, viewBottom, rowsBlockH);
    }

    /** Minimal expand indicator: a "+" when collapsed, a single "-" bar when expanded (two crisp rects). */
    private void drawExpandIcon(float cx, float cy, float w, boolean expanded, int color) {
        float half = w / 2f;
        float t = clampf(w * 0.07f, 0.55f, 0.75f); // thin AA bars, matching the hairline borders
        GuiRender.line(cx - half, cy, cx + half, cy, t, color);       // horizontal bar (always)
        if (!expanded) {
            GuiRender.line(cx, cy - half, cx, cy + half, t, color);   // vertical bar -> makes a plus
        }
    }

    /** Renders one sub-option control (toggle / stepper / slider) inside an expanded card. */
    private void drawControl(Row row, int mouseX, int mouseY, ClientSettings cfg) {
        // Expanded sub-option text: was 0.54 (0.72 * 0.75), then +10% -> 0.594.
        float lScale = labelScale * 0.594f;
        float vScale = valueScale * 0.594f;
        float labelY = vcenter(row.y, row.h, lScale);
        if (row.hit(mouseX, mouseY)) {
            GuiRender.roundedRect(row.x, row.y + 1, row.x + row.w, row.y + row.h - 1, 4, ROW_HOVER);
        }
        switch (row.def.type) {
            case TOGGLE:
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, TEXT_HI, MED);
                drawCheckbox(row, toggleValue(cfg, row.def.kind), true);
                break;
            case KEYBIND: {
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, TEXT_HI, MED);
                boolean capturing = capturingDummyKey && row.def.kind == K_DUMMY_KEY;
                String txt = capturing ? "Press..." : keyName(cfg.dummySpawnKeyCode);
                int col = capturing ? TEXT_HI : (cfg.dummySpawnKeyCode == Keyboard.KEY_NONE ? TEXT_LO : TEXT_MID);
                drawChip(row, txt, vScale, col, true, capturing, mouseX, mouseY);
                break;
            }
            case ACTION: {
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, TEXT_HI, MED);
                String cap = (row.def.options != null && row.def.options.length > 0) ? row.def.options[0] : "Go";
                drawChip(row, cap, vScale, TEXT_HI, false, false, mouseX, mouseY);
                break;
            }
            case STEPPER: {
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, TEXT_HI, MED);
                drawDropdownTrigger(row, row.def.options[stepperIndex(cfg, row.def.kind)], ddFontScale, true, mouseX, mouseY);
                break;
            }
            case SLIDER: {
                GuiRender.text(row.def.label, row.x + 2, labelY, lScale, TEXT_HI, MED);
                float[] tr = sliderTrack(row);
                float trackX1 = tr[0], trackX2 = tr[1];
                float trackH = clampf(row.h * 0.16f, 3f, 5f);
                float trackY = row.y + row.h / 2f - trackH / 2f;
                float val = sliderValue(cfg, row.def.kind);
                float frac = clampf((val - row.def.min) / (row.def.max - row.def.min), 0f, 1f);
                float handleX = trackX1 + frac * (trackX2 - trackX1);
                GuiRender.roundedRect(trackX1, trackY, trackX2, trackY + trackH, trackH / 2f, TRACK_OFF);
                GuiRender.roundedRect(trackX1, trackY, handleX, trackY + trackH, trackH / 2f, TRACK_ON);
                float knobR = clampf(row.h * 0.18f, 3.5f, 6f);
                float kcy = row.y + row.h / 2f;
                GuiRender.circle(handleX, kcy, knobR + 0.8f, KNOB_RING);
                GuiRender.circle(handleX, kcy, knobR, KNOB_ON);
                if (editingSliderKind == row.def.kind) {
                    String buf = editSliderBuf.toString();
                    float vw = Math.max(GuiRender.textWidth(buf, vScale), GuiRender.textWidth("-0.00", vScale));
                    float vx2 = row.x + row.w;
                    float vx1 = vx2 - vw;
                    float vy = vcenter(row.y, row.h, vScale);
                    GuiRender.roundedRect(vx1 - 3, row.y + 2, vx2 + 1, row.y + row.h - 2, 3, 0x14FFFFFF);
                    GuiRender.text(buf, vx1, vy, vScale, TEXT_HI);
                    if ((caretBlink / 6) % 2 == 0) {
                        float cx = vx1 + GuiRender.textWidth(buf, vScale);
                        GuiRender.rect(cx, vy - 1, cx + 1, vy + BedwarsQolFont.height(vScale), TEXT_HI);
                    }
                } else {
                    String vs = formatSlider(val);
                    GuiRender.text(vs, (row.x + row.w) - GuiRender.textWidth(vs, vScale),
                            vcenter(row.y, row.h, vScale), vScale, TEXT_HI);
                }
                break;
            }
            default:
                break;
        }
    }

    private void drawRows(int mouseX, int mouseY) {
        ClientSettings cfg = settings();
        // The Settings page draws its theme carousel as a fixed band above the (scrolling) rows.
        if (selectedSection == SETTINGS_SECTION) drawThemeCarousel(mouseX, mouseY);
        int viewTop = selectedSection == SETTINGS_SECTION ? flatRowsTop : contentTop, viewBottom = rowsViewBottom;
        boolean clip = maxScroll > 0;
        float scrollDy = scroll - scrollRender; // shift baked (target) positions to the eased offset
        if (clip) GuiRender.beginScissor(contentX, viewTop, contentRight, viewBottom);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, scrollDy, 0f);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (clip && (row.y + row.h + scrollDy < viewTop || row.y + scrollDy > viewBottom)) continue; // cull off-screen
            boolean child = row.def.child;
            // A sub-setting is greyed + non-interactive while its parent toggle is off.
            boolean enabled = !child || toggleValue(cfg, row.def.parentKind);
            // Match the module-card typography, 30% smaller for the Settings page; the label text is then
            // bumped 25% larger and a further 10% (1.25 * 1.10 = 1.375). The stepper dropdown text matches
            // this label scale (so the button text == the setting title to its left); sliders keep vScale.
            float lScale = settingsRowLabelScale;
            float vScale = valueScale * 0.72f * SETTINGS_SCALE;
            int labelColor = enabled ? TEXT_HI : TEXT_LO;
            boolean inView = !clip || (mouseY >= viewTop && mouseY <= viewBottom);
            boolean hover = inView && enabled && row.hit(mouseX, mouseY);
            float labelY = vcenter(row.y, row.h, lScale);

            if (hover && row.def.type != RowType.ACTION) {
                GuiRender.roundedRect(row.x, row.y + 1, row.x + row.w, row.y + row.h - 1, 4, ROW_HOVER);
            }
            // faint separator between rows
            if (i < rows.size() - 1) {
                GuiRender.divider(row.x, row.x + row.w, row.y + row.h - 0.5f, SEPARATOR);
            }

            switch (row.def.type) {
                case TOGGLE:
                    GuiRender.text(row.def.label, row.x + 2, labelY, lScale, labelColor, MED);
                    drawCheckbox(row, toggleValue(cfg, row.def.kind), enabled);
                    break;
                case STEPPER: {
                    GuiRender.text(row.def.label, row.x + 2, labelY, lScale, labelColor, MED);
                    drawDropdownTrigger(row, row.def.options[stepperIndex(cfg, row.def.kind)], settingsRowLabelScale, enabled, mouseX, mouseY);
                    break;
                }
                case SLIDER: {
                    GuiRender.text(row.def.label, row.x + 2, labelY, lScale, labelColor, MED);
                    float[] tr = sliderTrack(row);
                    float trackX1 = tr[0], trackX2 = tr[1];
                    float trackH = clampf(row.h * 0.16f, 3f, 5f);
                    float trackY = row.y + row.h / 2f - trackH / 2f;
                    float val = sliderValue(cfg, row.def.kind);
                    float frac = clampf((val - row.def.min) / (row.def.max - row.def.min), 0f, 1f);
                    float handleX = trackX1 + frac * (trackX2 - trackX1);
                    GuiRender.roundedRect(trackX1, trackY, trackX2, trackY + trackH, trackH / 2f,
                            enabled ? TRACK_OFF : TRACK_DISABLED);
                    if (enabled) {
                        GuiRender.roundedRect(trackX1, trackY, handleX, trackY + trackH, trackH / 2f, TRACK_ON);
                    }
                    float knobR = clampf(row.h * 0.18f, 3.5f, 6f);
                    float kcy = row.y + row.h / 2f;
                    GuiRender.circle(handleX, kcy, knobR + 0.8f, KNOB_RING);
                    GuiRender.circle(handleX, kcy, knobR, enabled ? KNOB_ON : KNOB_DISABLED);
                    String vs = formatSlider(val);
                    GuiRender.text(vs, (row.x + row.w) - GuiRender.textWidth(vs, vScale),
                            vcenter(row.y, row.h, vScale), vScale, enabled ? TEXT_HI : TEXT_LO);
                    break;
                }
            }
        }
        GlStateManager.popMatrix();
        if (clip) GuiRender.endScissor();
        if (maxScroll > 0) drawScrollbar(viewTop, viewBottom, rowsBlockH);
        if (pinnedAction != null) {
            float[] b = bottomButton(pinnedAction.label);
            drawBottomButton(b, pinnedAction.label, GuiRender.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]));
        }
    }

    // ---------------------------------------------------------------- theme carousel (Settings page)

    /** Copies the active preset (clamped against THEMES) into the card-colour fields read by drawCards. */
    private void resolveTheme() {
        int i = settings().moduleTheme;
        ThemeDef t = THEMES[(i >= 0 && i < THEMES.length) ? i : 0];
        themeCardBg = t.bg;
        themeCardBorderOff = t.borderOff;
        themeCardBorderOn = t.borderOn;
    }

    /** Lays out the theme carousel band at the top of the Settings content area and sets {@link #flatRowsTop}
     *  (where the stepper rows begin, just below it). Square swatches are sized from rowH; if the strip is
     *  wider than the content column it scrolls horizontally (its own {@link #themeScroll}), otherwise it's
     *  centred and {@link #themeScrollMax} is 0. */
    private void layoutThemeCarousel() {
        themeNameScale = clampf(labelScale * 0.45f, 0.62f, 0.85f); // small label in the swatch's top-right corner
        // Rectangular swatches: a compact pill height; width is the longer of the (small) name + padding and
        // a floor tied to the height, so swatches read as consistent colour tiles.
        themeSwatchH = clamp(Math.round(rowH * 1.14f), 19, 36); // 20% taller
        float maxNameW = 0f;
        for (ThemeDef t : THEMES) maxNameW = Math.max(maxNameW, GuiRender.textWidth(t.name, themeNameScale, MED));
        int hPad = clamp(Math.round(themeSwatchH * 0.6f), 9, 18);
        themeSwatchW = Math.max(Math.round(maxNameW) + 2 * hPad, Math.round(themeSwatchH * 2.1f));
        themeSwatchGap = clamp(Math.round(themeSwatchH * 0.34f), 4, 10);
        themeHeaderScale = clampf(labelScale * 0.62f, 0.78f, 1.1f);                  // "Themes" heading
        themeHeaderH = Math.round(BedwarsQolFont.height(themeHeaderScale)) + clamp(Math.round(rowH * 0.1f), 2, 5);
        int topPad = themeSwatchTopPad();
        carouselTop = contentTop;
        themeSwatchTop = carouselTop + themeHeaderH + topPad;                        // swatch row sits below the heading
        int bandH = themeHeaderH + topPad + themeSwatchH + topPad;
        carouselBottom = contentTop + bandH;
        flatRowsTop = carouselBottom + clamp(Math.round(rowH * 0.22f), 3, 8);

        int n = THEMES.length;
        int stripW = n * themeSwatchW + (n - 1) * themeSwatchGap;
        int bandW = contentRight - contentX;
        if (stripW <= bandW) {
            themeScrollMax = 0;
            themeStripX0 = contentX + (bandW - stripW) / 2;
        } else {
            themeScrollMax = stripW - bandW;
            themeStripX0 = contentX;
        }
        themeScroll = clamp(themeScroll, 0, themeScrollMax);
    }

    private int themeSwatchTopPad() {
        return clamp(Math.round(rowH * 0.16f), 3, 7);
    }

    /** Screen rect [x1,y1,x2,y2] of swatch {@code i} (accounts for the horizontal scroll offset). */
    private float[] themeSwatchRect(int i) {
        float x1 = themeStripX0 - themeScroll + i * (themeSwatchW + themeSwatchGap);
        float y1 = themeSwatchTop;
        return new float[]{x1, y1, x1 + themeSwatchW, y1 + themeSwatchH};
    }

    /** Draws the rounded-rectangle theme strip: each swatch is a mini module card — the theme's card fill
     *  over an opaque base, framed by its complementary (enabled-border) colour — with the theme name
     *  centred inside. The selected swatch gets a crisp white keyline; hover thickens its frame. */
    private void drawThemeCarousel(int mouseX, int mouseY) {
        int sel = settings().moduleTheme;
        if (sel < 0 || sel >= THEMES.length) sel = 0;
        // Left-aligned "Themes" heading above the swatch strip.
        GuiRender.text("Themes", contentX, vcenter(carouselTop, themeHeaderH, themeHeaderScale),
                themeHeaderScale, TEXT_HI, MED);
        boolean clip = themeScrollMax > 0;
        if (clip) GuiRender.beginScissor(contentX, carouselTop, contentRight, carouselBottom);
        float r = clampf(themeSwatchH * 0.12f, 2f, 3f); // tight rounded corners
        for (int i = 0; i < THEMES.length; i++) {
            ThemeDef t = THEMES[i];
            float[] s = themeSwatchRect(i);
            float x1 = s[0], y1 = s[1], x2 = s[2], y2 = s[3];
            if (clip && (x2 < contentX || x1 > contentRight)) continue; // cull fully off-band swatches
            boolean selected = i == sel;
            boolean hover = GuiRender.inside(mouseX, mouseY, x1, y1, x2, y2);
            GuiRender.roundedRect(x1, y1, x2, y2, r, 0xFF0E0E0E); // opaque base under the translucent fill
            GuiRender.gradientRoundedRectH(x1, y1, x2, y2, r, gradHi(t.bg), gradLo(t.bg)); // theme fill, left→right sheen
            int frame = selected ? PANEL_BORDER : t.borderOn;     // complementary colour, white when selected
            GuiRender.roundedRectOutline(x1, y1, x2, y2, r, selected ? 1.0f : (hover ? 0.8f : 0.6f), frame);
            // Theme name: a small label tucked into the top-right corner (right-aligned), not centred.
            float namePad = clampf(themeSwatchH * 0.14f, 2.5f, 5f);
            String nm = ellipsize(t.name, themeNameScale, (x2 - x1) - 2f * namePad);
            float nameW = GuiRender.textWidth(nm, themeNameScale, MED);
            GuiRender.text(nm, x2 - namePad - nameW, y1 + namePad, themeNameScale,
                    (selected || hover) ? TEXT_HI : TEXT_MID, MED);
        }
        if (clip) GuiRender.endScissor();
        // Hairline separating the carousel band from the rows below.
        GuiRender.divider(contentX, contentRight, flatRowsTop - clamp(Math.round(rowH * 0.11f), 2, 4), SEPARATOR);
    }

    /** Handles a click in the carousel band: selects the swatch under the cursor (saving + re-resolving),
     *  and consumes any click inside the band so it never falls through to the rows. Returns true if handled. */
    private boolean themeCarouselClick(int mouseX, int mouseY, ClientSettings cfg) {
        if (mouseY < carouselTop || mouseY > carouselBottom || mouseX < contentX || mouseX > contentRight) {
            return false;
        }
        for (int i = 0; i < THEMES.length; i++) {
            float[] s = themeSwatchRect(i);
            if (GuiRender.inside(mouseX, mouseY, s[0], s[1], s[2], s[3])) {
                if (cfg.moduleTheme != i) {
                    cfg.moduleTheme = i;
                    resolveTheme();
                    cfg.save();
                }
                playClick();
                return true;
            }
        }
        return true; // inside the band but between swatches — swallow it
    }

    /** Right-aligned checkbox: an empty rounded square (keyline) when off, filled solid white when on.
     *  Simple, elegant, modern — replaces the old pill switch. */
    private void drawCheckbox(Row row, boolean on, boolean enabled) {
        // Small box; vertically centered on the row (== the label's vertical center, since the label is
        // font-centered in the same row height).
        // Expanded-module sub-setting (child) checkboxes are smaller than the base box: two successive
        // 15% reductions at the user's request (× 0.85 × 0.85 = 0.7225).
        float childK = 0.7225f;
        float size = clampf(row.h * (row.def.child ? 0.195f * childK : 0.23f),
                row.def.child ? 4.5f * childK : 5.5f, row.def.child ? 6f * childK : 7.5f);
        float rightPad = controlRightPad(row.h); // inset from the row's right edge (shared with the dropdown)
        float x2 = row.x + row.w - rightPad;
        float x1 = x2 - size;
        float y1 = row.y + (row.h - size) / 2f;
        float y2 = y1 + size;
        float r = clampf(size * 0.14f, 0.8f, 1.4f); // tighter corners
        if (on) {
            GuiRender.roundedRect(x1, y1, x2, y2, r, enabled ? KNOB_ON : KNOB_DISABLED);
        } else {
            GuiRender.roundedRect(x1, y1, x2, y2, r, enabled ? TRACK_OFF : TRACK_DISABLED);
            GuiRender.roundedRectOutline(x1, y1, x2, y2, r, 0.85f, enabled ? CHECKBOX_BORDER : SWITCH_DISABLED_BORDER);
        }
    }

    /** Right-aligned pill button for a KEYBIND / ACTION card child, sized to its text. */
    private float[] chipRect(Row row, String text, float scale) {
        float h = clampf(row.h * 0.62f, 11f, 18f);
        float w = GuiRender.textWidth(text, scale) + 16f;
        if (w < h * 1.8f) w = h * 1.8f; // keep a minimum pill width
        float x2 = row.x + row.w;
        float x1 = x2 - w;
        float y1 = row.y + (row.h - h) / 2f;
        return new float[]{x1, y1, x2, y1 + h};
    }

    /** Draws a chip for a KEYBIND key picker / ACTION button; {@code outline} adds a keyline (used while
     *  capturing a key). The text is centered; the pill brightens on hover or while capturing. */
    private void drawChip(Row row, String text, float scale, int textColor, boolean outline, boolean active,
                          int mouseX, int mouseY) {
        float[] c = chipRect(row, text, scale);
        boolean hover = GuiRender.inside(mouseX, mouseY, c[0], c[1], c[2], c[3]);
        float r = (c[3] - c[1]) * 0.28f;
        GuiRender.roundedRect(c[0], c[1], c[2], c[3], r, (hover || active) ? BTN_HOVER : BTN_BG);
        if (outline) GuiRender.roundedRectOutline(c[0], c[1], c[2], c[3], r, 0.75f, active ? PANEL_BORDER : BTN_BORDER);
        GuiRender.textCentered(text, (c[0] + c[2]) / 2f, vcenter(c[1], c[3] - c[1], scale), scale, textColor, MED);
    }

    /** Centered button in the shared pinned bottom band, sized to its label. */
    private float[] bottomButton(String label) {
        int contentW = contentRight - contentX;
        int w = Math.min(contentW, Math.round(GuiRender.textWidth(label, valueScale * 0.85f)) + 22);
        float x1 = contentX + (contentW - w) / 2f;
        return new float[]{x1, bottomBtnY1, x1 + w, bottomBtnY2};
    }

    private void drawBottomButton(float[] b, String label, boolean hover) {
        float bs = valueScale * 0.85f; // scaled-down button label
        GuiRender.roundedRect(b[0], b[1], b[2], b[3], 4, hover ? BTN_HOVER : BTN_BG);
        GuiRender.textCentered(label, (b[0] + b[2]) / 2f, vcenter(b[1], b[3] - b[1], bs), bs, TEXT_HI, MED);
    }

    /** The dropdown trigger button rect [x1,y1,x2,y2] for a stepper row. Right-aligned to the row and
     *  given a uniform width per page (widest option + padding + caret) so every dropdown lines up. */
    private float[] dropdownRect(Row row) {
        float h = clampf(row.h * 0.50f, 10f, 15f);
        float padX = ddPadX(row.h);
        float caretW = clampf(h * 0.24f, 2f, 3.2f);
        // Compact: edge pad + value column (sized to the widest option) + small gap + caret + edge pad.
        float w = pageStepperTextW + 2f * padX + padX * 0.6f + caretW;
        float x2 = row.x + row.w - controlRightPad(row.h); // right edge lines up with the checkbox column
        float x1 = x2 - w;
        float y1 = row.y + (row.h - h) / 2f;
        return new float[]{x1, y1, x2, y1 + h};
    }

    private float ddPadX(float rowHeight) {
        return clampf(rowHeight * 0.16f, 4f, 7f);
    }

    /** Inset from the row's right edge shared by the checkbox and the dropdown trigger, so their right
     *  edges line up down the column. */
    private float controlRightPad(float rowHeight) {
        return clampf(rowHeight * 0.32f, 7f, 13f);
    }

    /** Draws a stepper's dropdown trigger: a chip showing the current value with a caret affordance
     *  (down when closed, up when open). Brightens on hover; shows a bright keyline while open. */
    private void drawDropdownTrigger(Row row, String value, float scale, boolean enabled, int mouseX, int mouseY) {
        float[] c = dropdownRect(row);
        boolean open = openDropdownKind == row.def.kind;
        boolean hover = enabled && GuiRender.inside(mouseX, mouseY, c[0], c[1], c[2], c[3]);
        float r = (c[3] - c[1]) * 0.13f; // tight corners
        GuiRender.roundedRect(c[0], c[1], c[2], c[3], r, (hover || open) ? BTN_HOVER : BTN_BG);
        GuiRender.roundedRectOutline(c[0], c[1], c[2], c[3], r, 0.75f, open ? PANEL_BORDER : BTN_BORDER);
        float padX = ddPadX(row.h);
        float caretW = clampf((c[3] - c[1]) * 0.24f, 2f, 3.2f);
        float caretLeft = c[2] - padX - caretW;
        // Value text centered between the button's left edge and the down caret.
        GuiRender.textCentered(value, (c[0] + caretLeft) / 2f, vcenter(c[1], c[3] - c[1], scale), scale,
                enabled ? TEXT_HI : TEXT_LO, MED);
        drawCaret(caretLeft + caretW / 2f, (c[1] + c[3]) / 2f, caretW / 2f, caretW * 0.34f, open, enabled ? TEXT_MID : TEXT_LO);
    }

    /** A small "v" (down) / "^" (up) caret, drawn as two AA hairlines. */
    private void drawCaret(float cx, float cy, float hw, float hh, boolean up, int color) {
        if (up) {
            GuiRender.line(cx - hw, cy + hh, cx, cy - hh, 0.85f, color);
            GuiRender.line(cx, cy - hh, cx + hw, cy + hh, 0.85f, color);
        } else {
            GuiRender.line(cx - hw, cy - hh, cx, cy + hh, 0.85f, color);
            GuiRender.line(cx, cy + hh, cx + hw, cy - hh, 0.85f, color);
        }
    }

    /** Option-list rect [x1,y1,x2,y2,itemH] for the open dropdown: dropped below the anchor, flipped
     *  above (or clamped within the panel) when it would overflow the bottom edge. */
    private float[] dropdownListGeom() {
        int n = ddOptions.length;
        float itemH = clampf(ddY2 - ddY1, 10f, 16f);
        float padY = 2f;
        float maxOpt = 0f;
        for (String o : ddOptions) maxOpt = Math.max(maxOpt, GuiRender.textWidth(o, ddScale, MED));
        // As compact as the content allows: left pad + widest option + right pad.
        float w = maxOpt + ddPad * 2f;
        float x2 = ddX2;             // right-aligned to the trigger
        float x1 = x2 - w;
        float totalH = n * itemH + padY * 2f;
        float y1 = ddY2 + 2f;        // below the trigger by default
        float limitTop = contentTop; // never flip a menu up into the header band
        float limitBottom = panelY + panelH - pad;
        if (y1 + totalH > limitBottom) {
            float above = ddY1 - 2f - totalH;
            y1 = above >= limitTop ? above : Math.max(limitTop, limitBottom - totalH);
        }
        return new float[]{x1, y1, x2, y1 + totalH, itemH};
    }

    /** Draws the open dropdown's option list on top of everything (called last in drawScreen). */
    private void drawDropdownPopup(int mouseX, int mouseY) {
        if (openDropdownKind == 0 || ddOptions == null) return;
        float[] g = dropdownListGeom();
        float x1 = g[0], y1 = g[1], x2 = g[2], y2 = g[3], itemH = g[4];
        float padY = 2f, r = 2.5f; // tight corners, matching the trigger
        GuiRender.roundedRect(x1, y1, x2, y2, r, DD_BG);
        int sel = stepperIndex(settings(), openDropdownKind);
        // Fill each highlighted row up to the keyline's inner SOLID edge (geom - 0.5 = stroke 0.75
        // minus its 0.25 AA half), with a concentric radius, then draw the keyline OPAQUE and ON TOP
        // (below). The keyline is then the single crisp edge for every row: it covers each fill's
        // outer AA uniformly, so no row — rounded or square — sticks past it, and the fill→edge
        // transition is hidden, so no sub-pixel seam shows at any GUI scale. Filling just to the solid
        // edge (rather than the geometric edge) means the fill never spills its own AA past the
        // keyline; the keyline on top then guarantees no gap below it.
        float inset = 0.5f;
        float rr = r - inset; // concentric with the menu's corner arcs (shared centers)
        int last = ddOptions.length - 1;
        int n = ddOptions.length;
        for (int i = 0; i < n; i++) {
            float iy1 = y1 + padY + i * itemH;
            float iy2 = iy1 + itemH;
            boolean hover = GuiRender.inside(mouseX, mouseY, x1, iy1, x2, iy2);
            boolean selected = i == sel;
            if (hover || selected) {
                // Inset only the sides that touch the keyline; interior row boundaries stay put.
                float hTop = i == 0 ? y1 + inset : iy1;
                float hBot = i == last ? y2 - inset : iy2;
                float rTL = 0f, rTR = 0f, rBR = 0f, rBL = 0f;
                if (n == 1) { rTL = rTR = rBR = rBL = rr; } // sole row: all four corners
                else if (i == 0) { rTL = rTR = rr; }        // first: top two
                else if (i == last) { rBL = rBR = rr; }     // last: bottom two
                GuiRender.roundedRect(x1 + inset, hTop, x2 - inset, hBot, rTL, rTR, rBR, rBL,
                        hover ? DD_ITEM_HOVER : DD_ITEM_SELECTED);
            }
            GuiRender.text(ddOptions[i], x1 + ddPad, vcenter(iy1, itemH, ddScale), ddScale,
                    (selected || hover) ? TEXT_HI : TEXT_MID, MED);
        }
        // Keyline LAST, opaque, on top of the fills — the single source of truth for the menu edge.
        GuiRender.roundedRectOutline(x1, y1, x2, y2, r, 0.75f, DD_BORDER);
    }

    /** Opens (or toggles closed) the dropdown for a stepper row, anchoring it to the trigger button. */
    private void openDropdown(Row row, float scale) {
        if (openDropdownKind == row.def.kind) { closeDropdown(); return; }
        float[] c = dropdownRect(row);
        openDropdownKind = row.def.kind;
        ddOptions = row.def.options;
        ddX1 = c[0]; ddY1 = c[1]; ddX2 = c[2]; ddY2 = c[3];
        ddScale = scale;
        ddPad = ddPadX(row.h);
        playClick();
    }

    private void closeDropdown() {
        openDropdownKind = 0;
        ddOptions = null;
    }

    private static float vcenter(float top, float boxH, float scale) {
        return top + (boxH - BedwarsQolFont.height(scale)) / 2f;
    }

    /** Optically centres text in [top, top+boxH] by its visible cap bounds (cap-top → baseline) rather
     *  than the full line box, so prominent centred text (the header title/button) doesn't read slightly
     *  high from the line box's unused descender space. */
    private static float vcenterOptical(float top, float boxH, float scale, BedwarsQolFont.Weight weight) {
        return top + (boxH - BedwarsQolFont.capHeight(scale, weight)) / 2f - BedwarsQolFont.capTop(scale, weight);
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        // Remap into the fixed-"Large" virtual space the panel geometry lives in (identity at guiScale 3).
        mouseX = Math.round(mouseX / uiScale);
        mouseY = Math.round(mouseY / uiScale);
        commitSliderEdit();                       // any click finalizes an open numeric field (Task 2)
        ClientSettings cfg = settings();

        // An open dropdown is modal: the next click either picks an option or dismisses the menu.
        if (openDropdownKind != 0) {
            if (mouseButton == 0) {
                float[] g = dropdownListGeom();
                if (GuiRender.inside(mouseX, mouseY, g[0], g[1], g[2], g[3])) {
                    int idx = clamp((int) Math.floor((mouseY - (g[1] + 3f)) / g[4]), 0, ddOptions.length - 1);
                    int kind = openDropdownKind;
                    setStepperIndex(cfg, kind, idx);
                    closeDropdown();
                    playClick();
                    cfg.save();
                    if (kind == K_GUISIZE) initGui(); // resize the panel live
                    return;
                }
            }
            closeDropdown();                       // clicked the trigger again or outside -> dismiss
            return;
        }

        if (mouseButton == 1) {                    // right-click: expand/collapse a card (search or section)
            if (searchActive() || SECTIONS[selectedSection].cards) {
                rightClickCards(mouseX, mouseY);
            }
            return;
        }
        if (mouseButton != 0) return;

        // Header search bar (persistent): click to focus (and clear via the x); any other click blurs it.
        boolean inSearchBar = GuiRender.inside(mouseX, mouseY, searchBarX1, searchBarY1, searchBarX2, searchBarY2);
        searchFocused = inSearchBar;
        if (inSearchBar) {
            if (searchQuery.length() > 0
                    && GuiRender.inside(mouseX, mouseY, searchClearX1, searchBarY1, searchClearX2, searchBarY2)) {
                searchQuery.setLength(0);
                resetScroll();
                layoutContent();
            }
            playClick();
            return;
        }

        // Edit HUD button pinned to the bottom of the sidebar — handle before section routing.
        float[] hb = sidebarEditBtnRect();
        if (GuiRender.inside(mouseX, mouseY, hb[0], hb[1], hb[2], hb[3])) {
            stopEditing();
            playClick();
            cfg.save();
            mc.displayGuiScreen(new EditHudGui());
            return;
        }

        // Scrollbar: grab the thumb to drag it, or click the track above/below to page.
        float[] sb = scrollbarGeom();
        if (sb != null && mouseX >= sb[0] - 3 && mouseX <= sb[1] + 1 && mouseY >= sb[2] && mouseY <= sb[3]) {
            float thumbY = sb[2] + sb[5] * clampf(scrollRender / maxScroll, 0f, 1f);
            if (mouseY >= thumbY && mouseY <= thumbY + sb[4]) {
                draggingThumb = true;
                thumbGrabDy = mouseY - thumbY;
            } else {
                int page = Math.max(rowH, (int) ((sb[3] - sb[2]) * 0.9f));
                scroll = clamp(scroll + (mouseY < thumbY ? -page : page), 0, maxScroll);
                layoutContent(); // search & sections bake card y
                playClick();
            }
            return;
        }

        for (int i = 0; i < SECTIONS.length; i++) {
            int y = navStartY + i * navItemH;
            if (GuiRender.inside(mouseX, mouseY, panelX, y, panelX + sidebarW, y + navItemH)) {
                if (i != selectedSection || searchActive()) {
                    stopEditing();
                    selectedSection = i;
                    scroll = 0;
                    scrollRender = 0f;
                    scrollAccum = 0f;
                    themeScroll = 0;          // each visit to the Settings page starts the carousel at the left
                    searchQuery.setLength(0); // leaving search: clear the query so the section shows
                    layoutContent();
                    playClick();
                }
                return;
            }
        }

        // Search results are real module cards: same click model as a section.
        if (searchActive()) {
            cardsClick(mouseX, mouseY, cfg);
            return;
        }

        if (SECTIONS[selectedSection].cards) {
            cardsClick(mouseX, mouseY, cfg);
            return;
        }

        // Settings page: the theme carousel sits above the rows and consumes clicks in its band.
        if (selectedSection == SETTINGS_SECTION && themeCarouselClick(mouseX, mouseY, cfg)) return;

        if (pinnedAction != null) {
            float[] b = bottomButton(pinnedAction.label);
            if (GuiRender.inside(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
                if (pinnedAction.kind == K_EDITHUD) {
                    stopEditing();
                    playClick();
                    cfg.save();
                    mc.displayGuiScreen(new EditHudGui());
                }
                return;
            }
        }
        // Ignore clicks that land outside the scrollable viewport (on a clipped-off row).
        if (maxScroll > 0 && (mouseY < contentTop || mouseY > contentTop + contentH)) return;

        for (Row row : rows) {
            if (!row.hit(mouseX, mouseY)) continue;
            if (row.def.child && !toggleValue(cfg, row.def.parentKind)) return; // disabled sub-setting
            switch (row.def.type) {
                case TOGGLE:
                    toggle(cfg, row.def.kind);
                    playClick();
                    cfg.save();
                    break;
                case STEPPER:
                    openDropdown(row, settingsRowLabelScale); // open the Settings dropdown (text matches the row label)
                    break;
                case SLIDER: {
                    float[] tr = sliderTrack(row);
                    if (mouseX >= tr[0] - 4) { // grab on the track / value region, not the label
                        draggingSliderKind = row.def.kind;
                        dragTrackX1 = tr[0];
                        dragTrackX2 = tr[1];
                        dragMin = row.def.min;
                        dragMax = row.def.max;
                        applySliderDrag(cfg, mouseX);
                        playClick();
                    }
                    break;
                }
                default:
                    break;
            }
            return;
        }
    }

    private void cardsClick(int mouseX, int mouseY, ClientSettings cfg) {
        if (maxScroll > 0 && (mouseY < cardsViewTop || mouseY > cardsViewBottom)) return;
        int padX = clamp(Math.round(rowH * 0.30f), 7, 13);
        float chevW = clampf(rowH * 0.34f, 6f, 11f);
        for (Card card : cards) {
            if (GuiRender.inside(mouseX, mouseY, card.x, card.y, card.x + card.w, card.y + card.headerH)) {
                float chevHitX1 = card.x + card.w - (chevW + 2 * padX);
                if (card.hasSub && mouseX >= chevHitX1) {
                    // The arrow (right edge of the header) is the ONLY way to expand/collapse.
                    Integer k = card.module.kind;
                    if (expandedModules.contains(k)) expandedModules.remove(k);
                    else expandedModules.add(k);
                    playClick();
                    relayoutCards();
                } else {
                    // Clicking the card body turns the module on/off — it does not expand.
                    toggle(cfg, card.module.kind);
                    playClick();
                    cfg.save();
                }
                return;
            }
            for (Row r : card.children) {
                if (!r.hit(mouseX, mouseY)) continue;
                controlClick(r, mouseX, mouseY, cfg);
                return;
            }
        }
    }

    private void rightClickCards(int mouseX, int mouseY) {
        if (maxScroll > 0 && (mouseY < cardsViewTop || mouseY > cardsViewBottom)) return;
        for (Card card : cards) {
            if (!card.hasSub) continue;
            if (GuiRender.inside(mouseX, mouseY, card.x, card.y, card.x + card.w, card.y + card.headerH)) {
                Integer k = card.module.kind;
                if (expandedModules.contains(k)) expandedModules.remove(k);
                else expandedModules.add(k);
                playClick();
                relayoutCards();
                return;
            }
        }
    }

    /** Click handling for a single sub-option control inside an expanded card. */
    private void controlClick(Row row, int mouseX, int mouseY, ClientSettings cfg) {
        switch (row.def.type) {
            case TOGGLE:
                toggle(cfg, row.def.kind);
                playClick();
                cfg.save();
                break;
            case KEYBIND: {
                String txt = capturingDummyKey ? "Press..." : keyName(cfg.dummySpawnKeyCode);
                float[] c = chipRect(row, txt, valueScale * 0.9f);
                if (GuiRender.inside(mouseX, mouseY, c[0], c[1], c[2], c[3])) {
                    capturingDummyKey = !capturingDummyKey; // click the chip to start/cancel capture
                    playClick();
                }
                break;
            }
            case ACTION: {
                String cap = (row.def.options != null && row.def.options.length > 0) ? row.def.options[0] : "Go";
                float[] c = chipRect(row, cap, valueScale * 0.9f);
                if (GuiRender.inside(mouseX, mouseY, c[0], c[1], c[2], c[3])) {
                    if (row.def.kind == K_DUMMY_CLEAR) TestDummyHandler.clearAll();
                    playClick();
                }
                break;
            }
            case STEPPER:
                openDropdown(row, ddFontScale); // click the row -> open the value dropdown
                break;
            case SLIDER: {
                float[] vr = sliderValueRect(row);
                if (GuiRender.inside(mouseX, mouseY, vr[0] - 3, vr[1] - 2, vr[2] + 2, vr[3] + 2)) {
                    startSliderEdit(row, cfg);
                    playClick();
                    return;
                }
                float[] tr = sliderTrack(row);
                if (mouseX >= tr[0] - 4 && mouseX < vr[0] - 3) {
                    draggingSliderKind = row.def.kind;
                    dragTrackX1 = tr[0];
                    dragTrackX2 = tr[1];
                    dragMin = row.def.min;
                    dragMax = row.def.max;
                    applySliderDrag(cfg, mouseX);
                    playClick();
                }
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onGuiClosed() {
        stopEditing();
        Keyboard.enableRepeatEvents(false);
        GuiBlur.end(); // stop the blur and free its scratch framebuffers
        settings().save();
    }

    @Override
    public void updateScreen() {
        caretBlink++;
    }

    private static final float SCROLL_TAU = 0.10f; // scroll easing time-constant (s); smaller = snappier

    /** Eases the rendered scroll offset toward the integer target, frame-rate-independently. Runs once
     *  per frame from {@link #drawScreen}; clicks/layout keep using the integer target so they stay stable.
     *  Easing runs on every platform, macOS included: LWJGL2 collapses the OS's high-precision trackpad
     *  and momentum deltas into a coarse integer {@code getDWheel()} before we ever see them, so rendering
     *  each step 1:1 visibly stutters. Smoothing here interpolates across those integer jumps. */
    private void advanceScroll() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        // Not scrollable, or actively dragging the thumb (which tracks the cursor 1:1) -> pin to target.
        if (maxScroll <= 0 || draggingThumb) { scrollRender = scroll; return; }
        if (dt <= 0f) { scrollRender = scroll; return; }
        if (dt > 0.1f) dt = 0.1f; // clamp spikes (alt-tab / GC) so it never teleports
        scrollRender += (scroll - scrollRender) * (1f - (float) Math.exp(-dt / SCROLL_TAU));
        if (Math.abs(scroll - scrollRender) < 0.5f) scrollRender = scroll; // snap; kills the sub-pixel tail
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dwheel = Mouse.getEventDWheel();
        if (dwheel == 0) return;
        if (openDropdownKind != 0) return; // an open dropdown is modal: swallow the wheel, don't scroll
        // Theme carousel: while the cursor is over its band, the wheel pans the swatch strip horizontally
        // (independent of — and even when there is no — vertical row scroll).
        if (!searchActive() && selectedSection == SETTINGS_SECTION && themeScrollMax > 0
                && lastMouseY >= carouselTop && lastMouseY <= carouselBottom
                && lastMouseX >= contentX && lastMouseX <= contentRight) {
            int step = Math.max(themeSwatchW / 2, 8);
            themeScroll = clamp(themeScroll + (dwheel > 0 ? -step : step), 0, themeScrollMax);
            return;
        }
        if (maxScroll <= 0) return;
        // Proportional, fine-grained wheel mapping. macOS LWJGL2 reports (int)(deltaY*120): only ~10-12
        // per slow notch (more when spun fast, or many small momentum deltas from a trackpad); Windows
        // ~120. Using the MAGNITUDE (not just the sign) makes a trackpad scroll finely and a wheel scroll
        // in sensible steps; the fractional accumulator preserves sub-pixel deltas through the int target,
        // and the per-frame ease (advanceScroll) smooths the coarse result on every platform.
        int rh = Math.max(8, rowH);
        float delta = dwheel / 120f * (rh * 1.1f);     // ~1.1 rows per full notch; sub-notch deltas -> finer
        delta = clampf(delta, -rh * 2.5f, rh * 2.5f);  // clamp a fast flick so the target never teleports
        scrollAccum -= delta;                          // wheel up (dwheel>0) scrolls content up
        int whole = (int) scrollAccum;                 // commit whole pixels, carry the fraction
        if (whole != 0) {
            scrollAccum -= whole;
            scroll = clamp(scroll + whole, 0, maxScroll);
            // Cards (sections & search) and the flat rows bake y from `scroll` -> re-layout.
            layoutContent();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        mouseX = Math.round(mouseX / uiScale);
        mouseY = Math.round(mouseY / uiScale);
        if (clickedMouseButton == 0 && draggingThumb) {
            float[] sb = scrollbarGeom();
            if (sb != null && sb[5] > 0f) {
                float newTop = clampf(mouseY - sb[2] - thumbGrabDy, 0f, sb[5]);
                scroll = clamp(Math.round(newTop / sb[5] * maxScroll), 0, maxScroll);
                scrollRender = scroll; // track the cursor directly while dragging (no lag)
                layoutContent(); // search & sections bake card y
            }
            return;
        }
        if (clickedMouseButton == 0 && draggingSliderKind != 0) {
            applySliderDrag(settings(), mouseX);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        draggingThumb = false;
        if (draggingSliderKind != 0) {
            draggingSliderKind = 0;
            settings().save();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (openDropdownKind != 0) {               // an open dropdown swallows keys; ESC closes it
            if (keyCode == Keyboard.KEY_ESCAPE) { closeDropdown(); playClick(); }
            return;
        }
        if (editingSliderKind != 0) {
            sliderEditKey(typedChar, keyCode);
            return;
        }
        if (searchFocused) {
            searchKey(typedChar, keyCode);
            return;
        }
        // While capturing the Debug spawn key, the next keypress binds it (ESC cancels) — swallow either way.
        if (capturingDummyKey) {
            if (keyCode != Keyboard.KEY_ESCAPE) {
                settings().dummySpawnKeyCode = keyCode;
                settings().save();
                playClick();
            }
            capturingDummyKey = false;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---------------------------------------------------------------- config glue

    private static boolean toggleValue(ClientSettings cfg, int kind) {
        switch (kind) {
            case K_POTION: return cfg.potionStatusEnabled;
            case K_ARMOR: return cfg.armorTypeEnabled;
            case K_INFO: return cfg.infoEnabled;
            case K_INVENTORY: return cfg.inventoryHudEnabled;
            case K_GENTIMERS: return cfg.genTimersEnabled;
            case K_STATS: return cfg.playerStats;
            case K_NAMETAG: return cfg.playerStatsNametag;
            case K_TAB: return cfg.playerStatsTab;
            case K_CHATHOVER: return cfg.playerStatsChatHover;
            case K_LEVEL: return cfg.playerStatsShowLevel;
            case K_RANK: return cfg.playerStatsShowRank;
            case K_SWEATREPORT: return cfg.statsSweatReport;
            case K_AUTOGG: return cfg.autoGg;
            case K_TAB_HEADERFOOTER: return cfg.tabHideHeaderFooter;
            case K_TAB_PING: return cfg.tabNumericPing;
            case K_KEYSTROKES: return cfg.keystrokesEnabled;
            case K_POTION_INGAME: return cfg.potionInGameOnly;
            case K_ARMOR_INGAME: return cfg.armorInGameOnly;
            case K_INVENTORY_INGAME: return cfg.inventoryInGameOnly;
            case K_KEYSTROKES_INGAME: return cfg.keystrokesInGameOnly;
            case K_POTION_BG: return cfg.potionBackgroundEnabled;
            case K_ARMOR_BG: return cfg.armorBackgroundEnabled;
            case K_INFO_BG: return cfg.infoBackgroundEnabled;
            case K_INVENTORY_BG: return cfg.inventoryBackgroundEnabled;
            case K_GENTIMERS_BG: return cfg.genTimersBackgroundEnabled;
            case K_KEYSTROKES_BG: return cfg.keystrokesBackgroundEnabled;
            case K_BLOCKOVERLAY: return cfg.blockOverlayEnabled;
            case K_SEETHROUGH: return cfg.blockOverlaySeeThrough;
            case K_HANDPOS: return cfg.handPositionEnabled;
            case K_TNTFUSE: return cfg.tntFuseEnabled;
            case K_SUPPRESSESC: return cfg.suppressEscMenu;
            case K_DUMMY: return cfg.dummyEnabled;
            default: return false;
        }
    }

    private static void toggle(ClientSettings cfg, int kind) {
        switch (kind) {
            case K_POTION: cfg.potionStatusEnabled = !cfg.potionStatusEnabled; break;
            case K_ARMOR: cfg.armorTypeEnabled = !cfg.armorTypeEnabled; break;
            case K_INFO: cfg.infoEnabled = !cfg.infoEnabled; break;
            case K_INVENTORY: cfg.inventoryHudEnabled = !cfg.inventoryHudEnabled; break;
            case K_GENTIMERS: cfg.genTimersEnabled = !cfg.genTimersEnabled; break;
            case K_STATS: cfg.playerStats = !cfg.playerStats; break;
            case K_NAMETAG: cfg.playerStatsNametag = !cfg.playerStatsNametag; break;
            case K_TAB: cfg.playerStatsTab = !cfg.playerStatsTab; break;
            case K_CHATHOVER: cfg.playerStatsChatHover = !cfg.playerStatsChatHover; break;
            case K_LEVEL: cfg.playerStatsShowLevel = !cfg.playerStatsShowLevel; break;
            case K_RANK: cfg.playerStatsShowRank = !cfg.playerStatsShowRank; break;
            case K_SWEATREPORT: cfg.statsSweatReport = !cfg.statsSweatReport; break;
            case K_AUTOGG: cfg.autoGg = !cfg.autoGg; break;
            case K_TAB_HEADERFOOTER: cfg.tabHideHeaderFooter = !cfg.tabHideHeaderFooter; break;
            case K_TAB_PING: cfg.tabNumericPing = !cfg.tabNumericPing; break;
            case K_KEYSTROKES: cfg.keystrokesEnabled = !cfg.keystrokesEnabled; break;
            case K_POTION_INGAME: cfg.potionInGameOnly = !cfg.potionInGameOnly; break;
            case K_ARMOR_INGAME: cfg.armorInGameOnly = !cfg.armorInGameOnly; break;
            case K_INVENTORY_INGAME: cfg.inventoryInGameOnly = !cfg.inventoryInGameOnly; break;
            case K_KEYSTROKES_INGAME: cfg.keystrokesInGameOnly = !cfg.keystrokesInGameOnly; break;
            case K_POTION_BG: cfg.potionBackgroundEnabled = !cfg.potionBackgroundEnabled; break;
            case K_ARMOR_BG: cfg.armorBackgroundEnabled = !cfg.armorBackgroundEnabled; break;
            case K_INFO_BG: cfg.infoBackgroundEnabled = !cfg.infoBackgroundEnabled; break;
            case K_INVENTORY_BG: cfg.inventoryBackgroundEnabled = !cfg.inventoryBackgroundEnabled; break;
            case K_GENTIMERS_BG: cfg.genTimersBackgroundEnabled = !cfg.genTimersBackgroundEnabled; break;
            case K_KEYSTROKES_BG: cfg.keystrokesBackgroundEnabled = !cfg.keystrokesBackgroundEnabled; break;
            case K_BLOCKOVERLAY: cfg.blockOverlayEnabled = !cfg.blockOverlayEnabled; break;
            case K_SEETHROUGH: cfg.blockOverlaySeeThrough = !cfg.blockOverlaySeeThrough; break;
            case K_HANDPOS: cfg.handPositionEnabled = !cfg.handPositionEnabled; break;
            case K_TNTFUSE: cfg.tntFuseEnabled = !cfg.tntFuseEnabled; break;
            case K_SUPPRESSESC: cfg.suppressEscMenu = !cfg.suppressEscMenu; break;
            case K_DUMMY: cfg.dummyEnabled = !cfg.dummyEnabled; break;
            default: break;
        }
    }

    private static int stepperIndex(ClientSettings cfg, int kind) {
        if (kind == K_GUISIZE) return cfg.guiSize;
        if (kind == K_HUDSIZE) return cfg.defaultTextSize;
        if (kind == K_OVERLAYSTYLE) return cfg.blockOverlayStyle;
        if (kind == K_OVERLAYCOLOR) return overlayColorIndex(cfg.blockOverlayColor);
        if (kind == K_OVERLAYOPACITY) return overlayOpacityIndex(cfg.blockOverlayColor);
        if (kind == K_TNTRADIUS) return tntRadiusIndex(cfg.tntFuseRadius);
        if (kind == K_SCOREBOARD_SIZE) return cfg.scoreboardSize;
        if (kind == K_STYLEDTAB_SIZE) return cfg.styledTabListSize;
        if (kind == K_HUDFONT) return cfg.hudFont;
        return cfg.hudDisplayMode;
    }

    private static int tntRadiusIndex(int radius) {
        int best = 0;
        for (int i = 1; i < TNT_RADIUS_VALUES.length; i++) {
            if (Math.abs(TNT_RADIUS_VALUES[i] - radius) < Math.abs(TNT_RADIUS_VALUES[best] - radius)) best = i;
        }
        return best;
    }

    private static int overlayColorIndex(int argb) {
        int rgb = argb & 0xFFFFFF;
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < OVERLAY_COLOR_RGB.length; i++) {
            int d = colorDist(rgb, OVERLAY_COLOR_RGB[i]);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static int overlayOpacityIndex(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int best = 0;
        for (int i = 1; i < OVERLAY_ALPHA.length; i++) {
            if (Math.abs(OVERLAY_ALPHA[i] - a) < Math.abs(OVERLAY_ALPHA[best] - a)) best = i;
        }
        return best;
    }

    private static int colorDist(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    /** Sets a stepper to a specific option index (chosen from the dropdown menu). */
    private static void setStepperIndex(ClientSettings cfg, int kind, int idx) {
        if (kind == K_GUISIZE) {
            cfg.guiSize = idx;
        } else if (kind == K_HUDSIZE) {
            cfg.defaultTextSize = idx;
            cfg.applyDefaultTextSize();
        } else if (kind == K_DISPLAY) {
            cfg.hudDisplayMode = idx;
        } else if (kind == K_OVERLAYSTYLE) {
            cfg.blockOverlayStyle = idx;
        } else if (kind == K_OVERLAYCOLOR) {
            cfg.blockOverlayColor = (cfg.blockOverlayColor & 0xFF000000) | OVERLAY_COLOR_RGB[idx];
        } else if (kind == K_OVERLAYOPACITY) {
            cfg.blockOverlayColor = (cfg.blockOverlayColor & 0x00FFFFFF) | (OVERLAY_ALPHA[idx] << 24);
        } else if (kind == K_TNTRADIUS) {
            cfg.tntFuseRadius = TNT_RADIUS_VALUES[idx];
        } else if (kind == K_SCOREBOARD_SIZE) {
            cfg.scoreboardSize = idx;
        } else if (kind == K_STYLEDTAB_SIZE) {
            cfg.styledTabListSize = idx;
        } else if (kind == K_HUDFONT) {
            cfg.hudFont = idx;
        }
    }

    // ---- sliders (continuous values, e.g. hand position X/Y/Z) ----

    private static float sliderValue(ClientSettings cfg, int kind) {
        switch (kind) {
            case K_HANDX: return cfg.handPosX;
            case K_HANDY: return cfg.handPosY;
            case K_HANDZ: return cfg.handPosZ;
            case K_HANDSCALE: return cfg.handScale;
            default: return 0f;
        }
    }

    private static void setSlider(ClientSettings cfg, int kind, float val) {
        switch (kind) {
            case K_HANDX: cfg.handPosX = val; break;
            case K_HANDY: cfg.handPosY = val; break;
            case K_HANDZ: cfg.handPosZ = val; break;
            case K_HANDSCALE: cfg.handScale = val; break;
            default: break;
        }
    }

    /** Track [x1, x2] for a slider row, sized so it never shifts as the value (and its width) changes. */
    private float[] sliderTrack(Row row) {
        float vScale = row.def.child ? valueScale * 0.9f : valueScale;
        float vw = GuiRender.textWidth("-0.00", vScale);
        float trackW = clampf(row.w * 0.42f, 44f, 96f);
        float trackX2 = (row.x + row.w) - vw - clampf(row.h * 0.3f, 6f, 12f);
        return new float[]{trackX2 - trackW, trackX2};
    }

    private void applySliderDrag(ClientSettings cfg, int mouseX) {
        float frac = clampf((mouseX - dragTrackX1) / Math.max(1f, dragTrackX2 - dragTrackX1), 0f, 1f);
        float val = dragMin + frac * (dragMax - dragMin);
        val = Math.round(val * 100f) / 100f; // snap to 0.01
        setSlider(cfg, draggingSliderKind, val);
    }

    private static String formatSlider(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    /** Screen rect [x1,y1,x2,y2] of a slider's right-aligned numeric value (the click-to-type hot zone). */
    private float[] sliderValueRect(Row row) {
        float vScale = row.def.child ? valueScale * 0.9f : valueScale;
        String vs = editingSliderKind == row.def.kind ? editSliderBuf.toString()
                : formatSlider(sliderValue(settings(), row.def.kind));
        float w = Math.max(GuiRender.textWidth(vs, vScale), GuiRender.textWidth("-0.00", vScale));
        float x2 = row.x + row.w;
        float x1 = x2 - w;
        float y1 = vcenter(row.y, row.h, vScale);
        float y2 = y1 + BedwarsQolFont.height(vScale);
        return new float[]{x1, y1, x2, y2};
    }

    private void startSliderEdit(Row row, ClientSettings cfg) {
        commitSliderEdit();
        draggingSliderKind = 0;
        editingSliderKind = row.def.kind;
        editSliderMin = row.def.min;
        editSliderMax = row.def.max;
        editSliderBuf.setLength(0);
        editSliderBuf.append(formatSlider(sliderValue(cfg, row.def.kind)));
        caretBlink = 0;
    }

    private void commitSliderEdit() {
        if (editingSliderKind == 0) return;
        int kind = editingSliderKind;
        editingSliderKind = 0;
        float val;
        try {
            val = Float.parseFloat(editSliderBuf.toString().trim());
        } catch (NumberFormatException e) {
            return; // unparseable -> keep prior value, just exit edit mode
        }
        val = clampf(val, editSliderMin, editSliderMax);
        val = Math.round(val * 100f) / 100f; // same 0.01 snap as applySliderDrag
        ClientSettings cfg = settings();
        setSlider(cfg, kind, val);
        cfg.save();
    }

    private void cancelSliderEdit() {
        editingSliderKind = 0;
    }

    private void sliderEditKey(char typedChar, int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                commitSliderEdit();
                playClick();
                return;
            case Keyboard.KEY_ESCAPE:
                cancelSliderEdit();
                return;
            case Keyboard.KEY_BACK:
                if (editSliderBuf.length() > 0) editSliderBuf.deleteCharAt(editSliderBuf.length() - 1);
                caretBlink = 0;
                return;
            default:
                if (editSliderBuf.length() >= 8) return;
                if ((typedChar >= '0' && typedChar <= '9')
                        || (typedChar == '-' && editSliderBuf.length() == 0)
                        || (typedChar == '.' && editSliderBuf.indexOf(".") < 0)) {
                    editSliderBuf.append(typedChar);
                    caretBlink = 0;
                }
        }
    }

    /** Active scrollbar geometry for the current section, or null when nothing scrolls.
     *  Indices: [0]=x1 [1]=x2 [2]=top [3]=bottom [4]=thumbH [5]=travel — mirrors {@link #drawScrollbar}. */
    private float[] scrollbarGeom() {
        if (maxScroll <= 0) return null;
        int top, bottom, contentPx = rowsBlockH;
        if (searchActive() || SECTIONS[selectedSection].cards) {
            top = cardsViewTop;          // a card list (section view or search results)
            bottom = cardsViewBottom;
        } else {
            // Match drawRows: the Settings page drops its rows below the theme carousel (flatRowsTop),
            // so the draggable thumb track must start there too — otherwise it sits over the carousel.
            top = selectedSection == SETTINGS_SECTION ? flatRowsTop : contentTop;
            bottom = rowsViewBottom; // stop above any pinned bottom button, matching the row layout
        }
        int trackH = bottom - top;
        if (trackH <= 0 || contentPx <= 0) return null;
        float thumbH = Math.max(14f, trackH * (trackH / (float) contentPx));
        return new float[]{contentRight - 3, contentRight, top, bottom, thumbH, trackH - thumbH};
    }

    /** Thin scrollbar on the right edge of a viewport [top, bottom] given the total content height. */
    private void drawScrollbar(int top, int bottom, int contentPx) {
        int trackH = bottom - top;
        if (trackH <= 0 || contentPx <= 0) return;
        float x2 = contentRight, x1 = x2 - 1.0f; // thinner bar (was 1.5px)
        GuiRender.roundedRect(x1, top, x2, bottom, 0.5f, 0x14FFFFFF);
        float thumbH = Math.max(14f, trackH * (trackH / (float) contentPx));
        float travel = trackH - thumbH;
        float t = maxScroll == 0 ? 0f : clampf(scrollRender / maxScroll, 0f, 1f);
        float thumbY = top + travel * t;
        GuiRender.roundedRect(x1, thumbY, x2, thumbY + thumbH, 0.5f, 0x59FFFFFF);
    }

    private void drawCross(float cx, float cy, float r, int color) {
        GuiRender.line(cx - r, cy - r, cx + r, cy + r, 0.75f, color);
        GuiRender.line(cx - r, cy + r, cx + r, cy - r, 0.75f, color);
    }

    private void stopEditing() {
        commitSliderEdit();
        capturingDummyKey = false;
        closeDropdown();
    }

    private static String keyName(int code) {
        if (code == Keyboard.KEY_NONE) return "Unbound";
        String name = Keyboard.getKeyName(code);
        return name == null ? ("#" + code) : name;
    }

    private static String ellipsize(String s, float scale, float maxW) {
        if (s == null || s.isEmpty()) return "";
        if (GuiRender.textWidth(s, scale) <= maxW) return s;
        float ew = GuiRender.textWidth("...", scale);
        int end = s.length();
        while (end > 0 && GuiRender.textWidth(s.substring(0, end), scale) + ew > maxW) end--;
        return s.substring(0, end) + "...";
    }

    // ---------------------------------------------------------------- search section

    /** Collects every top-level module (toggle) from the card sections (HUD/Combat/Visuals/Stats). */
    private void collectModules() {
        allModules.clear();
        for (Section s : SECTIONS) {
            if (!s.cards) continue;
            for (RowDef rd : s.rows) {
                if (!rd.child && rd.desc != null) allModules.add(new SearchModule(rd, s.tab));
            }
        }
    }

    /** True when a non-empty query is active — the content area shows search results instead of the
     *  selected section. The search bar itself lives in the header and is always visible. */
    private boolean searchActive() {
        return searchQuery.toString().trim().length() > 0;
    }

    /** Filters + ranks the module list for the current query and lays the results out as module cards
     *  across the full content band (the search bar lives in the header; geometry set in initGui). */
    private void layoutSearchResults() {
        searchListTop = contentTop;
        searchListBottom = contentTop + contentH;

        searchResults.clear();
        final String q = searchQuery.toString().trim();
        if (q.isEmpty()) {
            searchResults.addAll(allModules); // empty query -> all, in section order
        } else {
            final java.util.IdentityHashMap<SearchModule, Integer> score =
                    new java.util.IdentityHashMap<SearchModule, Integer>();
            List<Integer> tmp = new ArrayList<Integer>();
            for (SearchModule m : allModules) {
                int best = ModuleSearch.scoreField(q, m.def.label, tmp);          // name: full weight
                int d = ModuleSearch.scoreField(q, m.def.desc, tmp);
                if (d != ModuleSearch.NO_MATCH) best = Math.max(best, d - 40);    // description: lower
                int sc = ModuleSearch.scoreField(q, m.section, tmp);
                if (sc != ModuleSearch.NO_MATCH) best = Math.max(best, sc - 60);  // category: lowest
                if (best != ModuleSearch.NO_MATCH) {
                    score.put(m, best);
                    searchResults.add(m);
                }
            }
            java.util.Collections.sort(searchResults, new java.util.Comparator<SearchModule>() {
                public int compare(SearchModule a, SearchModule b) {
                    int s = Integer.compare(score.get(b), score.get(a));               // best first
                    if (s != 0) return s;
                    int l = Integer.compare(a.def.label.length(), b.def.label.length()); // shorter wins tie
                    if (l != 0) return l;
                    return Integer.compare(allModules.indexOf(a), allModules.indexOf(b)); // stable
                }
            });
        }

        // Render the (filtered/ranked) results as real module cards, so the search page looks and
        // behaves identically to a section: same chrome, click-to-enable, "+"/right-click expands inline.
        List<RowDef> moduleDefs = new ArrayList<RowDef>(searchResults.size());
        List<List<RowDef>> childDefs = new ArrayList<List<RowDef>>(searchResults.size());
        for (SearchModule m : searchResults) {
            moduleDefs.add(m.def);
            childDefs.add(childrenOf(m.def));
        }
        buildCards(moduleDefs, childDefs, searchListTop, Math.max(0, searchListBottom - searchListTop));
    }

    private void drawSearchResults(int mouseX, int mouseY) {
        // The search bar is drawn in the header; here we render only the results (or an empty-state line).
        if (searchResults.isEmpty()) {
            GuiRender.textCentered("No matches", (contentX + contentRight) / 2f,
                    searchListTop + (searchListBottom - searchListTop) / 2f - BedwarsQolFont.height(valueScale) / 2f,
                    valueScale, TEXT_LO);
        } else {
            drawCards(mouseX, mouseY);
        }
    }

    private void searchKey(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {       // ESC clears a query, else just blurs the field
            if (searchQuery.length() > 0) {
                searchQuery.setLength(0);
                resetScroll();
                layoutContent();
            }
            searchFocused = false;
            return;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (searchQuery.length() > 0) searchQuery.deleteCharAt(searchQuery.length() - 1);
            resetScroll();
            layoutContent();
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && searchQuery.length() < 48) {
            searchQuery.append(typedChar);
            resetScroll();
            layoutContent();
        }
    }

    private void resetScroll() {
        scroll = 0;
        scrollRender = 0f;
        scrollAccum = 0f;
    }

    private void playClick() {
        mc.getSoundHandler().playSound(
                PositionedSoundRecord.create(new ResourceLocation("gui.button.press"), 1.0F));
    }

    private static ClientSettings settings() {
        if (BedwarsQol.config == null) BedwarsQol.config = new ClientSettings();
        BedwarsQol.config.sanitize();
        return BedwarsQol.config;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        return Math.min(v, max);
    }

    private static float clampf(float v, float min, float max) {
        if (v < min) return min;
        return Math.min(v, max);
    }

    // ---- gradient fill (OkLab / OkLCH-derived left→right sheen on module cards + theme swatches) ----
    // Endpoints are computed in OkLab — a perceptually-uniform colour space (Björn Ottosson, 2020; the
    // model behind CSS Color 4's `oklch`) — instead of naive sRGB channel scaling, which on dark colours
    // barely moves and turns muddy. gradHi is a soft highlight (clearly lighter, a touch LESS chroma so it
    // reads like light catching the surface, plus a few degrees of hue rotation); gradLo is a deeper shade
    // (darker, slightly MORE chroma, opposite rotation). Drawn left (gradHi) → right (gradLo). The
    // perceptual lightness delta is large enough to be obvious yet still tasteful. The base colour's alpha
    // is preserved so card/swatch translucency is unchanged. Neutral greys (chroma 0) stay neutral.
    private static int gradHi(int base) { return okShift(base,  0.105f, 0.90f,  5f); }
    private static int gradLo(int base) { return okShift(base, -0.090f, 1.14f, -5f); }

    /** Returns {@code argb} shifted in OkLCH by (+{@code dL} lightness, chroma ×{@code cMul},
     *  +{@code dHueDeg} hue), then converted back to sRGB. The alpha byte is preserved. */
    private static int okShift(int argb, float dL, float cMul, float dHueDeg) {
        int alpha = argb >>> 24 & 0xFF;
        float r = srgbToLinear((argb >> 16 & 0xFF) / 255f);
        float g = srgbToLinear((argb >> 8 & 0xFF) / 255f);
        float b = srgbToLinear((argb & 0xFF) / 255f);
        // linear sRGB -> OkLab
        float lm = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float mm = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
        float sm = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b;
        float lc = (float) Math.cbrt(lm), mc = (float) Math.cbrt(mm), sc = (float) Math.cbrt(sm);
        float L = 0.2104542553f * lc + 0.7936177850f * mc - 0.0040720468f * sc;
        float A = 1.9779984951f * lc - 2.4285922050f * mc + 0.4505937099f * sc;
        float Bb = 0.0259040371f * lc + 0.7827717662f * mc - 0.8086757660f * sc;
        // OkLab -> OkLCH, apply the shifts, back to OkLab
        float C = (float) Math.hypot(A, Bb);
        float h = (float) Math.atan2(Bb, A) + (float) Math.toRadians(dHueDeg);
        L = clampf(L + dL, 0f, 1f);
        C = Math.max(0f, C * cMul);
        A = C * (float) Math.cos(h);
        Bb = C * (float) Math.sin(h);
        // OkLab -> linear sRGB
        float l_ = L + 0.3963377774f * A + 0.2158037573f * Bb;
        float m_ = L - 0.1055613458f * A - 0.0638541728f * Bb;
        float s_ = L - 0.0894841775f * A - 1.2914855480f * Bb;
        l_ = l_ * l_ * l_; m_ = m_ * m_ * m_; s_ = s_ * s_ * s_;
        float or =  4.0767416621f * l_ - 3.3077115913f * m_ + 0.2309699292f * s_;
        float og = -1.2684380046f * l_ + 2.6097574011f * m_ - 0.3413193965f * s_;
        float ob = -0.0041960863f * l_ - 0.7034186147f * m_ + 1.7076147010f * s_;
        int R = clampByte(Math.round(linearToSrgb(or) * 255f));
        int G = clampByte(Math.round(linearToSrgb(og) * 255f));
        int Bc = clampByte(Math.round(linearToSrgb(ob) * 255f));
        return (alpha << 24) | (R << 16) | (G << 8) | Bc;
    }

    private static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    private static float linearToSrgb(float c) {
        if (c <= 0f) return 0f;
        if (c >= 1f) return 1f;
        return c <= 0.0031308f ? 12.92f * c : 1.055f * (float) Math.pow(c, 1f / 2.4f) - 0.055f;
    }

    private static int clampByte(int v) { return v < 0 ? 0 : Math.min(255, v); }
}
