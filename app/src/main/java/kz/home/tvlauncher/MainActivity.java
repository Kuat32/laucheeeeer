package kz.home.tvlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Лёгкий главный экран для Android TV.
 *
 * Без библиотек (AndroidX, Leanback) и без живых эффектов: на слабом процессоре телевизора
 * дорого стоят именно они. Иконки грузятся в фоне, экран рисуется один раз за загрузку.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "launcher";
    private static final String KEY_FAVORITES = "favorites";
    private static final int GRID_COLUMNS = 4;

    /** Плитки по умолчанию в «Избранном», если они установлены. TV+ ищется отдельно. */
    private static final String[] DEFAULT_FAVORITES = {
            "com.android.vending",               // Google Play
            "com.google.android.youtube.tv",     // YouTube для ТВ
            "com.google.android.youtube.tvkids",
    };

    private static final int[] TILE_COLORS = {
            0xFF2B5D8A, 0xFF7A3E9D, 0xFF2E7D5B, 0xFFA2522A,
            0xFF8A2B4A, 0xFF3C4F9E, 0xFF5E6B2B, 0xFF2A6F7A,
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    private ScrollView scroll;
    private LinearLayout favoritesRow;
    private LinearLayout allAppsGrid;
    private TextView clockView;
    private TextView dateView;

    private final Map<String, AppEntry> apps = new LinkedHashMap<>();
    private int loadGeneration;
    private float density;

    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru"));

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            clockView.setText(clockFormat.format(now));
            String date = dateFormat.format(now);
            dateView.setText(date.substring(0, 1).toUpperCase(new Locale("ru")) + date.substring(1));
            // Обновляемся ровно в начале следующей минуты, а не каждую секунду.
            long delay = 60_000 - (System.currentTimeMillis() % 60_000) + 50;
            ui.postDelayed(this, delay);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    static final class AppEntry {
        String packageName;
        String label;
        Intent launchIntent;
        Drawable banner;
        Drawable icon;
    }

    // ---------------------------------------------------------------- жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;

        scroll = (ScrollView) findViewById(R.id.scroll);
        favoritesRow = (LinearLayout) findViewById(R.id.favorites_row);
        allAppsGrid = (LinearLayout) findViewById(R.id.all_apps_grid);
        clockView = (TextView) findViewById(R.id.clock);
        dateView = (TextView) findViewById(R.id.date);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(clockTick);
        clockTick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(clockTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        background.shutdownNow();
    }

    /** Нажали Home, когда лаунчер уже открыт: возвращаемся к первой плитке. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusFirstTile();
    }

    /**
     * На главном экране PROG +/− и цифры сразу включают «Телевизор»:
     * пожилым не нужно искать плитку, достаточно нажать привычную кнопку.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean channelKey = code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN;
        int digit = -1;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) digit = code - KeyEvent.KEYCODE_0;
        if (code >= KeyEvent.KEYCODE_NUMPAD_0 && code <= KeyEvent.KEYCODE_NUMPAD_9) digit = code - KeyEvent.KEYCODE_NUMPAD_0;
        if (channelKey || digit >= 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Intent tv = new Intent(this, PlayerActivity.class);
                if (digit >= 0) tv.putExtra(PlayerActivity.EXTRA_DIGIT, digit);
                startActivity(tv);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** Кнопка «Назад» на главном экране ничего не делает. */
    @Override
    public void onBackPressed() {
        focusFirstTile();
    }

    // ---------------------------------------------------------------- загрузка приложений

    private void loadApps() {
        final int generation = ++loadGeneration;
        final PackageManager pm = getPackageManager();
        background.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, AppEntry> found = new LinkedHashMap<>();
                collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
                collect(pm, Intent.CATEGORY_LAUNCHER, found);

                List<AppEntry> sorted = new ArrayList<>(found.values());
                final Collator collator = Collator.getInstance(new Locale("ru"));
                Collections.sort(sorted, new Comparator<AppEntry>() {
                    @Override
                    public int compare(AppEntry a, AppEntry b) {
                        return collator.compare(a.label, b.label);
                    }
                });
                final Map<String, AppEntry> result = new LinkedHashMap<>();
                for (AppEntry e : sorted) result.put(e.packageName, e);

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation != loadGeneration || isFinishing()) return;
                        apps.clear();
                        apps.putAll(result);
                        render();
                    }
                });
            }
        });
    }

    private void collect(PackageManager pm, String category, Map<String, AppEntry> out) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list;
        try {
            list = pm.queryIntentActivities(query, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || getPackageName().equals(ai.packageName)) continue;
            if (out.containsKey(ai.packageName)) continue;

            AppEntry e = new AppEntry();
            e.packageName = ai.packageName;
            CharSequence label = ri.loadLabel(pm);
            e.label = label != null ? label.toString() : ai.packageName;
            e.launchIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                e.banner = ai.loadBanner(pm);
                if (e.banner == null) e.banner = ai.applicationInfo.loadBanner(pm);
            } catch (RuntimeException ignored) {
            }
            if (e.banner == null) {
                try {
                    e.icon = ri.loadIcon(pm);
                } catch (RuntimeException ignored) {
                }
            }
            out.put(e.packageName, e);
        }
    }

    // ---------------------------------------------------------------- избранное

    /** TV+ Казахтелеком: точного имени пакета не знаем заранее, ищем по названию и пакету. */
    private static boolean looksLikeTvPlus(AppEntry e) {
        String pkg = e.packageName.toLowerCase(Locale.ROOT);
        String label = e.label.toLowerCase(Locale.ROOT);
        return pkg.contains("kazakhtelecom") || pkg.contains("telecom.kz") || pkg.contains("kaztelecom")
                || label.contains("tv+") || label.contains("тв+") || label.contains("казахтелеком");
    }

    private List<String> readFavorites() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        List<String> result = new ArrayList<>();
        String saved = prefs.getString(KEY_FAVORITES, null);
        if (saved != null) {
            if (!saved.isEmpty()) {
                for (String p : saved.split(",")) if (!p.isEmpty()) result.add(p);
            }
            return result;
        }
        // Первый запуск: TV+ первой, потом Google Play и YouTube.
        for (AppEntry e : apps.values()) if (looksLikeTvPlus(e)) result.add(e.packageName);
        for (String p : DEFAULT_FAVORITES) if (!result.contains(p)) result.add(p);
        return result;
    }

    private void writeFavorites(List<String> favorites) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_FAVORITES, TextUtils.join(",", favorites))
                .apply();
    }

    private void toggleFavorite(AppEntry e) {
        List<String> favorites = readFavorites();
        String message;
        if (favorites.remove(e.packageName)) {
            message = "Убрано из избранного: " + e.label;
        } else {
            favorites.add(e.packageName);
            message = "Добавлено в избранное: " + e.label;
        }
        writeFavorites(favorites);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        View focused = getCurrentFocus();
        String focusedTag = focused != null ? (String) focused.getTag() : null;
        render();
        restoreFocus(focusedTag);
    }

    /** Сдвигает плитку в «Избранном» влево или вправо. */
    private boolean moveFavorite(String packageName, int delta) {
        List<String> favorites = readFavorites();
        int from = favorites.indexOf(packageName);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= favorites.size()) return false;
        Collections.swap(favorites, from, to);
        writeFavorites(favorites);
        render();
        restoreFocus("fav:" + packageName);
        return true;
    }

    // ---------------------------------------------------------------- отрисовка

    private void render() {
        favoritesRow.removeAllViews();
        allAppsGrid.removeAllViews();

        favoritesRow.addView(tvTile());
        for (String pkg : readFavorites()) {
            AppEntry e = apps.get(pkg);
            if (e != null) favoritesRow.addView(appTile(e, true));
        }
        favoritesRow.addView(settingsTile());

        LinearLayout row = null;
        int i = 0;
        for (AppEntry e : apps.values()) {
            if (i % GRID_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                allAppsGrid.addView(row);
            }
            row.addView(appTile(e, false));
            i++;
        }

        if (getCurrentFocus() == null) focusFirstTile();
    }

    private View appTile(final AppEntry e, final boolean favorite) {
        FrameLayout tile = baseTile(favorite ? "fav:" + e.packageName : "all:" + e.packageName,
                colorFor(e.packageName));

        if (e.banner != null) {
            ImageView banner = new ImageView(this);
            banner.setScaleType(ImageView.ScaleType.FIT_XY);
            banner.setImageDrawable(e.banner);
            tile.addView(banner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            addIconAndLabel(tile, e.icon, e.label);
        }

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(e);
            }
        });
        // Долгое нажатие OK: добавить/убрать из избранного.
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                toggleFavorite(e);
                return true;
            }
        });
        // Кнопка «Меню» (☰) на пульте — то же самое; в избранном перемотка ◀◀ ▶▶ двигает плитку.
        // PROG +/− здесь не трогаем: они включают «Телевизор» (см. dispatchKeyEvent).
        tile.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    toggleFavorite(e);
                    return true;
                }
                if (favorite && keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) return moveFavorite(e.packageName, -1);
                if (favorite && keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) return moveFavorite(e.packageName, 1);
                return false;
            }
        });
        return tile;
    }

    /** Первая плитка: встроенный телевизор с бесплатными каналами. */
    private View tvTile() {
        FrameLayout tile = baseTile("tv", getResources().getColor(R.color.tv_tile));
        addIconAndLabel(tile, getResources().getDrawable(R.drawable.ic_tv), getString(R.string.tv));
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
            }
        });
        return tile;
    }

    private View settingsTile() {
        FrameLayout tile = baseTile("settings", 0xFF3A3D45);
        Drawable gear = getResources().getDrawable(android.R.drawable.ic_menu_preferences);
        addIconAndLabel(tile, gear, "Настройки ТВ");
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(MainActivity.this, "Настройки недоступны", Toast.LENGTH_SHORT).show();
                }
            }
        });
        return tile;
    }

    private FrameLayout baseTile(String tag, int color) {
        FrameLayout tile = new FrameLayout(this);
        tile.setTag(tag);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);

        float radius = dp(10);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(color);
        normal.setCornerRadius(radius);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(color);
        focused.setCornerRadius(radius);
        focused.setStroke(dp(3), 0xFFFFFFFF);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        tile.setBackground(bg);
        tile.setPadding(dp(3), dp(3), dp(3), dp(3));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(112));
        lp.setMargins(dp(10), dp(10), dp(10), dp(10));
        tile.setLayoutParams(lp);

        // Только scale: это дёшево даже на слабом ТВ. Тени и размытие не используем.
        tile.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.08f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(110).start();
                if (hasFocus) v.bringToFront();
            }
        });
        return tile;
    }

    private void addIconAndLabel(FrameLayout tile, Drawable icon, String label) {
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(52), dp(52),
                    Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            ilp.topMargin = dp(12);
            tile.addView(iv, ilp);
        }
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        tlp.setMargins(dp(8), 0, dp(8), dp(10));
        tile.addView(tv, tlp);
    }

    // ---------------------------------------------------------------- действия

    private void launch(AppEntry e) {
        try {
            startActivity(e.launchIntent);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Intent fallback = getPackageManager().getLeanbackLaunchIntentForPackage(e.packageName);
            if (fallback == null) fallback = getPackageManager().getLaunchIntentForPackage(e.packageName);
            if (fallback != null) {
                try {
                    startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (RuntimeException ignored) {
                }
            }
            Toast.makeText(this, "Не удалось открыть " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void focusFirstTile() {
        scroll.scrollTo(0, 0);
        if (favoritesRow.getChildCount() > 0) favoritesRow.getChildAt(0).requestFocus();
    }

    private void restoreFocus(String tag) {
        if (tag != null) {
            View v = scroll.findViewWithTag(tag);
            if (v != null) {
                v.requestFocus();
                return;
            }
        }
        focusFirstTile();
    }

    // ---------------------------------------------------------------- мелочи

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static int colorFor(String key) {
        return TILE_COLORS[(key.hashCode() & 0x7fffffff) % TILE_COLORS.length];
    }
}
