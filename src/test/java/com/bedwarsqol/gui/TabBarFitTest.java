package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Pins the guarantee behind finding [I1]: {@link SettingsGui#fitTabBar} must shrink the tab strip until it
 * provably fits the available span, so the six Small-size tabs can never overlap the reserved search / Edit
 * HUD cluster. The synthetic case mirrors the Small / minimum-viewport numbers (six wide labels, a tight
 * available span) that overflow at the starting scale and only fit after the solver shrinks.
 */
public class TabBarFitTest {

    // Six labels measured at scale 1.0, summing to 170 — wider than the shipped Inter metrics, so the strip
    // overflows the starting scale and the solver is forced to shrink.
    private static final float[] TAB_W1 = {24f, 36f, 33f, 35f, 30f, 12f};

    // A tight available span (the tabs-left → reserved-right-cluster distance at Small), well below the
    // starting strip width but above the floor strip width, so a correct solver lands in between.
    private static final float AVAILABLE = 120f;

    // Starting geometry and hard safety floors, exactly as initGui passes them at Small.
    private static final float START_SCALE = 0.82f, START_PAD = 6f, START_GAP = 3f;
    private static final float MIN_SCALE = 0.34f, MIN_PAD = 2f, MIN_GAP = 2f;

    @Test
    public void fittedStripNeverOverlapsTheReservedRightCluster() {
        float[] fit = SettingsGui.fitTabBar(TAB_W1, AVAILABLE,
                START_SCALE, START_PAD, START_GAP, MIN_SCALE, MIN_PAD, MIN_GAP);
        float scale = fit[0], padX = fit[1], gap = fit[2];

        // Returned geometry stays within [floor, start].
        assertTrue("scale within bounds", scale >= MIN_SCALE - 1e-4f && scale <= START_SCALE + 1e-4f);
        assertTrue("padX within bounds", padX >= MIN_PAD - 1e-4f && padX <= START_PAD + 1e-4f);
        assertTrue("gap within bounds", gap >= MIN_GAP - 1e-4f && gap <= START_GAP + 1e-4f);

        // Walk the tabs the way initGui does (in span coordinates, tabsLeft = 0). No tab box may cross into
        // the reserved right cluster, whose left edge sits at `AVAILABLE`.
        float cursor = 0f;
        for (int i = 0; i < TAB_W1.length; i++) {
            float w = TAB_W1[i] * scale + 2 * padX;
            float x2 = cursor + w;
            assertTrue("tab " + i + " right edge " + x2 + " must stay left of the cluster " + AVAILABLE,
                    x2 <= AVAILABLE + 1e-3f);
            cursor += w + gap;
        }
        // cursor now equals tabsRight + gap; the non-overlap invariant is tabsRight + gap <= available.
        assertTrue("tabsRight + gap " + cursor + " must be <= available " + AVAILABLE,
                cursor <= AVAILABLE + 1e-3f);

        // And the case really is stressful: at the starting scale the strip overflows, so the solver shrank.
        float startNeed = START_SCALE * sum(TAB_W1) + 2 * TAB_W1.length * START_PAD + TAB_W1.length * START_GAP;
        assertTrue("starting strip should overflow the available span", startNeed > AVAILABLE);
    }

    private static float sum(float[] a) {
        float s = 0f;
        for (float v : a) s += v;
        return s;
    }
}
