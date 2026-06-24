package com.bedwarsqol.config;

/**
 * A user-defined chat macro: press {@link #keyCode} in-game (no GUI open) to send {@link #message}
 * in chat. A message starting with {@code /} runs as a command. Persisted as part of
 * {@link ClientSettings} via Gson, so the no-arg constructor + public fields are intentional.
 */
public class Keybind {

    /** LWJGL key code ({@code org.lwjgl.input.Keyboard.KEY_*}); {@code 0} = unbound. */
    public int keyCode;
    /** Chat message (or {@code /command}) sent when the key is pressed. */
    public String message = "";

    public Keybind() {
    }

    public Keybind(int keyCode, String message) {
        this.keyCode = keyCode;
        this.message = message == null ? "" : message;
    }
}
