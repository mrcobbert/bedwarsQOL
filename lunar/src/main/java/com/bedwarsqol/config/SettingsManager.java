package com.bedwarsqol.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.bedwarsqol.BedwarsQol;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

public final class SettingsManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SettingsManager() {
    }

    public static ClientSettings load() {
        File file = settingsFile();
        if (!file.isFile()) {
            ClientSettings defaults = new ClientSettings();
            save(defaults);
            return defaults;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            ClientSettings settings = GSON.fromJson(reader, ClientSettings.class);
            if (settings == null) settings = new ClientSettings();
            settings.sanitize();
            return settings;
        } catch (Exception ignored) {
            ClientSettings defaults = new ClientSettings();
            defaults.sanitize();
            return defaults;
        }
    }

    public static void save(ClientSettings settings) {
        if (settings == null) return;
        settings.sanitize();
        File file = settingsFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(settings, writer);
        } catch (IOException ignored) {
        }
    }

    /** Mod data dir (~/.bedwarsqol); Forge's config dir is unavailable under Weave. */
    private static File settingsFile() {
        File dir = new File(System.getProperty("user.home"), ".bedwarsqol");
        return new File(dir, BedwarsQol.MODID + ".json");
    }
}
