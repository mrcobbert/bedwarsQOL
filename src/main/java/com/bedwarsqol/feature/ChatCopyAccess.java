package com.bedwarsqol.feature;

/**
 * Implemented on the vanilla {@code GuiNewChat} by {@code mixin/GuiNewChatMixin} so the Copy Chat
 * right-click handler in {@code mixin/GuiChatMixin} can ask the chat GUI which full message sits under
 * the cursor. Vanilla's own {@code getChatComponent} only returns the clicked sibling of a single
 * <i>wrapped</i> line; this resolves back to the whole original message.
 */
public interface ChatCopyAccess {

    /** Formatted text of the full chat message under the raw mouse position, or null when none. */
    String bedwarsqol$fullTextAt(int rawMouseX, int rawMouseY);
}
