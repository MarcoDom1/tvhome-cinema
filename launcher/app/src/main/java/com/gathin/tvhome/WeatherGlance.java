package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * An "At a glance" weather line under the clock: an icon drawn in code, the temperature, the condition and
 * today's high and low. The data comes from Open-Meteo (no key), at most once every 20 minutes and only while
 * Home is showing; the last answer is kept so a cold start draws at once. Needs weather_lat and weather_lon in a
 * theme's config.json; without them the view is gone and nothing goes online.
 */
public final class WeatherGlance extends View {

    private static final long TTL_MS = 20 * 60 * 1000L;
    private static final String PREFS = "weather", KEY_JSON = "json", KEY_TIME = "time";

    private enum Icon { SUN, MOON, PARTLY_DAY, PARTLY_NIGHT, CLOUD, FOG, RAIN, SNOW, STORM }

    private final float density, sp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), path2 = new Path();
    private final RectF rect = new RectF();

    private String city = "";
    private double lat = Double.NaN, lon = Double.NaN;
    private boolean fahrenheit;
    private boolean fetching;
    private boolean cardStyle = true;

    private boolean have;
    private int temp, hi, lo, code;
    private boolean day = true;
    private String line1 = "", line2 = "";

    public WeatherGlance(Context context) {
        this(context, null);
    }

    public WeatherGlance(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        sp = context.getResources().getDisplayMetrics().scaledDensity;
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        Typeface light = UiFont.get(context, R.string.font_light);
        Typeface medium = UiFont.get(context, R.string.font_medium);
        big.setTypeface(light);
        big.setColor(0xFFFFFFFF);
        big.setTextSize(64 * sp);
        mid.setTypeface(medium);
        mid.setColor(0xF2FFFFFF);
        mid.setTextSize(25 * sp);
        small.setTypeface(medium);
        small.setColor(0xA6FFFFFF);
        small.setTextSize(18 * sp);
        card.setStyle(Paint.Style.FILL);
        card.setColor(0x1FFFFFFF);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f * density);
        edge.setColor(0x33FFFFFF);
        small.setLetterSpacing(0.04f);
    }

    /** Sets the place and unit; the cached answer, if any, is drawn straight away. */
    public void configure(String cityName, double latitude, double longitude, boolean useFahrenheit, boolean card) {
        cardStyle = card;
        if (!card) {
            big.setTextSize(42 * sp);
            mid.setTextSize(19 * sp);
            small.setTextSize(14 * sp);
        }
        city = cityName == null ? "" : cityName;
        lat = latitude;
        lon = longitude;
        fahrenheit = useFahrenheit;
        load(prefs().getString(KEY_JSON, null));
    }

    /** Called when Home comes up: fetches only when the last answer is older than 20 minutes. */
    public void refreshIfStale() {
        if (Double.isNaN(lat) || Double.isNaN(lon) || fetching) return;
        long age = System.currentTimeMillis() - prefs().getLong(KEY_TIME, 0);
        if (have && age >= 0 && age < TTL_MS) return;
        fetching = true;
        new Thread(() -> {
            String body = null;
            try {
                String u = String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                                + "&timezone=auto&forecast_days=1&temperature_unit=%s",
                        lat, lon, fahrenheit ? "fahrenheit" : "celsius");
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                        String l;
                        while ((l = r.readLine()) != null) sb.append(l);
                    }
                    body = sb.toString();
                }
                c.disconnect();
            } catch (Exception ignored) {
                // offline: keep what is on screen
            }
            final String result = body;
            main.post(() -> {
                fetching = false;
                if (result != null && load(result)) {
                    prefs().edit().putString(KEY_JSON, result).putLong(KEY_TIME, System.currentTimeMillis()).apply();
                }
            });
        }, "weather").start();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean load(String json) {
        if (json == null) return false;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            JSONObject d = o.getJSONObject("daily");
            temp = (int) Math.round(cur.getDouble("temperature_2m"));
            code = cur.getInt("weather_code");
            day = cur.optInt("is_day", 1) == 1;
            JSONArray mx = d.getJSONArray("temperature_2m_max"), mn = d.getJSONArray("temperature_2m_min");
            hi = (int) Math.round(mx.getDouble(0));
            lo = (int) Math.round(mn.getDouble(0));
            line1 = describe(code);
            line2 = labelHiLo() + " " + hi + "°   " + labelLo() + " " + lo + "°" + (city.isEmpty() ? "" : "  ·  " + city);
            have = true;
            requestLayout();
            invalidate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- text ---------------------------------------------------------------------------------------

    private static String lang() {
        String l = Locale.getDefault().getLanguage();
        return l.equals("es") || l.equals("it") ? l : "en";
    }

    private static String labelHiLo() { String l = lang(); return l.equals("es") ? "Máx" : l.equals("it") ? "Max" : "High"; }
    private static String labelLo() { String l = lang(); return l.equals("es") ? "Mín" : l.equals("it") ? "Min" : "Low"; }

    private static String describe(int c) {
        int i;
        if (c == 0) i = 0;
        else if (c == 1) i = 1;
        else if (c == 2) i = 2;
        else if (c == 3) i = 3;
        else if (c == 45 || c == 48) i = 4;
        else if (c >= 51 && c <= 57) i = 5;
        else if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) i = 6;
        else if ((c >= 71 && c <= 77) || c == 85 || c == 86) i = 7;
        else if (c >= 95) i = 8;
        else i = 3;
        String[] en = {"Clear", "Mostly clear", "Partly cloudy", "Cloudy", "Fog", "Drizzle", "Rain", "Snow", "Thunderstorm"};
        String[] es = {"Despejado", "Mayormente despejado", "Parcialmente nublado", "Nublado", "Niebla", "Llovizna", "Lluvia", "Nieve", "Tormenta"};
        String[] it = {"Sereno", "Prevalentemente sereno", "Parzialmente nuvoloso", "Nuvoloso", "Nebbia", "Pioggerella", "Pioggia", "Neve", "Temporale"};
        String l = lang();
        return (l.equals("es") ? es : l.equals("it") ? it : en)[i];
    }

    private Icon icon() {
        if (code == 0 || code == 1) return day ? Icon.SUN : Icon.MOON;
        if (code == 2) return day ? Icon.PARTLY_DAY : Icon.PARTLY_NIGHT;
        if (code == 45 || code == 48) return Icon.FOG;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return Icon.RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Icon.SNOW;
        if (code >= 95) return Icon.STORM;
        return Icon.CLOUD;
    }

    // ---- layout and drawing -------------------------------------------------------------------------

    private float iconSize() { return (cardStyle ? 78 : 50) * density; }
    private float padX() { return (cardStyle ? 40 : 4) * density; }
    private float padY() { return (cardStyle ? 14 : 4) * density; }

    private float contentWidth() {
        float tw = big.measureText(temp + "°");
        float text = Math.max(mid.measureText(line1), small.measureText(line2));
        return iconSize() + 22 * density + tw + 34 * density + text;
    }

    @Override
    protected void onMeasure(int w, int h) {
        if (!have) {
            setMeasuredDimension(0, 0);
            return;
        }
        setMeasuredDimension(resolveSize((int) (2 * padX() + contentWidth()), w), (int) (2 * padY() + iconSize()));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!have) return;
        if (cardStyle) {
            float r = 26 * density;
            rect.set(edge.getStrokeWidth(), edge.getStrokeWidth(), getWidth() - edge.getStrokeWidth(), getHeight() - edge.getStrokeWidth());
            c.drawRoundRect(rect, r, r, card);
            c.drawRoundRect(rect, r, r, edge);
        }
        float s = iconSize();
        float x0 = cardStyle ? Math.max(padX(), (getWidth() - contentWidth()) / 2f) : padX();   // the content stands in the middle of the card
        drawIcon(c, icon(), x0 + s / 2f, getHeight() / 2f, s);
        float x = x0 + s + 22 * density;
        Paint.FontMetrics fm = big.getFontMetrics();
        float base = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(temp + "°", x, base, big);
        x += big.measureText(temp + "°") + 34 * density;
        Paint.FontMetrics m1 = mid.getFontMetrics(), m2 = small.getFontMetrics();
        float gap = 6 * density;
        float total = (m1.descent - m1.ascent) + gap + (m2.descent - m2.ascent);
        float top = getHeight() / 2f - total / 2f;
        c.drawText(line1, x, top - m1.ascent, mid);
        c.drawText(line2, x, top + (m1.descent - m1.ascent) + gap - m2.ascent, small);
    }

    private void drawIcon(Canvas c, Icon icon, float cx, float cy, float s) {
        switch (icon) {
            case SUN: sun(c, cx, cy, s * 0.5f); break;
            case MOON: moon(c, cx, cy, s * 0.5f); break;
            case PARTLY_DAY:
                sun(c, cx - s * 0.16f, cy - s * 0.16f, s * 0.36f);
                cloud(c, cx + s * 0.06f, cy + s * 0.1f, s * 0.82f, 0xFFEAF0F8);
                break;
            case PARTLY_NIGHT:
                moon(c, cx - s * 0.16f, cy - s * 0.16f, s * 0.36f);
                cloud(c, cx + s * 0.06f, cy + s * 0.1f, s * 0.82f, 0xFFEAF0F8);
                break;
            case CLOUD: cloud(c, cx, cy, s * 0.95f, 0xFFEAF0F8); break;
            case FOG:
                cloud(c, cx, cy - s * 0.1f, s * 0.8f, 0xFFD5DCE8);
                stroke.setColor(0xFFC3CCDB);
                stroke.setStrokeWidth(s * 0.07f);
                c.drawLine(cx - s * 0.32f, cy + s * 0.26f, cx + s * 0.32f, cy + s * 0.26f, stroke);
                c.drawLine(cx - s * 0.2f, cy + s * 0.4f, cx + s * 0.4f, cy + s * 0.4f, stroke);
                break;
            case RAIN:
                cloud(c, cx, cy - s * 0.1f, s * 0.9f, 0xFFC9D3E3);
                stroke.setColor(0xFF63B3FF);
                stroke.setStrokeWidth(s * 0.07f);
                for (int i = -1; i <= 1; i++) {
                    float x = cx + i * s * 0.22f;
                    c.drawLine(x + s * 0.04f, cy + s * 0.22f, x - s * 0.04f, cy + s * 0.4f, stroke);
                }
                break;
            case SNOW:
                cloud(c, cx, cy - s * 0.1f, s * 0.9f, 0xFFD9E1EE);
                fill.setColor(0xFFFFFFFF);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.22f, cy + (i == 0 ? 0.38f : 0.3f) * s, s * 0.045f, fill);
                break;
            case STORM:
                cloud(c, cx, cy - s * 0.1f, s * 0.9f, 0xFFA9B4C8);
                path.reset();
                path.moveTo(cx + s * 0.04f, cy + s * 0.12f);
                path.lineTo(cx - s * 0.12f, cy + s * 0.34f);
                path.lineTo(cx - s * 0.01f, cy + s * 0.34f);
                path.lineTo(cx - s * 0.08f, cy + s * 0.5f);
                path.lineTo(cx + s * 0.14f, cy + s * 0.26f);
                path.lineTo(cx + s * 0.03f, cy + s * 0.26f);
                path.close();
                fill.setColor(0xFFFFD23C);
                c.drawPath(path, fill);
                break;
        }
    }

    private void sun(Canvas c, float cx, float cy, float s) {
        fill.setColor(0xFFFFC93C);
        c.drawCircle(cx, cy, s * 0.3f, fill);
        stroke.setColor(0xFFFFC93C);
        stroke.setStrokeWidth(s * 0.09f);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            c.drawLine(cx + cs * s * 0.43f, cy + sn * s * 0.43f, cx + cs * s * 0.6f, cy + sn * s * 0.6f, stroke);
        }
    }

    private void moon(Canvas c, float cx, float cy, float s) {
        path.reset();
        path.addCircle(cx, cy, s * 0.42f, Path.Direction.CW);
        path2.reset();
        path2.addCircle(cx + s * 0.22f, cy - s * 0.14f, s * 0.38f, Path.Direction.CW);
        path.op(path2, Path.Op.DIFFERENCE);
        fill.setColor(0xFFDCE6FF);
        c.drawPath(path, fill);
    }

    private void cloud(Canvas c, float cx, float cy, float s, int color) {
        fill.setColor(color);
        c.drawCircle(cx - s * 0.22f, cy + s * 0.04f, s * 0.2f, fill);
        c.drawCircle(cx + s * 0.02f, cy - s * 0.09f, s * 0.26f, fill);
        c.drawCircle(cx + s * 0.27f, cy + s * 0.04f, s * 0.19f, fill);
        rect.set(cx - s * 0.42f, cy + s * 0.0f, cx + s * 0.46f, cy + s * 0.23f);
        c.drawRoundRect(rect, s * 0.11f, s * 0.11f, fill);
    }
}
