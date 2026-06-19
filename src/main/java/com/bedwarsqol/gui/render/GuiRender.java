package com.bedwarsqol.gui.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Minimal immediate-mode draw kit for custom GUIs, built only on the LWJGL2-era stack that
 * Minecraft 1.8.9 ships ({@link GlStateManager} / {@link Tessellator} / {@link GL11}) plus
 * {@link FontRenderer} — no NanoVG (that needs LWJGL3, which 1.8.9 doesn't have). It mirrors the
 * immediate-mode pattern in {@code feature/NametagStats.drawLineAbove} and the scaled-text
 * pattern in {@code hud/BedwarsHudRenderer.drawScaledString}.
 *
 * <p>Colors are ARGB ints (e.g. {@code 0xFF4A90E2}). Every shape is wrapped in
 * {@link #beginShapes()}/{@link #endShapes()} so GL state is always restored — in particular
 * texturing is re-enabled and the color reset to white <b>before any text is drawn</b> (forgetting
 * this tints/garbles the font — the classic bug), blending is turned back off so nothing leaks
 * into the rest of the frame, and face culling is toggled off while drawing so triangle-fan
 * corners can't be culled by winding order.
 */
public final class GuiRender {

    private GuiRender() {
    }

    // ---- ARGB channel helpers (normalized 0..1) ----
    private static float a(int c) { return (c >>> 24 & 0xFF) / 255f; }
    private static float r(int c) { return (c >> 16 & 0xFF) / 255f; }
    private static float g(int c) { return (c >> 8 & 0xFF) / 255f; }
    private static float b(int c) { return (c & 0xFF) / 255f; }

    /** Anti-alias fringe width (GUI px): edges get a soft alpha ramp to 0 for coverage AA (~1 device px). */
    private static final float AA = 0.5f;

    private static void beginShapes() {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void endShapes() {
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Filled rectangle (the workhorse). Color carries its own alpha in the high byte. */
    public static void rect(float x1, float y1, float x2, float y2, int color) {
        if (x1 > x2) { float t = x1; x1 = x2; x2 = t; }
        if (y1 > y2) { float t = y1; y1 = y2; y2 = t; }
        beginShapes();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        float rr = r(color), gg = g(color), bb = b(color), aa = a(color);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x1, y2, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(x2, y2, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(x2, y1, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(x1, y1, 0).color(rr, gg, bb, aa).endVertex();
        tess.draw();
        endShapes();
    }

    /** Vertical gradient fill from {@code top} (at y1) to {@code bottom} (at y2). */
    public static void gradientRect(float x1, float y1, float x2, float y2, int top, int bottom) {
        if (x1 > x2) { float t = x1; x1 = x2; x2 = t; }
        if (y1 > y2) { float t = y1; y1 = y2; y2 = t; int c = top; top = bottom; bottom = c; }
        beginShapes();
        GlStateManager.shadeModel(GL11.GL_SMOOTH); // interpolate per-vertex color
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x1, y2, 0).color(r(bottom), g(bottom), b(bottom), a(bottom)).endVertex();
        wr.pos(x2, y2, 0).color(r(bottom), g(bottom), b(bottom), a(bottom)).endVertex();
        wr.pos(x2, y1, 0).color(r(top), g(top), b(top), a(top)).endVertex();
        wr.pos(x1, y1, 0).color(r(top), g(top), b(top), a(top)).endVertex();
        tess.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        endShapes();
    }

    /** Rectangle outline drawn as four thin filled rects. */
    public static void border(float x1, float y1, float x2, float y2, int color, float t) {
        rect(x1, y1, x2, y1 + t, color);           // top
        rect(x1, y2 - t, x2, y2, color);           // bottom
        rect(x1, y1 + t, x1 + t, y2 - t, color);   // left
        rect(x2 - t, y1 + t, x2, y2 - t, color);   // right
    }

    /** 1px horizontal divider line spanning [x1, x2] at y. */
    public static void divider(float x1, float x2, float y, int color) {
        rect(x1, y, x2, y + 1, color);
    }

    /**
     * Rounded-rectangle fill: a "plus" of rects for the body plus a triangle-fan in each corner.
     * Radius is clamped so it never exceeds half the smaller side; sub-pixel radii fall back to a
     * plain rect.
     */
    public static void roundedRect(float x1, float y1, float x2, float y2, float radius, int color) {
        if (x1 > x2) { float t = x1; x1 = x2; x2 = t; }
        if (y1 > y2) { float t = y1; y1 = y2; y2 = t; }
        radius = Math.min(radius, Math.min(x2 - x1, y2 - y1) / 2f);
        if (radius <= 0.5f) {
            rect(x1, y1, x2, y2, color);
            return;
        }
        rect(x1 + radius, y1, x2 - radius, y2, color);              // middle column (full height)
        rect(x1, y1 + radius, x1 + radius, y2 - radius, color);     // left column
        rect(x2 - radius, y1 + radius, x2, y2 - radius, color);     // right column
        cornerFan(x1 + radius, y1 + radius, radius, 180f, color);   // top-left
        cornerFan(x2 - radius, y1 + radius, radius, 270f, color);   // top-right
        cornerFan(x2 - radius, y2 - radius, radius, 0f, color);     // bottom-right
        cornerFan(x1 + radius, y2 - radius, radius, 90f, color);    // bottom-left
        // soft AA fringe along the straight edges so the whole silhouette matches the feathered corners
        fringeEdge(x1 + radius, y1, x2 - radius, y1, 0f, -1f, color); // top
        fringeEdge(x1 + radius, y2, x2 - radius, y2, 0f, 1f, color);  // bottom
        fringeEdge(x1, y1 + radius, x1, y2 - radius, -1f, 0f, color); // left
        fringeEdge(x2, y1 + radius, x2, y2 - radius, 1f, 0f, color);  // right
    }

    private static void cornerFan(float cx, float cy, float radius, float startDeg, int color) {
        int seg = Math.max(8, Math.round(radius * 1.5f)); // enough segments so the curve isn't faceted
        beginShapes();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        float rr = r(color), gg = g(color), bb = b(color), aa = a(color);
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(cx, cy, 0).color(rr, gg, bb, aa).endVertex();
        for (int i = 0; i <= seg; i++) {
            double ang = Math.toRadians(startDeg + 90.0 * i / seg);
            wr.pos(cx + Math.cos(ang) * radius, cy + Math.sin(ang) * radius, 0)
                    .color(rr, gg, bb, aa).endVertex();
        }
        tess.draw();
        endShapes();
        fringeArc(cx, cy, radius, radius + AA, startDeg, startDeg + 90f, seg, color); // soft outer edge
    }

    /** AA fringe along a circular arc: inner radius at full color, outer radius at alpha 0 (a ~1px ramp). */
    private static void fringeArc(float cx, float cy, float rInner, float rOuter,
                                  float startDeg, float endDeg, int seg, int color) {
        beginShapes();
        GlStateManager.shadeModel(GL11.GL_SMOOTH); // interpolate the alpha ramp across the strip
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        float rr = r(color), gg = g(color), bb = b(color), aa = a(color);
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= seg; i++) {
            double ang = Math.toRadians(startDeg + (endDeg - startDeg) * i / seg);
            double cos = Math.cos(ang), sin = Math.sin(ang);
            wr.pos(cx + cos * rInner, cy + sin * rInner, 0).color(rr, gg, bb, aa).endVertex();
            wr.pos(cx + cos * rOuter, cy + sin * rOuter, 0).color(rr, gg, bb, 0f).endVertex();
        }
        tess.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        endShapes();
    }

    /** AA fringe along a straight edge a-&gt;b: the edge at full color, pushed out by {@link #AA} along the
     *  outward unit normal (nx,ny) to alpha 0. Same RGB at both ends so there is no dark halo. */
    private static void fringeEdge(float ax, float ay, float bx, float by, float nx, float ny, int color) {
        beginShapes();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        float rr = r(color), gg = g(color), bb = b(color), aa = a(color);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(ax, ay, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(bx, by, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(bx + nx * AA, by + ny * AA, 0).color(rr, gg, bb, 0f).endVertex();
        wr.pos(ax + nx * AA, ay + ny * AA, 0).color(rr, gg, bb, 0f).endVertex();
        tess.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        endShapes();
    }

    /** Left-aligned Inter text (flat, no shadow — the clean GUI look). */
    public static void text(String s, float x, float y, float scale, int color) {
        BedwarsQolFont.draw(s, x, y, scale, color, false);
    }

    public static void text(String s, float x, float y, float scale, int color, BedwarsQolFont.Weight weight) {
        BedwarsQolFont.draw(s, x, y, scale, color, false, weight);
    }

    /** Inter text centered horizontally around {@code centerX}. */
    public static void textCentered(String s, float centerX, float y, float scale, int color) {
        textCentered(s, centerX, y, scale, color, BedwarsQolFont.Weight.REGULAR);
    }

    public static void textCentered(String s, float centerX, float y, float scale, int color, BedwarsQolFont.Weight weight) {
        float w = BedwarsQolFont.width(s, scale, weight);
        BedwarsQolFont.draw(s, centerX - w / 2f, y, scale, color, false, weight);
    }

    /** Width of an Inter string at the given scale. */
    public static float textWidth(String s, float scale) {
        return BedwarsQolFont.width(s, scale);
    }

    public static float textWidth(String s, float scale, BedwarsQolFont.Weight weight) {
        return BedwarsQolFont.width(s, scale, weight);
    }

    /** Filled circle (triangle fan) with a soft AA fringe on the rim. */
    public static void circle(float cx, float cy, float radius, int color) {
        if (radius <= 0f) return;
        int seg = Math.max(18, Math.round(radius * 3f));
        beginShapes();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        float rr = r(color), gg = g(color), bb = b(color), aa = a(color);
        wr.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(cx, cy, 0).color(rr, gg, bb, aa).endVertex();
        for (int i = 0; i <= seg; i++) {
            double ang = Math.PI * 2.0 * i / seg;
            wr.pos(cx + Math.cos(ang) * radius, cy + Math.sin(ang) * radius, 0).color(rr, gg, bb, aa).endVertex();
        }
        tess.draw();
        endShapes();
        fringeArc(cx, cy, radius, radius + AA, 0f, 360f, seg, color); // soft rim
    }

    /** Line segment drawn as an oriented quad with a STRADDLED AA fringe (ramp centered on each edge),
     *  so the visible footprint is {@code thick + AA} — matching {@link #roundedRectOutline} hairlines. */
    public static void line(float x1, float y1, float x2, float y2, float thick, int color) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return;
        float nx = -dy / len, ny = dx / len;            // unit normal
        float hi = Math.max(0f, thick / 2f - AA / 2f);  // inner solid half-width
        float ho = thick / 2f + AA / 2f;                // outer (alpha 0) half-width
        beginShapes();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        float rr = r(color), gg = g(color), bb = b(color), aa = a(color);
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        if (hi > 0f) { // solid core (skipped for sub-AA-thin lines, where the two ramps meet)
            wr.pos(x1 + nx * hi, y1 + ny * hi, 0).color(rr, gg, bb, aa).endVertex();
            wr.pos(x2 + nx * hi, y2 + ny * hi, 0).color(rr, gg, bb, aa).endVertex();
            wr.pos(x2 - nx * hi, y2 - ny * hi, 0).color(rr, gg, bb, aa).endVertex();
            wr.pos(x1 - nx * hi, y1 - ny * hi, 0).color(rr, gg, bb, aa).endVertex();
        }
        // +side fringe: hi (full) -> ho (0)
        wr.pos(x1 + nx * hi, y1 + ny * hi, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(x2 + nx * hi, y2 + ny * hi, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(x2 + nx * ho, y2 + ny * ho, 0).color(rr, gg, bb, 0f).endVertex();
        wr.pos(x1 + nx * ho, y1 + ny * ho, 0).color(rr, gg, bb, 0f).endVertex();
        // -side fringe
        wr.pos(x1 - nx * hi, y1 - ny * hi, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(x2 - nx * hi, y2 - ny * hi, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(x2 - nx * ho, y2 - ny * ho, 0).color(rr, gg, bb, 0f).endVertex();
        wr.pos(x1 - nx * ho, y1 - ny * ho, 0).color(rr, gg, bb, 0f).endVertex();
        tess.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        endShapes();
    }

    /** Vector chevron (points left when pointLeft, else right) centered at (cx,cy). Drawn as ONE mitered
     *  AA stroke so the two arms converge to a single sharp point — no butt-cap notch at the tip. */
    public static void chevron(float cx, float cy, float halfW, float halfH, boolean pointLeft, float thick, int color) {
        float tipX = pointLeft ? cx - halfW : cx + halfW;
        float backX = pointLeft ? cx + halfW : cx - halfW;
        strokeMiter(backX, cy - halfH, tipX, cy, backX, cy + halfH, thick, color);
    }

    /** Stroke the open polyline p0->p1->p2 as a single AA band of width {@code thick} with a sharp miter
     *  join at p1 and butt caps at the ends — gives crisp, properly-converging chevron tips. */
    private static void strokeMiter(float p0x, float p0y, float p1x, float p1y, float p2x, float p2y,
                                    float thick, int color) {
        float h = thick / 2f;
        float d1x = p1x - p0x, d1y = p1y - p0y; float l1 = (float) Math.sqrt(d1x * d1x + d1y * d1y);
        float d2x = p2x - p1x, d2y = p2y - p1y; float l2 = (float) Math.sqrt(d2x * d2x + d2y * d2y);
        if (l1 < 1e-4f || l2 < 1e-4f) return;
        d1x /= l1; d1y /= l1; d2x /= l2; d2y /= l2;
        float n1x = -d1y, n1y = d1x, n2x = -d2y, n2y = d2x;        // left-hand normals
        float mx = n1x + n2x, my = n1y + n2y;
        float ml = (float) Math.sqrt(mx * mx + my * my);
        if (ml < 1e-3f) { line(p0x, p0y, p1x, p1y, thick, color); line(p1x, p1y, p2x, p2y, thick, color); return; }
        mx /= ml; my /= ml;                                        // unit miter direction
        float dot = mx * n1x + my * n1y;
        if (dot < 0.2f && dot > -0.2f) dot = dot < 0 ? -0.2f : 0.2f; // clamp the sharp-angle miter spike
        float mlen = h / dot;
        float p0Lx = p0x + n1x * h, p0Ly = p0y + n1y * h, p0Rx = p0x - n1x * h, p0Ry = p0y - n1y * h;
        float p1Lx = p1x + mx * mlen, p1Ly = p1y + my * mlen, p1Rx = p1x - mx * mlen, p1Ry = p1y - my * mlen;
        float p2Lx = p2x + n2x * h, p2Ly = p2y + n2y * h, p2Rx = p2x - n2x * h, p2Ry = p2y - n2y * h;
        float rr = r(color), gg = g(color), bb = b(color), aa = a(color);
        beginShapes();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(p0Lx, p0Ly, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(p0Rx, p0Ry, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(p1Lx, p1Ly, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(p1Rx, p1Ry, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(p2Lx, p2Ly, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(p2Rx, p2Ry, 0).color(rr, gg, bb, aa).endVertex();
        tess.draw();
        endShapes();
        strokeFringe(p0Lx, p0Ly, n1x, n1y, p1Lx, p1Ly, mx, my, p2Lx, p2Ly, n2x, n2y, color);       // left edge
        strokeFringe(p0Rx, p0Ry, -n1x, -n1y, p1Rx, p1Ry, -mx, -my, p2Rx, p2Ry, -n2x, -n2y, color); // right edge
    }

    /** One side's AA fringe for {@link #strokeMiter}: three on-edge points (full alpha) each pushed out
     *  by {@link #AA} along the given unit direction to alpha 0. */
    private static void strokeFringe(float ax, float ay, float adx, float ady,
                                     float bx, float by, float bdx, float bdy,
                                     float cx, float cy, float cdx, float cdy, int color) {
        beginShapes();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        float rr = r(color), gg = g(color), bb = b(color), aa = a(color);
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(ax, ay, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(ax + adx * AA, ay + ady * AA, 0).color(rr, gg, bb, 0f).endVertex();
        wr.pos(bx, by, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(bx + bdx * AA, by + bdy * AA, 0).color(rr, gg, bb, 0f).endVertex();
        wr.pos(cx, cy, 0).color(rr, gg, bb, aa).endVertex();
        wr.pos(cx + cdx * AA, cy + cdy * AA, 0).color(rr, gg, bb, 0f).endVertex();
        tess.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        endShapes();
    }

    /**
     * Stroke around a rounded rectangle's full perimeter, drawn as a SINGLE continuous band so the
     * corners are exactly as thick as the straight edges and join seamlessly. The whole outline (4
     * quarter-arcs + the 4 straight runs between their tangents) is one closed triangle strip from
     * the inner rail {@code R-thick} to the outer rail {@code R}, plus a symmetric anti-alias fringe
     * on BOTH rails. One layer of geometry everywhere → no double-blend, no heavy corners. (This is
     * the immediate-mode equivalent of NanoVG's single-strip stroke expansion.)
     */
    public static void roundedRectOutline(float x1, float y1, float x2, float y2, float radius, float thick, int color) {
        if (x1 > x2) { float t = x1; x1 = x2; x2 = t; }
        if (y1 > y2) { float t = y1; y1 = y2; y2 = t; }
        radius = Math.min(radius, Math.min(x2 - x1, y2 - y1) / 2f);
        if (radius <= 0.5f) {
            border(x1, y1, x2, y2, color, thick);
            return;
        }
        float t = Math.min(thick, radius); // keep the inner rail R-t >= 0

        // Sample the perimeter once as (point-on-R, outward-normal) pairs. Each corner is an
        // inclusive 90° arc; a corner's last tangent and the next corner's first tangent bound a
        // straight edge, so corners AND edges come from the same point list with no extra pieces.
        int per = Math.max(8, Math.round(radius * 1.5f)); // samples per corner (matches the fill curve)
        int n = 4 * (per + 1);
        float[] px = new float[n], py = new float[n], nx = new float[n], ny = new float[n];
        float[] ccx = {x1 + radius, x2 - radius, x2 - radius, x1 + radius};
        float[] ccy = {y1 + radius, y1 + radius, y2 - radius, y2 - radius};
        float[] start = {180f, 270f, 0f, 90f};
        int idx = 0;
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i <= per; i++) {
                double ang = Math.toRadians(start[c] + 90.0 * i / per);
                float ax = (float) Math.cos(ang), ay = (float) Math.sin(ang);
                nx[idx] = ax; ny[idx] = ay;
                px[idx] = ccx[c] + ax * radius;
                py[idx] = ccy[c] + ay * radius;
                idx++;
            }
        }
        // Straddle the AA ramp on each rail (ramp centered on the true edge) so the stroke sits AT its
        // nominal edges and the visible footprint is ~(thick + AA), not (thick + 2*AA) — a thin hairline.
        float h = AA * 0.5f;
        if (t > AA) {
            contourBand(px, py, nx, ny, n, -h, 1f, -(t - h), 1f, color);     // solid core
        }
        contourBand(px, py, nx, ny, n, -h, 1f, h, 0f, color);               // outer edge AA (straddles R)
        contourBand(px, py, nx, ny, n, -(t - h), 1f, -(t + h), 0f, color);   // inner edge AA (straddles R-t)
    }

    /** Closed triangle-strip band around a precomputed perimeter (point on R + outward normal). Each
     *  point emits two vertices offset along its normal — {@code offA}@alpha {@code aA}, then
     *  {@code offB}@alpha {@code aB} (alpha multipliers 0..1) — and the loop is closed by repeating
     *  the first point. Same RGB at every vertex (only alpha varies) so the fringe never darkens. */
    private static void contourBand(float[] px, float[] py, float[] nx, float[] ny, int n,
                                    float offA, float aA, float offB, float aB, int color) {
        beginShapes();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        float rr = r(color), gg = g(color), bb = b(color), base = a(color);
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int k = 0; k <= n; k++) {
            int i = k % n;
            wr.pos(px[i] + nx[i] * offA, py[i] + ny[i] * offA, 0).color(rr, gg, bb, base * aA).endVertex();
            wr.pos(px[i] + nx[i] * offB, py[i] + ny[i] * offB, 0).color(rr, gg, bb, base * aB).endVertex();
        }
        tess.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        endShapes();
    }

    /** Point-in-rect test (inclusive). */
    public static boolean inside(int mx, int my, float x1, float y1, float x2, float y2) {
        return mx >= x1 && mx <= x2 && my >= y1 && my <= y2;
    }

    /**
     * Clip all subsequent drawing to the GUI-space rectangle [x1,y1]-[x2,y2] (used to keep a
     * scrollable list inside its viewport). Coordinates are in scaled GUI pixels; this converts them
     * to the real framebuffer pixels {@code glScissor} expects (origin bottom-left, scaled by the GUI
     * factor). Always pair with {@link #endScissor()}.
     */
    public static void beginScissor(float x1, float y1, float x2, float y2) {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc);
        int sf = sr.getScaleFactor();
        int fbH = mc.displayHeight;
        int sx = Math.round(x1 * sf);
        int sy = Math.round(fbH - y2 * sf);
        int sw = Math.max(0, Math.round((x2 - x1) * sf));
        int sh = Math.max(0, Math.round((y2 - y1) * sf));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, sw, sh);
    }

    public static void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
