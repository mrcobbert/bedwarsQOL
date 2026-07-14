package com.bedwarsqol.feature;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared denick state and skin-decoding, used by {@link NickUtils} (detection + announce), the chat
 * annotation ({@link ChatNameTags}), and the tab-list overlay ({@code GuiPlayerTabOverlayMixin}).
 *
 * <p><b>How the denick works (and why it is undetectable).</b> A nicked player who keeps their own
 * skin has Hypixel forward the original Mojang-signed {@code textures} property. That base64 blob is
 * JSON embedding the skin owner's real {@code profileName} — the genuine account, because Mojang signs
 * it. We decode a {@link GameProfile} we were already sent: zero requests, nothing transmitted, pure
 * local parsing, so Hypixel cannot observe it (see {@code nick-denick-via-signed-skin}). A player whose
 * signed skin names the same account as their in-game name isn't nicked; a mismatch is (UUID is
 * immutable across renames, so a mere rename is not a false positive).
 *
 * <p><b>Context gate ({@link #identitiesVisible()}).</b> Since ~Aug 2024 Hypixel obfuscates names and
 * strips skins in the pregame waiting lobby — and the pregame→game transition briefly pairs obfuscated
 * tab names with <i>real</i> signed skins, so "any signed skin present" is NOT proof identities are real
 * (that definition once let the whole lobby false-denick, ourselves included). The scan in
 * {@link NickUtils} therefore only reports identities visible when it saw at least one
 * identity-consistent real row (v4 UUID whose signed skin names its own tab account) and no
 * obfuscation-window tripwire fired. The dependent surfaces stay silent when it is false.
 */
public final class Denicks {

    private Denicks() {}

    /** "profileName" inside the base64-decoded, Mojang-signed skin "textures" blob = the real account. */
    private static final Pattern PROFILE_NAME = Pattern.compile("\"profileName\"\\s*:\\s*\"([^\"]+)\"");

    /** "profileId" in the same blob = the skin owner's real account UUID (32 hex chars, no dashes). */
    private static final Pattern PROFILE_ID = Pattern.compile("\"profileId\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");

    /** Lower-cased in-game nick -> recovered real account name, for nicked-with-kept-skin players. */
    private static final Map<String, String> REAL_BY_NICK = new ConcurrentHashMap<String, String>();

    /** Set true whenever the latest scan decoded at least one valid signed skin (real identities present). */
    private static volatile boolean identitiesVisible = false;

    /** Reset all per-world state (called on world load). */
    public static void clear() {
        REAL_BY_NICK.clear();
        identitiesVisible = false;
    }

    public static boolean identitiesVisible() {
        return identitiesVisible;
    }

    public static void setIdentitiesVisible(boolean visible) {
        identitiesVisible = visible;
    }

    /** Record a denicked player: their in-game nick now resolves to {@code real}. */
    public static void put(String inGameNick, String real) {
        if (inGameNick == null || inGameNick.isEmpty() || real == null || real.isEmpty()) return;
        REAL_BY_NICK.put(inGameNick.toLowerCase(), real);
    }

    /** The recovered real name for an in-game nick, or null if we haven't denicked them. */
    public static String realNameForNick(String inGameName) {
        if (inGameName == null || inGameName.isEmpty()) return null;
        return REAL_BY_NICK.get(inGameName.toLowerCase());
    }

    /** The real identity a Mojang-signed skin embeds: account name plus (when present) undashed UUID hex. */
    public static final class SkinIdentity {
        public final String name;
        public final String idHex; // null on blobs that omit profileId

        SkinIdentity(String name, String idHex) {
            this.name = name;
            this.idHex = idHex;
        }
    }

    /** Decode the player's Mojang-signed skin property; return the real identity it embeds, or null. */
    public static SkinIdentity identityFromSkin(GameProfile profile) {
        if (profile == null) return null;
        Collection<Property> textures = profile.getProperties().get("textures");
        if (textures == null || textures.isEmpty()) return null;
        Property prop = textures.iterator().next();
        if (prop == null || prop.getValue() == null) return null;
        String json;
        try {
            json = new String(Base64.getDecoder().decode(prop.getValue()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badBase64) {
            return null;
        }
        Matcher name = PROFILE_NAME.matcher(json);
        if (!name.find()) return null;
        Matcher id = PROFILE_ID.matcher(json);
        return new SkinIdentity(name.group(1), id.find() ? id.group(1) : null);
    }
}
