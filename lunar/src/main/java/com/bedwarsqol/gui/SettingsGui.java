package com.bedwarsqol.gui;

import com.bedwarsqol.BedwarsQol;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.Keybind;
import com.bedwarsqol.gui.render.BedwarsQolFont;
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
            // Search has its own path (drawSearch): a search bar over a filtered list of every module.
            new Section("Search"),
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
            new Section("Hypixel Stats", true,
                    new RowDef(RowType.TOGGLE, "Hypixel Stats", "BedWars stats on screen", K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Nametag", K_NAMETAG, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Tab", K_TAB, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Chat Hover", K_CHATHOVER, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Level", K_LEVEL, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Show Rank", K_RANK, null, K_STATS),
                    new RowDef(RowType.TOGGLE, "Party Report", K_SWEATREPORT, null, K_STATS)),
            // Keybinds is rendered by a dedicated scrollable-list path (drawKeybinds), not the row
            // engine — it has no fixed RowDefs.
            new Section("Keybinds"),
            new Section("Settings",
                    new RowDef(RowType.STEPPER, "GUI Size", K_GUISIZE, GUI_SIZES),
                    new RowDef(RowType.STEPPER, "HUD Size", K_HUDSIZE, TEXT_SIZES),
                    new RowDef(RowType.STEPPER, "Scoreboard Size", K_SCOREBOARD_SIZE, SIZES),
                    new RowDef(RowType.STEPPER, "Tab List Size", K_STYLEDTAB_SIZE, SIZES),
                    new RowDef(RowType.STEPPER, "Display", K_DISPLAY, DISPLAY_MODES),
                    new RowDef(RowType.STEPPER, "Font", K_HUDFONT, FONT_MODES),
                    new RowDef(RowType.ACTION, "Edit HUD", K_EDITHUD, null)),
    };

    /** Indices of the special sections (own render/input paths) in {@link #SECTIONS}. */
    private static final int SEARCH_SECTION = 0;
    private static final int KEYBINDS_SECTION = 5;

    private static final int MAX_ROWS = 6;
    private static final int MSG_MAX_LEN = 256;
    private static final String ADD_LABEL = "+ Add Keybind";
    // Sidebar pill insets: gap from the panel edge to the pill, and from the pill to its label.
    private static final int NAV_INSET = 6;
    private static final int NAV_TEXT_PAD = 10;

    // ---- grayscale palette (Radix-style neutral ramp; depth via luminance + hairlines) ----
    // Shared constants live in gui/render/Theme so HUD elements (scoreboard, tab list) match exactly.
    private static final int PANEL = Theme.PANEL;
    private static final int PANEL_BORDER = Theme.PANEL_BORDER; // whiteish keyline
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
    private static final int CARD_OFF_BG = 0xFF1C1C1C;
    private static final int CARD_OFF_BORDER = 0x14FFFFFF;
    private static final int CARD_ON_BORDER = 0x3DFFFFFF;
    private static final int CARD_R = 5;
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

    private int selectedSection = 1; // open to HUD by default (index 0 is the Search page)
    private final List<Row> rows = new ArrayList<Row>();
    private final List<Card> cards = new ArrayList<Card>();
    // Which module cards are expanded (by module kind). Empty = all collapsed (the default each open).
    private final java.util.Set<Integer> expandedModules = new java.util.HashSet<Integer>();

    // geometry / rhythm (computed in initGui)
    private int panelX, panelY, panelW, panelH, pad, sidebarW;
    private int contentX, contentRight, contentTop, contentH, rowH;
    private int navStartY, navItemH;
    private float labelScale, valueScale, navScale;
    // Module-card typography: a slightly smaller header and a noticeably smaller subtext than the base
    // label scale, so cards read compact/sleek and the (shortened) descriptions fit on a single line.
    private float cardTitleScale, cardDescScale;
    // Dropdown typography: a notch smaller than the value scale so triggers and option rows read compact.
    private float ddFontScale;
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

    // ---- Keybinds section state ----
    // Inline editing of a macro's message (custom Inter text field with a blinking caret) and key
    // capture are mutually exclusive: editingKb / capturingKb hold the row index, or -1 when idle.
    private int editingKb = -1;
    private int capturingKb = -1;
    // Debug section: capturing the Test Dummy spawn key (the next keypress binds it; ESC cancels).
    private boolean capturingDummyKey;
    private final StringBuilder editBuf = new StringBuilder();
    private int caret;
    private int caretBlink;
    // Shared vertical scroll for whichever section is showing (the row list or the keybinds list).
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
    // Keybinds geometry (computed in layoutKeybinds()).
    private int kbListTop, kbListBottom, kbRowH, kbVm, kbKeyX2, kbMsgX1, kbMsgX2, kbDelX1, kbDelX2;
    private int kbAddX1, kbAddY1, kbAddX2, kbAddY2;
    // Shared bottom "action bar" button band (Reset HUD Sizes / + Add Keybind) — same height & y on
    // both pages, pinned to the bottom of the content area.
    private int bottomBtnY1, bottomBtnY2;
    private RowDef pinnedAction; // ACTION row pulled out of a flat section to the bottom band (or null)
    // Bottom of the flat-row scrollable viewport: the full content band, minus the pinned-button band
    // when an ACTION is pinned. The scrollbar must use this (not contentTop+contentH) so it stops above
    // the pinned button instead of overrunning it. Set in layoutContent, read by drawRows/scrollbarGeom.
    private int rowsViewBottom;
    private float kbChipScale;

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
        // The responsive "Large" size, then scaled down for Medium / Small. Everything below derives
        // from panelW/panelH, so the whole panel scales proportionally; the min-clamps are low enough
        // that the tallest (6-row) section still fits at Small.
        int largeW = clamp(Math.round(width * 0.62f), 330, 480);
        int largeH = clamp(Math.round(height * 0.60f), 188, 320);
        panelW = Math.round(largeW * gf);
        panelH = Math.round(largeH * gf);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        pad = clamp(Math.round(panelW * 0.040f), 10, 18);

        contentRight = panelX + panelW - pad;
        contentTop = panelY + pad;
        contentH = panelH - 2 * pad;
        rowH = clamp(contentH / MAX_ROWS, 16, 40);
        childRowH = clamp(Math.round(rowH * 0.66f), 12, 26); // compact sub-setting rows
        childIndent = clamp(Math.round(rowH * 0.7f), 12, 28);
        labelScale = clampf(rowH * 0.046f, 1.0f, 1.85f);
        valueScale = labelScale * 0.96f;
        ddFontScale = valueScale * 0.74f; // dropdowns smaller than other value text
        cardTitleScale = labelScale * 0.9f;
        cardDescScale = labelScale * 0.66f;
        // Sidebar vertical layout first: every SECTIONS item must fit inside the panel at every GUI size.
        int navTop = panelY + pad;
        int navBottom = panelY + panelH - pad;
        int navAvail = navBottom - navTop;
        // Floor low enough that all items fit at Small; cap the block to the available height so the last
        // item (Debug) can never spill past navBottom or the panel border.
        navItemH = clamp(navAvail / SECTIONS.length, 12, 30);
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
        layoutContent();
    }

    private static float guiFactor(int guiSize) {
        switch (guiSize) {
            case 0: return 0.70f;  // Small
            case 1: return 0.85f;  // Medium
            default: return 1.0f;  // Large
        }
    }

    private void layoutContent() {
        rows.clear();
        cards.clear();
        pinnedAction = null;
        if (selectedSection == SEARCH_SECTION) {
            layoutSearch();
            return;
        }
        if (selectedSection == KEYBINDS_SECTION) {
            layoutKeybinds();
            return;
        }
        if (SECTIONS[selectedSection].cards) {
            layoutCards();
            return;
        }
        Section section = SECTIONS[selectedSection];
        // Compact the flat "Settings" rows: a shorter top-level row height than the global rowH.
        int flatRowH = clamp(Math.round(rowH * 0.80f), 13, 30);
        // Pull any ACTION row out to the shared pinned bottom band; lay out the rest (steppers).
        // Widest stepper option label is shared so every stepper's arrows align.
        pageStepperTextW = 0f;
        int blockH = 0;
        for (RowDef rd : section.rows) {
            if (rd.type == RowType.ACTION) { pinnedAction = rd; continue; }
            if (rd.type == RowType.STEPPER && rd.options != null) {
                // Triggers render their value at ddFontScale, so measure the option widths there too.
                for (String opt : rd.options) {
                    pageStepperTextW = Math.max(pageStepperTextW, GuiRender.textWidth(opt, ddFontScale));
                }
            }
            blockH += rd.child ? childRowH : flatRowH;
        }
        rowsBlockH = blockH;
        // Rows fill the area above the pinned button (if any) and always start from the TOP, not centered.
        int listBottom = pinnedAction != null
                ? bottomBtnY1 - clamp(Math.round(rowH * 0.35f), 4, 10) : contentTop + contentH;
        rowsViewBottom = listBottom;
        int availH = listBottom - contentTop;
        boolean scrollable = blockH > availH;
        maxScroll = scrollable ? blockH - availH : 0;
        scroll = clamp(scroll, 0, maxScroll);
        int contentW = contentRight - contentX - SCROLL_GUTTER;
        int y = contentTop - scroll; // top-aligned
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
        if (selectedSection == SEARCH_SECTION) layoutSearch();
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

    /**
     * Lays out the Keybinds view: a scrollable list of macro rows in a clipped viewport, with a
     * pinned "+ Add Keybind" button below it. All columns derive from the content area, so it stays
     * balanced at every GUI size; the viewport height drives {@link #maxScroll} and whether the
     * scrollbar shows.
     */
    private void layoutKeybinds() {
        int contentW = contentRight - contentX;
        kbRowH = clamp(Math.round(rowH * 1.05f), 18, 34);
        kbVm = clamp(Math.round(kbRowH * 0.26f), 3, 8);
        kbChipScale = clampf(labelScale * 0.68f, 0.8f, 1.15f);

        // Pinned add button at the shared bottom action band (same height/position as Reset HUD Sizes).
        kbAddY2 = bottomBtnY2;
        kbAddY1 = bottomBtnY1;
        float[] add = bottomButton(ADD_LABEL);
        kbAddX1 = Math.round(add[0]);
        kbAddX2 = Math.round(add[2]);

        // List viewport above the add button.
        kbListTop = contentTop;
        kbListBottom = kbAddY1 - clamp(Math.round(kbRowH * 0.35f), 4, 10);

        int n = settings().keybinds.size();
        int viewH = Math.max(0, kbListBottom - kbListTop);
        maxScroll = Math.max(0, n * kbRowH - viewH);
        scroll = clamp(scroll, 0, maxScroll);

        // Columns: [ key chip ] [ message ........ ] [ ✕ ], plus a scrollbar gutter when needed.
        int gutter = maxScroll > 0 ? 7 : 0;
        int listRight = contentRight - gutter;
        int gap = clamp(Math.round(contentW * 0.025f), 4, 10);
        int delW = clamp(kbRowH - 2 * kbVm, 12, 20);
        kbDelX2 = listRight - 2;
        kbDelX1 = kbDelX2 - delW;
        int keyW = clamp(Math.round(contentW * 0.23f), 42, 86);
        kbKeyX2 = contentX + keyW;
        kbMsgX1 = kbKeyX2 + gap;
        kbMsgX2 = kbDelX1 - gap;
    }

    private int kbRowTop(int i) {
        return kbListTop - scroll + i * kbRowH;
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        advanceScroll();
        drawDefaultBackground();

        int px2 = panelX + panelW, py2 = panelY + panelH;
        GuiRender.roundedRect(panelX, panelY, px2, py2, PANEL_R, PANEL);
        GuiRender.roundedRectOutline(panelX, panelY, px2, py2, PANEL_R, 0.75f, PANEL_BORDER);

        // sidebar / content divider
        GuiRender.rect(panelX + sidebarW, panelY + pad, panelX + sidebarW + 1, py2 - pad, DIVIDER);

        // While a dropdown is open it's modal: feed the rest of the GUI an off-screen cursor so nothing
        // behind the menu hover-highlights (other triggers, rows) or scales (sidebar items).
        int hx = openDropdownKind != 0 ? -1 : mouseX;
        int hy = openDropdownKind != 0 ? -1 : mouseY;
        drawSidebar(hx, hy);
        if (selectedSection == SEARCH_SECTION) drawSearch(hx, hy);
        else if (selectedSection == KEYBINDS_SECTION) drawKeybinds(hx, hy);
        else if (SECTIONS[selectedSection].cards) drawCards(hx, hy);
        else drawRows(hx, hy);

        // The open dropdown menu floats above all section content (and the scrollbar).
        drawDropdownPopup(mouseX, mouseY);
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
    }

    /** Section icon from the Tabler (MIT) alpha atlas, tinted by {@code color}. See {@link Icons}.
     *  The slight upscale compensates for the icon set's built-in grid padding so it matches the label. */
    private void drawNavIcon(int section, float cx, float cy, float size, int color) {
        Icons.draw(section, cx, cy, size * 1.1f, color);
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
            GuiRender.roundedRect(card.x, card.y, card.x + card.w, card.y + card.h, CARD_R, CARD_OFF_BG);
            GuiRender.roundedRectOutline(card.x, card.y, card.x + card.w, card.y + card.h, CARD_R, 0.75f,
                    on ? CARD_ON_BORDER : CARD_OFF_BORDER);

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
                drawExpandIcon(card.x + card.w - padX - chevW / 2f, card.y + card.headerH / 2f, chevW,
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
        float lScale = labelScale * 0.72f;
        float vScale = valueScale * 0.72f;
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
        int viewTop = contentTop, viewBottom = rowsViewBottom;
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
            // Match the module-card typography: card titles render at labelScale*0.9, so the (flat)
            // Settings rows use that same compact size for a consistent feel across the whole GUI.
            float lScale = labelScale * 0.72f;
            float vScale = valueScale * 0.72f;
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
                    drawDropdownTrigger(row, row.def.options[stepperIndex(cfg, row.def.kind)], ddFontScale, enabled, mouseX, mouseY);
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

    /** Right-aligned checkbox: an empty rounded square (keyline) when off, filled solid white when on.
     *  Simple, elegant, modern — replaces the old pill switch. */
    private void drawCheckbox(Row row, boolean on, boolean enabled) {
        // Small box; vertically centered on the row (== the label's vertical center, since the label is
        // font-centered in the same row height).
        float size = clampf(row.h * (row.def.child ? 0.22f : 0.26f),
                row.def.child ? 5f : 6f, row.def.child ? 7f : 8.5f);
        float rightPad = controlRightPad(row.h); // inset from the row's right edge (shared with the dropdown)
        float x2 = row.x + row.w - rightPad;
        float x1 = x2 - size;
        float y1 = row.y + (row.h - size) / 2f;
        float y2 = y1 + size;
        float r = clampf(size * 0.22f, 1.2f, 2f);
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
        float r = (c[3] - c[1]) * 0.28f;
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
        float limitTop = panelY + pad;
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
        float padY = 2f, r = 4f;
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

    // ---------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
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
            if (selectedSection == SEARCH_SECTION
                    || (selectedSection != KEYBINDS_SECTION && SECTIONS[selectedSection].cards)) {
                rightClickCards(mouseX, mouseY);
            }
            return;
        }
        if (mouseButton != 0) return;

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
                if (selectedSection != KEYBINDS_SECTION) layoutContent(); // search & sections bake card y
                playClick();
            }
            return;
        }

        for (int i = 0; i < SECTIONS.length; i++) {
            int y = navStartY + i * navItemH;
            if (GuiRender.inside(mouseX, mouseY, panelX, y, panelX + sidebarW, y + navItemH)) {
                if (i != selectedSection) {
                    stopEditing();
                    selectedSection = i;
                    scroll = 0;
                    scrollRender = 0f;
                    scrollAccum = 0f;
                    searchQuery.setLength(0); // each visit to the search page starts fresh
                    layoutContent();
                    playClick();
                }
                return;
            }
        }

        if (selectedSection == SEARCH_SECTION) {
            searchClick(mouseX, mouseY, cfg);
            return;
        }

        if (selectedSection == KEYBINDS_SECTION) {
            keybindsClick(mouseX, mouseY, cfg);
            return;
        }

        if (SECTIONS[selectedSection].cards) {
            cardsClick(mouseX, mouseY, cfg);
            return;
        }

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
                    openDropdown(row, ddFontScale); // click the row -> open the value dropdown
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
    public void handleMouseInput() {
        super.handleMouseInput();
        if (maxScroll <= 0) return;
        int dwheel = Mouse.getEventDWheel();
        if (dwheel == 0) return;
        if (openDropdownKind != 0) return; // an open dropdown is modal: swallow the wheel, don't scroll
        // Proportional, fine-grained wheel mapping. macOS LWJGL2 reports (int)(deltaY*120): only ~10-12
        // per slow notch (more when spun fast, or many small momentum deltas from a trackpad); Windows
        // ~120. Using the MAGNITUDE (not just the sign) makes a trackpad scroll finely and a wheel scroll
        // in sensible steps; the fractional accumulator preserves sub-pixel deltas through the int target,
        // and the per-frame ease (advanceScroll) smooths the coarse result on every platform.
        int rh = Math.max(8, selectedSection == KEYBINDS_SECTION ? kbRowH : rowH);
        float delta = dwheel / 120f * (rh * 1.1f);     // ~1.1 rows per full notch; sub-notch deltas -> finer
        delta = clampf(delta, -rh * 2.5f, rh * 2.5f);  // clamp a fast flick so the target never teleports
        scrollAccum -= delta;                          // wheel up (dwheel>0) scrolls content up
        int whole = (int) scrollAccum;                 // commit whole pixels, carry the fraction
        if (whole != 0) {
            scrollAccum -= whole;
            scroll = clamp(scroll + whole, 0, maxScroll);
            // Keybinds reads scroll live (kbRowTop); cards (sections & search) bake y -> re-layout.
            if (selectedSection != KEYBINDS_SECTION) layoutContent();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (clickedMouseButton == 0 && draggingThumb) {
            float[] sb = scrollbarGeom();
            if (sb != null && sb[5] > 0f) {
                float newTop = clampf(mouseY - sb[2] - thumbGrabDy, 0f, sb[5]);
                scroll = clamp(Math.round(newTop / sb[5] * maxScroll), 0, maxScroll);
                scrollRender = scroll; // track the cursor directly while dragging (no lag)
                if (selectedSection != KEYBINDS_SECTION) layoutContent(); // search & sections bake card y
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
    protected void keyTyped(char typedChar, int keyCode) {
        if (openDropdownKind != 0) {               // an open dropdown swallows keys; ESC closes it
            if (keyCode == Keyboard.KEY_ESCAPE) { closeDropdown(); playClick(); }
            return;
        }
        if (editingSliderKind != 0) {
            sliderEditKey(typedChar, keyCode);
            return;
        }
        if (selectedSection == SEARCH_SECTION) {
            searchKey(typedChar, keyCode);
            return;
        }
        // While capturing a macro's key, the next keypress sets it (ESC cancels) — swallow either way.
        if (capturingKb >= 0) {
            if (keyCode != Keyboard.KEY_ESCAPE) {
                List<Keybind> kbs = settings().keybinds;
                if (capturingKb < kbs.size()) {
                    kbs.get(capturingKb).keyCode = keyCode;
                    settings().save();
                    playClick();
                }
            }
            capturingKb = -1;
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
        // While editing a message, route all keys to the inline text field (ESC/Enter commit).
        if (editingKb >= 0) {
            editMessageKey(typedChar, keyCode);
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

    // ---------------------------------------------------------------- keybinds view

    private void drawKeybinds(int mouseX, int mouseY) {
        List<Keybind> kbs = settings().keybinds;
        boolean inView = mouseY >= kbListTop && mouseY <= kbListBottom;

        float scrollDy = scroll - scrollRender; // shift live (target) row positions to the eased offset
        GuiRender.beginScissor(contentX, kbListTop, contentRight, kbListBottom);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, scrollDy, 0f);
        if (kbs.isEmpty()) {
            float ey = kbListTop + (kbListBottom - kbListTop) / 2f - BedwarsQolFont.height(valueScale) / 2f;
            GuiRender.textCentered("No keybinds yet", (contentX + contentRight) / 2f, ey, valueScale, TEXT_LO);
        } else {
            for (int i = 0; i < kbs.size(); i++) {
                int ry = kbRowTop(i);
                if (ry + scrollDy + kbRowH < kbListTop || ry + scrollDy > kbListBottom) continue; // cull off-screen rows
                drawKeybindRow(kbs.get(i), i, ry, mouseX, mouseY, inView);
            }
        }
        GlStateManager.popMatrix();
        GuiRender.endScissor();

        if (maxScroll > 0) drawScrollbar(kbListTop, kbListBottom, kbs.size() * kbRowH);

        boolean addHover = GuiRender.inside(mouseX, mouseY, kbAddX1, kbAddY1, kbAddX2, kbAddY2);
        drawBottomButton(new float[]{kbAddX1, kbAddY1, kbAddX2, kbAddY2}, ADD_LABEL, addHover);
    }

    private void drawKeybindRow(Keybind kb, int i, int ry, int mouseX, int mouseY, boolean inView) {
        boolean rowHover = inView && GuiRender.inside(mouseX, mouseY, contentX, ry, contentRight, ry + kbRowH);
        if (rowHover) GuiRender.roundedRect(contentX, ry + 1, contentRight, ry + kbRowH - 1, 4, ROW_HOVER);
        if (i > 0) GuiRender.divider(contentX, kbDelX2, ry - 0.5f, SEPARATOR);

        int chipY1 = ry + kbVm, chipY2 = ry + kbRowH - kbVm;
        float chipR = (chipY2 - chipY1) * 0.28f;

        // key chip (click to capture a key)
        boolean capturing = capturingKb == i;
        boolean keyHover = inView && GuiRender.inside(mouseX, mouseY, contentX, chipY1, kbKeyX2, chipY2);
        GuiRender.roundedRect(contentX, chipY1, kbKeyX2, chipY2, chipR, (keyHover || capturing) ? BTN_HOVER : BTN_BG);
        GuiRender.roundedRectOutline(contentX, chipY1, kbKeyX2, chipY2, chipR, 0.75f, capturing ? PANEL_BORDER : BTN_BORDER);
        String keyLabel = capturing ? "Press..." : keyName(kb.keyCode);
        int keyColor = capturing ? TEXT_HI : (kb.keyCode == 0 ? TEXT_LO : TEXT_MID);
        GuiRender.textCentered(ellipsize(keyLabel, kbChipScale, (kbKeyX2 - contentX) - 8f),
                (contentX + kbKeyX2) / 2f, vcenter(ry, kbRowH, kbChipScale), kbChipScale, keyColor, MED);

        // message (click to edit inline)
        float msgY = vcenter(ry, kbRowH, valueScale);
        int msgW = kbMsgX2 - kbMsgX1;
        if (editingKb == i) {
            drawMessageEditor(msgY, msgW);
        } else {
            String msg = kb.message == null ? "" : kb.message;
            if (msg.isEmpty()) GuiRender.text("Click to set message", kbMsgX1, msgY, valueScale, TEXT_LO);
            else GuiRender.text(ellipsize(msg, valueScale, msgW), kbMsgX1, msgY, valueScale, TEXT_HI);
        }

        // delete
        boolean delHover = inView && GuiRender.inside(mouseX, mouseY, kbDelX1, chipY1, kbDelX2, chipY2);
        if (delHover) GuiRender.roundedRect(kbDelX1, chipY1, kbDelX2, chipY2, 3, NAV_HOVER);
        drawCross((kbDelX1 + kbDelX2) / 2f, (chipY1 + chipY2) / 2f, (chipY2 - chipY1) * 0.11f,
                delHover ? TEXT_HI : TEXT_MID);
    }

    /** The inline message field: text shifted so the blinking caret stays inside the column. */
    private void drawMessageEditor(float msgY, int msgW) {
        String text = editBuf.toString();
        int c = Math.min(caret, text.length());
        float caretX = GuiRender.textWidth(text.substring(0, c), valueScale);
        float offset = caretX > msgW - 2 ? caretX - (msgW - 2) : 0f;

        GuiRender.beginScissor(kbMsgX1, kbListTop, kbMsgX2, kbListBottom); // clip to the message column
        GuiRender.text(text, kbMsgX1 - offset, msgY, valueScale, TEXT_HI);
        if ((caretBlink / 6) % 2 == 0) {
            float cx = kbMsgX1 - offset + caretX;
            GuiRender.rect(cx, msgY - 1, cx + 1, msgY + BedwarsQolFont.height(valueScale), TEXT_HI);
        }
        GuiRender.beginScissor(contentX, kbListTop, contentRight, kbListBottom); // restore list clip
    }

    /** Active scrollbar geometry for the current section, or null when nothing scrolls.
     *  Indices: [0]=x1 [1]=x2 [2]=top [3]=bottom [4]=thumbH [5]=travel — mirrors {@link #drawScrollbar}. */
    private float[] scrollbarGeom() {
        if (maxScroll <= 0) return null;
        int top, bottom, contentPx;
        if (selectedSection == KEYBINDS_SECTION) {
            top = kbListTop;
            bottom = kbListBottom;
            contentPx = settings().keybinds.size() * kbRowH;
        } else if (selectedSection == SEARCH_SECTION) {
            top = searchListTop;          // the card list lives below the search bar
            bottom = searchListBottom;
            contentPx = rowsBlockH;
        } else {
            top = contentTop;
            bottom = rowsViewBottom; // stop above any pinned bottom button, matching the row layout
            contentPx = rowsBlockH;
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
        float x2 = contentRight, x1 = x2 - 1.5f; // half the previous 3px width
        GuiRender.roundedRect(x1, top, x2, bottom, 0.75f, 0x14FFFFFF);
        float thumbH = Math.max(14f, trackH * (trackH / (float) contentPx));
        float travel = trackH - thumbH;
        float t = maxScroll == 0 ? 0f : clampf(scrollRender / maxScroll, 0f, 1f);
        float thumbY = top + travel * t;
        GuiRender.roundedRect(x1, thumbY, x2, thumbY + thumbH, 0.75f, 0x59FFFFFF);
    }

    private void drawCross(float cx, float cy, float r, int color) {
        GuiRender.line(cx - r, cy - r, cx + r, cy + r, 0.75f, color);
        GuiRender.line(cx - r, cy + r, cx + r, cy - r, 0.75f, color);
    }

    private void keybindsClick(int mouseX, int mouseY, ClientSettings cfg) {
        if (GuiRender.inside(mouseX, mouseY, kbAddX1, kbAddY1, kbAddX2, kbAddY2)) {
            addKeybind(cfg);
            playClick();
            return;
        }
        List<Keybind> kbs = cfg.keybinds;
        if (mouseY >= kbListTop && mouseY <= kbListBottom) {
            for (int i = 0; i < kbs.size(); i++) {
                int ry = kbRowTop(i);
                if (mouseY < ry || mouseY > ry + kbRowH) continue;
                int chipY1 = ry + kbVm, chipY2 = ry + kbRowH - kbVm;
                if (GuiRender.inside(mouseX, mouseY, kbDelX1, chipY1, kbDelX2, chipY2)) {
                    stopEditing();
                    kbs.remove(i);
                    cfg.save();
                    playClick();
                    layoutKeybinds();
                    return;
                }
                if (GuiRender.inside(mouseX, mouseY, contentX, chipY1, kbKeyX2, chipY2)) {
                    startCapture(i);
                    playClick();
                    return;
                }
                if (GuiRender.inside(mouseX, mouseY, kbMsgX1, ry, kbMsgX2, ry + kbRowH)) {
                    startEdit(i);
                    playClick();
                    return;
                }
                stopEditing(); // clicked the row but missed every control
                return;
            }
        }
        stopEditing(); // clicked empty space
    }

    private void addKeybind(ClientSettings cfg) {
        stopEditing();
        cfg.keybinds.add(new Keybind(Keyboard.KEY_NONE, ""));
        cfg.save();
        layoutKeybinds();
        scroll = maxScroll;                           // reveal the new row
        startCapture(cfg.keybinds.size() - 1);        // prompt for the key first
    }

    private void startCapture(int i) {
        commitEdit();
        capturingKb = i;
        editingKb = -1;
    }

    private void startEdit(int i) {
        if (editingKb == i) return; // already editing this row — keep the caret where it is
        commitEdit();
        capturingKb = -1;
        editingKb = i;
        editBuf.setLength(0);
        String m = settings().keybinds.get(i).message;
        if (m != null) editBuf.append(m);
        caret = editBuf.length();
        caretBlink = 0;
    }

    private void commitEdit() {
        if (editingKb < 0) return;
        List<Keybind> kbs = settings().keybinds;
        if (editingKb < kbs.size()) {
            kbs.get(editingKb).message = editBuf.toString();
            settings().save();
        }
        editingKb = -1;
    }

    private void stopEditing() {
        commitEdit();
        commitSliderEdit();
        capturingKb = -1;
        capturingDummyKey = false;
        closeDropdown();
    }

    private void editMessageKey(char typedChar, int keyCode) {
        if (isKeyComboCtrlV(keyCode)) {
            String clip = getClipboardString();
            if (clip != null) {
                for (int k = 0; k < clip.length() && editBuf.length() < MSG_MAX_LEN; k++) {
                    char ch = clip.charAt(k);
                    if (ChatAllowedCharacters.isAllowedCharacter(ch)) editBuf.insert(caret++, ch);
                }
                caretBlink = 0;
            }
            return;
        }
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                commitEdit();
                return;
            case Keyboard.KEY_BACK:
                if (caret > 0) editBuf.deleteCharAt(--caret);
                caretBlink = 0;
                return;
            case Keyboard.KEY_DELETE:
                if (caret < editBuf.length()) editBuf.deleteCharAt(caret);
                caretBlink = 0;
                return;
            case Keyboard.KEY_LEFT:
                if (caret > 0) caret--;
                caretBlink = 0;
                return;
            case Keyboard.KEY_RIGHT:
                if (caret < editBuf.length()) caret++;
                caretBlink = 0;
                return;
            case Keyboard.KEY_HOME:
                caret = 0;
                caretBlink = 0;
                return;
            case Keyboard.KEY_END:
                caret = editBuf.length();
                caretBlink = 0;
                return;
            default:
                if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && editBuf.length() < MSG_MAX_LEN) {
                    editBuf.insert(caret++, typedChar);
                    caretBlink = 0;
                }
        }
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

    /** Filters + ranks the module list for the current query and lays out the search viewport. */
    private void layoutSearch() {
        int contentW = contentRight - contentX;
        int barH = clamp(Math.round(rowH * 0.78f), 16, 26);
        int barW = Math.round(contentW * 0.9f);
        searchBarX1 = contentX + (contentW - barW) / 2;
        searchBarX2 = searchBarX1 + barW;
        searchBarY1 = contentTop;
        searchBarY2 = contentTop + barH;
        int clearW = clamp(Math.round(barH * 0.7f), 12, 20);
        searchClearX2 = searchBarX2 - 4;
        searchClearX1 = searchClearX2 - clearW;
        searchListTop = searchBarY2 + clamp(Math.round(rowH * 0.3f), 4, 10);
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

    private void drawSearch(int mouseX, int mouseY) {
        String q = searchQuery.toString();

        // search bar
        GuiRender.roundedRect(searchBarX1, searchBarY1, searchBarX2, searchBarY2, CARD_R, CARD_OFF_BG);
        GuiRender.roundedRectOutline(searchBarX1, searchBarY1, searchBarX2, searchBarY2, CARD_R, 0.75f, BTN_BORDER);
        float barCy = (searchBarY1 + searchBarY2) / 2f;
        float iconSize = (searchBarY2 - searchBarY1) * 0.5f;
        Icons.draw(SEARCH_SECTION, searchBarX1 + 7 + iconSize / 2f, barCy, iconSize, TEXT_MID);
        float textX = searchBarX1 + 7 + iconSize + 6;
        float textRight = searchClearX1 - 6;
        float ty = vcenter(searchBarY1, searchBarY2 - searchBarY1, valueScale);
        if (q.isEmpty()) {
            GuiRender.text("Search...", textX, ty, valueScale, TEXT_LO);
        } else {
            GuiRender.text(ellipsize(q, valueScale, textRight - textX), textX, ty, valueScale, TEXT_HI);
        }
        if ((caretBlink / 6) % 2 == 0) { // the bar is always focused on this page
            float cx = Math.min(textX + GuiRender.textWidth(q, valueScale), textRight);
            GuiRender.rect(cx, ty - 1, cx + 1, ty + BedwarsQolFont.height(valueScale), TEXT_HI);
        }
        if (!q.isEmpty()) {
            boolean xHover = GuiRender.inside(mouseX, mouseY, searchClearX1, searchBarY1, searchClearX2, searchBarY2);
            drawCross((searchClearX1 + searchClearX2) / 2f, barCy, (searchBarY2 - searchBarY1) * 0.16f,
                    xHover ? TEXT_HI : TEXT_MID);
        }

        // results: identical module cards to a section (laid out by layoutSearch), or an empty-state line.
        if (searchResults.isEmpty()) {
            GuiRender.textCentered("No matches", (contentX + contentRight) / 2f,
                    searchListTop + (searchListBottom - searchListTop) / 2f - BedwarsQolFont.height(valueScale) / 2f,
                    valueScale, TEXT_LO);
        } else {
            drawCards(mouseX, mouseY);
        }
    }

    private void searchClick(int mouseX, int mouseY, ClientSettings cfg) {
        if (searchQuery.length() > 0
                && GuiRender.inside(mouseX, mouseY, searchClearX1, searchBarY1, searchClearX2, searchBarY2)) {
            searchQuery.setLength(0);
            resetScroll();
            layoutSearch();
            playClick();
            return;
        }
        // The results are real module cards: same click model as a section (body = enable, "+" = expand).
        cardsClick(mouseX, mouseY, cfg);
    }

    private void searchKey(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (searchQuery.length() > 0) {
                searchQuery.setLength(0);
                resetScroll();
                layoutSearch();
            } else {
                mc.displayGuiScreen(null); // ESC on an empty search closes the GUI
            }
            return;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (searchQuery.length() > 0) searchQuery.deleteCharAt(searchQuery.length() - 1);
            caretBlink = 0;
            resetScroll();
            layoutSearch();
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && searchQuery.length() < 48) {
            searchQuery.append(typedChar);
            caretBlink = 0;
            resetScroll();
            layoutSearch();
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
}
