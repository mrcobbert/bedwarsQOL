package com.bedwarsqol.gui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the disabled-child contract behind finding [I4]: an expanded sub-option row is interactive only when
 * its parent module's master toggle is on, while synthetic GROUP children (Settings / Debug) are always live
 * regardless of their non-toggle parent kind. {@link SettingsGui#childEnabled(boolean, boolean)} is the pure
 * predicate that both the renderer and the click dispatch consult.
 */
public class ChildEnabledTest {

    @Test
    public void groupChildIsAlwaysEnabled() {
        assertTrue("GROUP child stays live even when parent flag is off",
                SettingsGui.childEnabled(true, false));
        assertTrue("GROUP child stays live when parent flag is on",
                SettingsGui.childEnabled(true, true));
    }

    @Test
    public void moduleChildFollowsParentToggle() {
        assertTrue("MODULE child enabled when its parent master toggle is on",
                SettingsGui.childEnabled(false, true));
        assertFalse("MODULE child disabled when its parent master toggle is off",
                SettingsGui.childEnabled(false, false));
    }
}
