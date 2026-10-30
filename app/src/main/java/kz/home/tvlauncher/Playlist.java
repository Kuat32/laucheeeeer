package kz.home.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Список каналов из открытого каталога iptv-org (только общедоступные трансляции,
 * которые сами каналы выкладывают бесплатно). Сначала казахстанские, затем русскоязычные.
 */
final class Playlist {

    /** Источники в порядке показа. Можно добавить свои ссылки на M3U. */
    static final String[] SOURCES = {
            "https://iptv-org.github.io/iptv/countries/kz.m3u",
            "https://iptv-org.github.io/iptv/languages/rus.m3u",
    };
    private static final String PRIMARY = "https://iptv-org.github.io/iptv/";
    private static final String MIRROR = "https://raw.githubusercontent.com/iptv-org/iptv/gh-pages/";

    private static final long REFRESH_MS = 12L * 60 * 60 * 1000;
    private static final long BROKEN_HIDE_MS = 12L * 60 * 60 * 1000;
    private static final String SOURCE_MARK = "#TVL-SOURCE:";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final String[] GROUP_ORDER = {
            "General", "News", "Movies", "Series", "Documentary", "Kids", "Entertainment",
            "Comedy", "Music", "Sports", "Culture", "Education", "Travel", "Lifestyle",
            "Outdoor", "Cooking", "Religious", "Undefined",
    };
    private static final Map<String, String> GROUP_RU = new HashMap<>();

    static {
        GROUP_RU.put("General", "Общие");
        GROUP_RU.put("News", "Новости");
        GROUP_RU.put("Movies", "Кино");
        GROUP_RU.put("Series", "Сериалы");
        GROUP_RU.put("Documentary", "Познавательные");
        GROUP_RU.put("Kids", "Детские");
        GROUP_RU.put("Animation", "Мультфильмы");
        GROUP_RU.put("Entertainment", "Развлечения");
        GROUP_RU.put("Comedy", "Юмор");
        GROUP_RU.put("Music", "Музыка");
        GROUP_RU.put("Sports", "Спорт");
        GROUP_RU.put("Culture", "Культура");
        GROUP_RU.put("Education", "Обучение");
        GROUP_RU.put("Travel", "Путешествия");
        GROUP_RU.put("Lifestyle", "Образ жизни");
        GROUP_RU.put("Outdoor", "Природа и охота");
        GROUP_RU.put("Cooking", "Кулинария");
        GROUP_RU.put("Religious", "Религия");
        GROUP_RU.put("Business", "Бизнес");
        GROUP_RU.put("Science", "Наука");
        GROUP_RU.put("Auto", "Авто");
        GROUP_RU.put("Family", "Семейные");
        GROUP_RU.put("Classic", "Классика");
        GROUP_RU.put("Legislative", "Парламент");
        GROUP_RU.put("Shop", "Магазин");
        GROUP_RU.put("Weather", "Погода");
    }

    static final class Channel {
        int number;
        String name;
        String group;
        String url;
        String userAgent;
        String referrer;
        String tvgId;
        int source;

        String groupRu() {
            String ru = GROUP_RU.get(group);
            return ru != null ? ru : "Другое";
        }
    }

    private Playlist() {
    }

    // ---------------------------------------------------------------- кэш и загрузка

