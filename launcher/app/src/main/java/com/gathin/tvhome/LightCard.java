package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * A glass card for one WiZ light, smart plug or a group of them, in the band beside the weather. WiZ devices answer
 * a small JSON protocol over UDP port 38899 on the local network: no account, no cloud. Each device is found by its
 * MAC, so a new address from the router is picked up by a one-off sweep of the local /24. OK switches the card on or
 * off (the whole group together); holding OK on a card with lights opens adjust mode, where left and right change the
 * brightness by 10 % and, on a colour bulb, up and down step through colours; OK or Back leaves. Needs "lights" in a
 * theme's config.json; without it no card exists and nothing is sent.
 */
public final class LightCard extends View {

    /** One device behind the card. kind: "bulb" (dimmable), "rgb" (dimmable and coloured) or "plug" (on and off). */
    public static final class Device {
        final String mac;
        final String kind;
        volatile String ip;
        volatile boolean reachable, on;
        volatile int dimming = 100;

        public Device(String mac, String kind) {
            this.mac = mac.toLowerCase(Locale.ROOT).replace(":", "");
            this.kind = kind;
        }
        boolean dimmable() { return "bulb".equals(kind) || "rgb".equals(kind); }
    }

    private static final int PORT = 38899;
    private static final String PREFS = "wiz_ips";
    private static final long POLL_MS = 15_000;
    /** Colour steps for a colour bulb: name, r, g, b (a "white" has temp instead). */
    private static final Object[][] COLOURS = {
            {"Cálido", 0, 0, 0, 2700}, {"Neutro", 0, 0, 0, 4000}, {"Frío", 0, 0, 0, 6000},
            {"Rojo", 255, 40, 40, 0}, {"Naranja", 255, 120, 20, 0}, {"Verde", 40, 255, 90, 0},
            {"Cian", 30, 220, 255, 0}, {"Azul", 40, 90, 255, 0}, {"Violeta", 150, 70, 255, 0}, {"Rosa", 255, 70, 170, 0}};