    static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "channels.m3u");
    }

    static boolean needsRefresh(Context c) {
        File f = cacheFile(c);
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > REFRESH_MS;
    }

    static List<Channel> loadCached(Context c) {
        File f = cacheFile(c);
        if (!f.exists()) return new ArrayList<>();
        try {
            return parse(readAll(new FileInputStream(f)));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** Скачивает все источники. Возвращает null, если не скачался ни один. */
    static List<Channel> download(Context c) {
        StringBuilder all = new StringBuilder("#EXTM3U\n");
        boolean any = false;
        for (int i = 0; i < SOURCES.length; i++) {
            String text = fetch(SOURCES[i]);
            if (text == null && SOURCES[i].startsWith(PRIMARY)) {
                text = fetch(MIRROR + SOURCES[i].substring(PRIMARY.length()));
            }
            if (text == null) continue;
            any = true;
            all.append(SOURCE_MARK).append(i).append('\n').append(text).append('\n');
        }
        if (!any) return null;
        String combined = all.toString();
        List<Channel> channels = parse(combined);
        if (channels.isEmpty()) return null;
        try {
            File tmp = new File(c.getFilesDir(), "channels.m3u.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(combined.getBytes(UTF8));
            } finally {
                out.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(cacheFile(c));
        } catch (IOException ignored) {
        }
        return channels;
    }

    private static String fetch(String address) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(address);
            for (int redirects = 0; redirects < 5; redirects++) {
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (loc == null) return null;
                    url = new URL(url, loc);
                    continue;
                }
                if (code != 200) return null;
                return readAll(conn.getInputStream());
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    // ---------------------------------------------------------------- разбор M3U

    private static final Pattern ATTR = Pattern.compile("([A-Za-z][\\w-]*)=\"([^\"]*)\"");
    private static final Pattern QUALITY = Pattern.compile("\\s*\\(\\d{3,4}[pi]\\)");

    static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenIds = new HashSet<>();

        int source = 0;
        Channel pending = null;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith(SOURCE_MARK)) {
                try {
                    source = Integer.parseInt(line.substring(SOURCE_MARK.length()).trim());
                } catch (NumberFormatException ignored) {
                }
                pending = null;
            } else if (line.startsWith("#EXTINF")) {
                pending = parseExtinf(line);
                if (pending != null) pending.source = source;
            } else if (line.startsWith("#EXTVLCOPT:")) {
                if (pending == null) continue;
                String opt = line.substring("#EXTVLCOPT:".length());
                if (opt.startsWith("http-referrer=")) pending.referrer = opt.substring("http-referrer=".length());
                if (opt.startsWith("http-user-agent=")) pending.userAgent = opt.substring("http-user-agent=".length());
            } else if (!line.startsWith("#")) {
                Channel ch = pending;
                pending = null;
                if (ch == null) continue;
                ch.url = line;
                if (!isPlayable(line)) continue;
                if (!seenUrls.add(line)) continue;
                if (ch.tvgId != null && !ch.tvgId.isEmpty() && !seenIds.add(ch.tvgId)) continue;
                out.add(ch);
            }
        }

        Collections.sort(out, new Comparator<Channel>() {
            @Override
            public int compare(Channel a, Channel b) {
                if (a.source != b.source) return a.source - b.source;
                int ga = groupRank(a.group), gb = groupRank(b.group);
                if (ga != gb) return ga - gb;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        for (int i = 0; i < out.size(); i++) out.get(i).number = i + 1;
        return out;
    }

    private static Channel parseExtinf(String line) {
        // Название идёт после первой запятой вне кавычек.
        boolean quoted = false;
        int comma = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) {
                comma = i;
                break;
            }
        }
        if (comma < 0) return null;
        String name = line.substring(comma + 1).trim();
        // Каналы, закрытые по региону или идущие не круглосуточно, пожилым только мешают.
        if (name.contains("[Geo-blocked]") || name.contains("[Not 24/7]")) return null;

        Channel ch = new Channel();
        Matcher m = ATTR.matcher(line.substring(0, comma));
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2);
            if (key.equals("group-title")) ch.group = value.split(";")[0].trim();
            else if (key.equals("tvg-id")) ch.tvgId = value;
            else if (key.equals("http-user-agent")) ch.userAgent = value;
            else if (key.equals("http-referrer")) ch.referrer = value;
        }
        if (ch.group == null || ch.group.isEmpty()) ch.group = "Undefined";
        String g = ch.group.toLowerCase(Locale.ROOT);
        if (g.contains("xxx") || g.contains("adult")) return null;

        name = QUALITY.matcher(name).replaceAll("");
        name = name.replaceAll("\\s*\\[[^]]*]", "").trim();
        if (name.isEmpty()) return null;
        ch.name = name;
        return ch;
    }

    private static boolean isPlayable(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false;
        // DASH и YouTube-ссылки встроенный плеер не открывает.
        return !u.contains(".mpd") && !u.contains("youtube.com") && !u.contains("youtu.be");
    }

    private static int groupRank(String group) {
        for (int i = 0; i < GROUP_ORDER.length; i++) if (GROUP_ORDER[i].equals(group)) return i;
        return GROUP_ORDER.length - 2; // неизвестные — перед Undefined
    }

    // ---------------------------------------------------------------- неработающие каналы

    private static SharedPreferences brokenPrefs(Context c) {
        return c.getSharedPreferences("broken_channels", Context.MODE_PRIVATE);
    }

    static boolean isBroken(Context c, Channel ch) {
        long when = brokenPrefs(c).getLong(key(ch), 0);
        return when != 0 && System.currentTimeMillis() - when < BROKEN_HIDE_MS;
    }

    static void markBroken(Context c, Channel ch) {
        brokenPrefs(c).edit().putLong(key(ch), System.currentTimeMillis()).apply();
    }

    static void markWorking(Context c, Channel ch) {
        SharedPreferences p = brokenPrefs(c);
        if (p.contains(key(ch))) p.edit().remove(key(ch)).apply();
    }

    private static String key(Channel ch) {
        return Integer.toHexString(ch.url.hashCode());
    }
}