    private final float density, sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG), status = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final String label;
    private final List<Device> devices;
    private final boolean hasColour, hasDimmer, round;
    private boolean known, busy, polling, adjust, swallowUp;
    private boolean alwaysLabel;
    private float scale = 1f;
    private int flashKey;
    private long flashUntil;

    private void flash(int keyCode) {
        flashKey = keyCode;
        flashUntil = System.currentTimeMillis() + 300;
        invalidate();
        main.postDelayed(this::invalidate, 320);
    }

    /** Size factor of the round button (config.json lights_scale_percent / 100). */
    public void setScale(float s) { scale = s; }

    /** Shows the name under the round button all the time, not only while it has focus. */
    public void setAlwaysLabel(boolean on) { alwaysLabel = on; }
    private int colourIndex = 0;
    private int shownColour = 0;   // 0 = white, else 0xRRGGBB of the colour bulb

    public LightCard(Context context, String label, List<Device> devices, boolean round) {
        super(context);
        this.label = label;
        this.devices = devices;
        this.round = round;
        boolean colour = false, dim = false;
        for (Device d : devices) { colour |= "rgb".equals(d.kind); dim |= d.dimmable(); }
        hasColour = colour;
        hasDimmer = dim;
        density = context.getResources().getDisplayMetrics().density;
        sp = context.getResources().getDisplayMetrics().scaledDensity;
        SharedPreferences p = prefs();
        for (Device d : devices) d.ip = p.getString(d.mac, null);
        setFocusable(true);
        setClickable(true);
        edge.setStyle(Paint.Style.STROKE);
        name.setTypeface(UiFont.get(context, R.string.font_medium));
        status.setTypeface(UiFont.get(context, R.string.font_medium));
    }

    // ---- polling and commands ------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        polling = true;
        poll.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        polling = false;
        main.removeCallbacks(poll);
        super.onDetachedFromWindow();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!polling) return;
            refresh();
            main.postDelayed(this, POLL_MS);
        }
    };

    public void refresh() {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                Thread[] ts = new Thread[devices.size()];
                for (int i = 0; i < ts.length; i++) {
                    Device d = devices.get(i);
                    ts[i] = new Thread(() -> {
                        JSONObject r = request(d, "getPilot", new JSONObject());
                        d.reachable = r != null;
                        JSONObject res = r == null ? null : r.optJSONObject("result");
                        if (res != null) {
                            d.on = res.optBoolean("state", false);
                            d.dimming = res.optInt("dimming", 100);
                            if ("rgb".equals(d.kind)) {
                                shownColour = res.has("r") && res.optInt("r") + res.optInt("g") + res.optInt("b") > 0
                                        ? ((res.optInt("r") & 0xFF) << 16 | (res.optInt("g") & 0xFF) << 8 | (res.optInt("b") & 0xFF)) : 0;
                            }
                        }
                    }, "wiz-poll");
                    ts[i].start();
                }
                for (Thread t : ts) t.join(4000);
            } catch (InterruptedException ignored) {
            } finally {
                busy = false;
                main.post(() -> { known = true; invalidate(); });
            }
        }, "wiz").start();
    }

    /** Sends a setPilot to every device of the card that accepts it, then refreshes. */
    private void command(JSONObject params, boolean dimmersOnly) {
        new Thread(() -> {
            for (Device d : devices) {
                if (dimmersOnly && !d.dimmable()) continue;
                new Thread(() -> request(d, "setPilot", params), "wiz-set").start();
            }
            main.postDelayed(this::refresh, 600);
        }, "wiz-cmd").start();
    }

    private JSONObject request(Device d, String method, JSONObject params) {
        String a = d.ip;
        if (a != null) {
            JSONObject r = send(a, method, params, 700);
            if (r != null && macMatches(r, d.mac)) return r;
        }
        String found = sweep(d.mac);
        if (found == null) return null;
        d.ip = found;
        prefs().edit().putString(d.mac, found).apply();
        return send(found, method, params, 900);
    }

    private static boolean macMatches(JSONObject r, String mac) {
        JSONObject res = r.optJSONObject("result");
        if (res == null) return true;
        String m = res.optString("mac", "");
        return m.isEmpty() || m.equalsIgnoreCase(mac);
    }

    private static JSONObject send(String host, String method, JSONObject params, int timeoutMs) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            byte[] out = new JSONObject().put("method", method).put("params", params).toString().getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(out, out.length, InetAddress.getByName(host), PORT));
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
        } catch (SocketTimeoutException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sweep(String mac) {
        String prefix = localPrefix();
        if (prefix == null) return null;
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(300);
            byte[] out = "{\"method\":\"getPilot\",\"params\":{}}".getBytes(StandardCharsets.UTF_8);
            for (int i = 1; i < 255; i++) {
                try { s.send(new DatagramPacket(out, out.length, InetAddress.getByName(prefix + i), PORT)); } catch (Exception ignored) { }
            }
            long end = System.currentTimeMillis() + 2500;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { s.receive(p); } catch (SocketTimeoutException e) { continue; }
                try {
                    JSONObject res = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8)).optJSONObject("result");
                    if (res != null && mac.equalsIgnoreCase(res.optString("mac"))) return p.getAddress().getHostAddress();
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String localPrefix() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface n = e.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                for (Enumeration<InetAddress> a = n.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress x = a.nextElement();
                    if (x instanceof Inet4Address && x.isSiteLocalAddress()) {
                        String h = x.getHostAddress();
                        return h.substring(0, h.lastIndexOf('.') + 1);
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- state ---------------------------------------------------------------------------------------

    private int onCount() { int n = 0; for (Device d : devices) if (d.reachable && d.on) n++; return n; }
    private int reachableCount() { int n = 0; for (Device d : devices) if (d.reachable) n++; return n; }
    private int dimLevel() {
        int sum = 0, n = 0;
        for (Device d : devices) if (d.reachable && d.on && d.dimmable()) { sum += d.dimming; n++; }
        return n == 0 ? 100 : sum / n;
    }

    // ---- keys ----------------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (adjust) {
            if (keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) flash(keyCode);
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int level = Math.max(10, Math.min(100, dimLevel() + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : -10)));
                for (Device d : devices) { if (d.dimmable()) { d.dimming = level; d.on = true; d.reachable = true; } }
                invalidate();
                try { command(new JSONObject().put("state", true).put("dimming", level), true); } catch (Exception ignored) { }
            } else if ((keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) && hasColour) {
                colourIndex = (colourIndex + (keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : COLOURS.length - 1)) % COLOURS.length;
                Object[] c = COLOURS[colourIndex];
                try {
                    JSONObject p = new JSONObject().put("state", true);
                    if ((Integer) c[4] > 0) p.put("temp", (Integer) c[4]);
                    else p.put("r", c[1]).put("g", c[2]).put("b", c[3]);
                    shownColour = (Integer) c[4] > 0 ? 0 : ((Integer) c[1] << 16 | (Integer) c[2] << 8 | (Integer) c[3]);
                    for (Device d : devices) if ("rgb".equals(d.kind)) { d.on = true; d.reachable = true; }
                    invalidate();
                    colourCommand(p);
                } catch (Exception ignored) { }
            }
            return true;   // everything stays put while adjusting; OK or Back leave on key up
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (hasDimmer) event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void colourCommand(JSONObject params) {
        new Thread(() -> {
            for (Device d : devices) if ("rgb".equals(d.kind)) new Thread(() -> request(d, "setPilot", params), "wiz-col").start();
            main.postDelayed(this::refresh, 600);
        }, "wiz-colcmd").start();
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (hasDimmer && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            adjust = true;
            swallowUp = true;   // the release of this very press must not close the mode it just opened
            invalidate();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (adjust) {
            if (swallowUp && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                swallowUp = false;
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BACK) {
                adjust = false;
                invalidate();
            }
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !event.isCanceled()
                && (event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
            toggle();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean performClick() {
        toggle();
        return true;
    }

    /** Any light on: all off. All off: all on. */
    private void toggle() {
        boolean turnOn = onCount() == 0;
        for (Device d : devices) { d.on = turnOn && d.reachable || turnOn && !known; }
        invalidate();
        try { command(new JSONObject().put("state", turnOn), false); } catch (Exception ignored) { }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) { adjust = false; swallowUp = false; }
        invalidate();
    }

    // ---- drawing -------------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (round) {   // a round button with room under it for the name, shown when the button has focus
            setMeasuredDimension((int) (68 * density * scale), (int) (95 * density * scale));
            return;
        }
        setMeasuredDimension(resolveSize((int) (118 * density), wSpec), resolveSize((int) (100 * density), hSpec));
    }

    /** Shrinks a paint's text size until the text fits the width. */
    private static void fit(Paint p, String text, float maxWidth, float startPx) {
        p.setTextSize(startPx);
        while (p.measureText(text) > maxWidth && p.getTextSize() > startPx * 0.6f) p.setTextSize(p.getTextSize() - 1f);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean focused = isFocused();
        int onN = onCount(), reach = reachableCount();
        boolean lit = known && onN > 0;
        if (round) { drawRound(c, focused, onN, reach, lit); return; }
        float w = getWidth(), h = getHeight(), r = 22 * density, sw = (focused ? 3f : 1.5f) * density;
        rect.set(sw, sw, w - sw, h - sw);
        int accent = !lit ? 0xFF8A94A6 : (hasColour && shownColour != 0 ? 0xFF000000 | shownColour : 0xFFFFC93C);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(focused ? 0x40FFFFFF : 0x1FFFFFFF);
        c.drawRoundRect(rect, r, r, fill);
        if (lit) {
            fill.setColor((accent & 0x00FFFFFF) | 0x2E000000);
            c.drawRoundRect(rect, r, r, fill);
        }
        edge.setStrokeWidth(sw);
        edge.setColor(focused ? 0xE6FFFFFF : 0x33FFFFFF);
        c.drawRoundRect(rect, r, r, edge);

        float s = 36 * density, cx = w / 2f, cy = h * 0.30f;
        String kind = devices.get(0).kind;
        if ("rgb".equals(kind)) drawBulb(c, cx, cy, s, accent, lit);
        else if ("bulb".equals(kind)) drawCeiling(c, cx, cy, s, accent);
        else if ("lamp".equals(kind)) drawLamp(c, cx, cy, s, accent);
        else drawPlug(c, cx, cy, s, accent);

        String st;
        if (!known) st = "…";
        else if (reach == 0) st = "Sin conexión";
        else if (onN == 0) st = "Apagada";
        else st = hasDimmer ? dimLevel() + "%" : "Encendida";
        if (adjust) st = hasColour ? "◀▶ brillo  ▲▼ color" : "◀ ▶ brillo";
        name.setTextAlign(Paint.Align.CENTER);
        status.setTextAlign(Paint.Align.CENTER);
        name.setColor(0xF2FFFFFF);
        status.setColor(0xB3FFFFFF);
        fit(name, label, w - 2 * 10 * density, 16 * sp);
        fit(status, st, w - 2 * 8 * density, 12 * sp);
        c.drawText(label, cx, h * 0.65f, name);
        c.drawText(st, cx, h * 0.65f + 18 * density, status);

        if (adjust) {   // brightness bar along the bottom edge
            float bw = w - 2 * 16 * density, bh = 4 * density, by = h - 11 * density;
            bar.setStyle(Paint.Style.FILL);
            bar.setColor(0x40FFFFFF);
            rect.set(16 * density, by, 16 * density + bw, by + bh);
            c.drawRoundRect(rect, bh, bh, bar);
            bar.setColor(0xFFFFFFFF);
            rect.set(16 * density, by, 16 * density + bw * dimLevel() / 100f, by + bh);
            c.drawRoundRect(rect, bh, bh, bar);
        }
    }

    /** The round button: a glass circle with the icon; the name and the state appear under it while it has focus. */
    private void drawRound(Canvas c, boolean focused, int onN, int reach, boolean lit) {
        float k = density * scale, w = getWidth(), d = 46 * k, cx = w / 2f, cy = d / 2f + 4 * k, sw = (focused ? 3f : 1.5f) * k;
        int accent = !lit ? 0xFF9AA3B5 : (hasColour && shownColour != 0 ? 0xFF000000 | shownColour : 0xFFFFC93C);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(focused ? 0x4DFFFFFF : 0x29FFFFFF);
        c.drawCircle(cx, cy, d / 2f - sw, fill);
        if (lit) {
            fill.setColor((accent & 0x00FFFFFF) | 0x33000000);
            c.drawCircle(cx, cy, d / 2f - sw, fill);
        }
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(sw);
        edge.setColor(focused ? 0xE6FFFFFF : 0x40FFFFFF);
        c.drawCircle(cx, cy, d / 2f - sw, edge);
        float s = 25 * k;
        String kind = devices.get(0).kind;
        if ("rgb".equals(kind)) drawBulb(c, cx, cy + 1 * k, s, accent, lit);
        else if ("bulb".equals(kind)) drawCeiling(c, cx, cy + 1 * k, s, accent);
        else if ("lamp".equals(kind)) drawLamp(c, cx, cy + 1 * k, s, accent);
        else drawPlug(c, cx, cy + 1 * k, s, accent);
        if (!focused && alwaysLabel) {
            name.setTextAlign(Paint.Align.CENTER);
            name.setColor(0xB3FFFFFF);
            fit(name, label, w - 4 * k, 13 * sp * scale);
            c.drawText(label, cx, d + 21 * k, name);
            return;
        }
        if (!focused) return;
        String st;
        if (!known) st = "…";
        else if (reach == 0) st = "Sin conexión";
        else if (onN == 0) st = "Apagada";
        else st = hasDimmer ? dimLevel() + "%" : "Encendida";
        if (adjust) { drawAdjust(c, w, cx, d, k); return; }
        name.setTextAlign(Paint.Align.CENTER);
        status.setTextAlign(Paint.Align.CENTER);
        name.setColor(0xF2FFFFFF);
        status.setColor(0xB3FFFFFF);
        fit(name, label, w - 4 * k, 15 * sp * scale);
        fit(status, st, w - 4 * k, 12 * sp * scale);
        c.drawText(label, cx, d + 21 * k, name);
        c.drawText(st, cx, d + 38 * k, status);
    }

    /** Adjust mode under a round button: the level between two arrows, and for a colour bulb the colour between two more. */
    private void drawAdjust(Canvas c, float w, float cx, float d, float k) {
        long now = System.currentTimeMillis();
        name.setTextAlign(Paint.Align.CENTER);
        status.setTextAlign(Paint.Align.CENTER);
        // Row one: ◀ 80% ▶ , row two (colour bulbs): ▲ Rojo ▼
        float y1 = d + 24 * k, y2 = d + 46 * k, off = w * 0.40f;
        name.setColor(0xFFFFFFFF);
        fit(name, dimLevel() + "%", w * 0.5f, 17 * sp * scale);
        c.drawText(dimLevel() + "%", cx, y1, name);
        arrow(c, "◀", cx - off, y1, KeyEvent.KEYCODE_DPAD_LEFT, now, k);
        arrow(c, "▶", cx + off, y1, KeyEvent.KEYCODE_DPAD_RIGHT, now, k);
        if (hasColour) {
            String cn = String.valueOf(COLOURS[colourIndex][0]);
            status.setColor(0xFFFFFFFF);
            fit(status, cn, w * 0.5f, 13 * sp * scale);
            c.drawText(cn, cx, y2, status);
            arrow(c, "▲", cx - off, y2, KeyEvent.KEYCODE_DPAD_UP, now, k);
            arrow(c, "▼", cx + off, y2, KeyEvent.KEYCODE_DPAD_DOWN, now, k);
        } else {
            float bw = w - 14 * k, bh = 4 * k, by = y2 - 8 * k;
            bar.setStyle(Paint.Style.FILL);
            bar.setColor(0x40FFFFFF);
            rect.set(7 * k, by, 7 * k + bw, by + bh);
            c.drawRoundRect(rect, bh, bh, bar);
            bar.setColor(0xFFFFFFFF);
            rect.set(7 * k, by, 7 * k + bw * dimLevel() / 100f, by + bh);
            c.drawRoundRect(rect, bh, bh, bar);
        }
    }

    /** An arrow glyph that lights up and swells for a moment after its key is pressed. */
    private void arrow(Canvas c, String glyph, float x, float y, int key, long now, float k) {
        boolean lit = flashKey == key && now < flashUntil;
        status.setTextAlign(Paint.Align.CENTER);
        status.setColor(lit ? 0xFFFFFFFF : 0x80FFFFFF);
        status.setTextSize((lit ? 21 : 14) * sp * scale);
        c.drawText(glyph, x, y, status);
    }

    private void drawBulb(Canvas c, float cx, float cy, float s, int color, boolean lit) {
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(color);
        c.drawCircle(cx, cy - s * 0.07f, s * 0.32f, ink);
        rect.set(cx - s * 0.16f, cy + s * 0.2f, cx + s * 0.16f, cy + s * 0.38f);
        c.drawRoundRect(rect, s * 0.05f, s * 0.05f, ink);
        if (lit) {
            edge.setStrokeWidth(s * 0.06f);
            edge.setColor((color & 0x00FFFFFF) | 0x99000000);
            edge.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 5; i++) {
                double a = Math.toRadians(-160 + i * 35);
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                c.drawLine(cx + cs * s * 0.46f, cy - s * 0.07f + sn * s * 0.46f, cx + cs * s * 0.58f, cy - s * 0.07f + sn * s * 0.58f, edge);
            }
            edge.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    /** A ceiling light: a wire, a cone shade and a strip of glow. */
    private void drawCeiling(Canvas c, float cx, float cy, float s, int color) {
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(color);
        rect.set(cx - s * 0.02f, cy - s * 0.46f, cx + s * 0.02f, cy - s * 0.12f);
        c.drawRect(rect, ink);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(cx - s * 0.1f, cy - s * 0.12f); p.lineTo(cx + s * 0.1f, cy - s * 0.12f);
        p.lineTo(cx + s * 0.36f, cy + s * 0.22f); p.lineTo(cx - s * 0.36f, cy + s * 0.22f); p.close();
        c.drawPath(p, ink);
        ink.setColor((color & 0x00FFFFFF) | 0x66000000);
        rect.set(cx - s * 0.26f, cy + s * 0.27f, cx + s * 0.26f, cy + s * 0.43f);
        c.drawRoundRect(rect, s * 0.08f, s * 0.08f, ink);
    }

    /** A floor lamp: a shade, a thin stand and a round base. */
    private void drawLamp(Canvas c, float cx, float cy, float s, int color) {
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(color);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(cx - s * 0.16f, cy - s * 0.42f); p.lineTo(cx + s * 0.16f, cy - s * 0.42f);
        p.lineTo(cx + s * 0.32f, cy - s * 0.04f); p.lineTo(cx - s * 0.32f, cy - s * 0.04f); p.close();
        c.drawPath(p, ink);
        rect.set(cx - s * 0.025f, cy - s * 0.04f, cx + s * 0.025f, cy + s * 0.36f);
        c.drawRect(rect, ink);
        rect.set(cx - s * 0.2f, cy + s * 0.34f, cx + s * 0.2f, cy + s * 0.43f);
        c.drawRoundRect(rect, s * 0.05f, s * 0.05f, ink);
    }

    private void drawPlug(Canvas c, float cx, float cy, float s, int color) {
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(color);
        rect.set(cx - s * 0.26f, cy - s * 0.12f, cx + s * 0.26f, cy + s * 0.36f);
        c.drawRoundRect(rect, s * 0.1f, s * 0.1f, ink);
        rect.set(cx - s * 0.15f, cy - s * 0.4f, cx - s * 0.07f, cy - s * 0.1f);
        c.drawRoundRect(rect, s * 0.03f, s * 0.03f, ink);
        rect.set(cx + s * 0.07f, cy - s * 0.4f, cx + s * 0.15f, cy - s * 0.1f);
        c.drawRoundRect(rect, s * 0.03f, s * 0.03f, ink);
    }
}
