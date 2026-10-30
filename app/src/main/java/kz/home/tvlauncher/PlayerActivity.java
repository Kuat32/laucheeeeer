package kz.home.tvlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Простой телевизор: открылся — сразу идёт последний канал.
 * ▲ / P+ — следующий канал, ▼ / P− — предыдущий, OK — список каналов, цифры — номер канала.
 * Неработающие каналы пропускаются сами.
 */
public class PlayerActivity extends Activity {

    private static final String TAG = "TvPlayer";
    /** Цифра, нажатая на главном экране: начинаем с неё набор номера канала. */
    static final String EXTRA_DIGIT = "digit";
    private static final String PREFS = "player";
    private static final String KEY_LAST_URL = "last_url";
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 9; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final long LOAD_TIMEOUT_MS = 15000;
    private static final int MAX_AUTO_SKIPS = 40;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView statusView;
    private View infoPanel;
    private TextView infoNumber;
    private TextView infoName;
    private TextView infoGroup;
    private TextView numberView;
    private ListView listView;
    private ChannelAdapter adapter;

    private List<Playlist.Channel> channels = new ArrayList<>();
    private int current = -1;
    private int direction = 1;
    private int autoSkips;
    private final StringBuilder typed = new StringBuilder();

    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            infoPanel.setVisibility(View.GONE);
        }
    };

    private final Runnable commitNumber = new Runnable() {
        @Override
        public void run() {
            if (typed.length() == 0) {
                numberView.setVisibility(View.GONE);
                return;
            }
            if (channels.isEmpty()) {
                // Список ещё грузится — подождём его, номер не теряем.
                ui.postDelayed(this, 500);
                return;
            }
            numberView.setVisibility(View.GONE);
            int n;
            try {
                n = Integer.parseInt(typed.toString());
            } catch (NumberFormatException e) {
                n = -1;
            }
            typed.setLength(0);
            if (n >= 1 && n <= channels.size()) {
                autoSkips = 0;
                direction = 1;
                play(n - 1);
            } else {
                showStatus("Нет канала с таким номером", 2500);
            }
        }
    };

    private final Runnable loadTimeout = new Runnable() {
        @Override
        public void run() {
            onChannelFailed();
        }
    };

    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            statusView.setVisibility(View.GONE);
        }
    };

    // ---------------------------------------------------------------- жизненный цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        playerView = (PlayerView) findViewById(R.id.player_view);
        statusView = (TextView) findViewById(R.id.status);
        infoPanel = findViewById(R.id.info_panel);
        infoNumber = (TextView) findViewById(R.id.info_number);
        infoName = (TextView) findViewById(R.id.info_name);
        infoGroup = (TextView) findViewById(R.id.info_group);
        numberView = (TextView) findViewById(R.id.number_input);
        listView = (ListView) findViewById(R.id.channel_list);

        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setFocusable(false);

        adapter = new ChannelAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                hideList();
                autoSkips = 0;
                direction = 1;
                play(position);
            }
        });

        loadChannels();
        takeDigit(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        takeDigit(intent);
    }

    private void takeDigit(android.content.Intent intent) {
        int digit = intent != null ? intent.getIntExtra(EXTRA_DIGIT, -1) : -1;
        if (digit < 0) return;
        intent.removeExtra(EXTRA_DIGIT);
        handleKey(KeyEvent.KEYCODE_0 + digit);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player == null) createPlayer();
        if (current >= 0) play(current);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Ушли с экрана — не тратим интернет и процессор.
        ui.removeCallbacks(loadTimeout);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        background.shutdownNow();
    }

    private void createPlayer() {
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    Log.i(TAG, "playing ok");
                    ui.removeCallbacks(loadTimeout);
                    autoSkips = 0;
                    statusView.setVisibility(View.GONE);
                    if (current >= 0 && current < channels.size()) {
                        Playlist.markWorking(PlayerActivity.this, channels.get(current));
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    showStatus("Загрузка…", 0);
                } else if (state == Player.STATE_ENDED) {
                    // Прямой эфир закончиться не должен — пробуем переподключиться.
                    if (current >= 0) play(current);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.i(TAG, "player error: " + error.getErrorCodeName() + " " + error.getMessage());
                onChannelFailed();
            }
        });
        playerView.setPlayer(player);
    }

    // ---------------------------------------------------------------- список каналов

    private void loadChannels() {
        showStatus("Загружаю список каналов…", 0);
        final Context app = getApplicationContext();
        background.execute(new Runnable() {
            @Override
            public void run() {
                final List<Playlist.Channel> cached = Playlist.loadCached(app);
                if (!cached.isEmpty()) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setChannels(cached, true);
                        }
                    });
                }
                if (cached.isEmpty() || Playlist.needsRefresh(app)) {
                    final List<Playlist.Channel> fresh = Playlist.download(app);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (fresh != null) {
                                setChannels(fresh, cached.isEmpty());
                            } else if (cached.isEmpty()) {
                                showStatus("Не удалось загрузить каналы.\nПроверьте интернет и откройте ещё раз.", 0);
                            }
                        }
                    });
                }
            }
        });
    }

    private void setChannels(List<Playlist.Channel> list, boolean startPlayback) {
        if (isFinishing()) return;
        String playingUrl = current >= 0 && current < channels.size() ? channels.get(current).url : null;
        channels = list;
        adapter.notifyDataSetChanged();
        Log.i(TAG, "channels loaded: " + list.size() + " start=" + startPlayback);

        if (playingUrl != null) {
            // Обновили список в фоне: остаёмся на том же канале, номер мог сдвинуться.
            current = indexOfUrl(playingUrl);
            if (current < 0) current = 0;
            return;
        }
        if (!startPlayback || channels.isEmpty()) return;

        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_URL, null);
        int start = last != null ? indexOfUrl(last) : -1;
        if (start < 0) start = firstWorking(0, 1);
        play(start);
    }

    private int indexOfUrl(String url) {
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).url.equals(url)) return i;
        return -1;
    }

    // ---------------------------------------------------------------- воспроизведение

    private void play(int index) {
        if (channels.isEmpty() || index < 0 || index >= channels.size()) return;
        if (player == null) createPlayer();
        current = index;
        Playlist.Channel ch = channels.get(index);
        Log.i(TAG, "play #" + ch.number + " " + ch.name + " " + ch.url);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_URL, ch.url).apply();
        showInfo();
        showStatus("Загрузка…", 0);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ch.userAgent != null ? ch.userAgent : DEFAULT_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);
        if (ch.referrer != null) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Referer", ch.referrer);
            http.setDefaultRequestProperties(headers);
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(ch.url);
        if (looksLikeHls(ch.url)) item.setMimeType(MimeTypes.APPLICATION_M3U8);

        MediaSource source = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http))
                .createMediaSource(item.build());
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);

        ui.removeCallbacks(loadTimeout);
        ui.postDelayed(loadTimeout, LOAD_TIMEOUT_MS);
    }

    private static boolean looksLikeHls(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        if (path.contains(".m3u8")) return true;
        return !(path.endsWith(".mp4") || path.endsWith(".ts") || path.endsWith(".mkv")
                || path.endsWith(".flv") || path.endsWith(".mp3") || path.endsWith(".aac"));
    }

    private void onChannelFailed() {
        ui.removeCallbacks(loadTimeout);
        Log.i(TAG, "channel failed, skips=" + autoSkips);
        if (current < 0 || current >= channels.size()) return;
        Playlist.markBroken(this, channels.get(current));
        if (player != null) player.stop();

        if (++autoSkips > Math.min(MAX_AUTO_SKIPS, channels.size())) {
            showStatus("Каналы не открываются.\nПроверьте интернет.", 0);
            return;
        }
        showStatus("Канал сейчас не работает,\nвключаю следующий…", 0);
        final int next = firstWorking(current + direction, direction);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                play(next);
            }
        }, 900);
    }

    /** Ищет ближайший канал, который недавно не ломался (по кругу). */
    private int firstWorking(int from, int dir) {
        int n = channels.size();
        if (n == 0) return -1;
        for (int step = 0; step < n; step++) {
            int i = ((from + step * dir) % n + n) % n;
            if (!Playlist.isBroken(this, channels.get(i))) return i;
        }
        return ((from % n) + n) % n;
    }

    private void zap(int dir) {
        if (channels.isEmpty()) return;
        direction = dir;
        autoSkips = 0;
        play(firstWorking(current + dir, dir));
    }

    // ---------------------------------------------------------------- пульт

    /**
     * Ловим кнопки раньше всех элементов экрана: иначе часть пультов (Sony RM-ED…)
     * присылает их так, что до onKeyDown они не доходят.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            Log.i(TAG, "key " + KeyEvent.keyCodeToString(keyCode) + " repeat=" + event.getRepeatCount()
                    + " channels=" + channels.size() + " current=" + current
                    + " list=" + (listView.getVisibility() == View.VISIBLE));
        }
        if (listView.getVisibility() != View.VISIBLE && isOurKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) handleKey(keyCode);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isOurKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                return true;
            default:
                return (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
                        || (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (listView.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                hideList();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }
        return handleKey(keyCode) || super.onKeyDown(keyCode, event);
    }

    private boolean handleKey(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            keyCode = KeyEvent.KEYCODE_0 + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }
        boolean digitKey = keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9;
        if (channels.isEmpty() && !digitKey) {
            showStatus("Список каналов ещё загружается…", 2500);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_MENU:
                showList();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_INFO:
                showInfo();
                return true;
            default:
                break;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            if (typed.length() < 4) typed.append((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
            numberView.setText(typed);
            numberView.setVisibility(View.VISIBLE);
            ui.removeCallbacks(commitNumber);
            ui.postDelayed(commitNumber, 1800);
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (listView.getVisibility() == View.VISIBLE) {
            hideList();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- экранные элементы

    private void showList() {
        if (channels.isEmpty()) return;
        infoPanel.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        listView.setVisibility(View.VISIBLE);
        listView.requestFocus();
        int sel = Math.max(current, 0);
        listView.setSelectionFromTop(sel, dp(160));
    }

    private void hideList() {
        listView.setVisibility(View.GONE);
        listView.clearFocus();
    }

    private void showInfo() {
        if (current < 0 || current >= channels.size()) return;
        Playlist.Channel ch = channels.get(current);
        infoNumber.setText(String.valueOf(ch.number));
        infoName.setText(ch.name);
        infoGroup.setText(ch.groupRu());
        infoPanel.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 4000);
    }

    private void showStatus(String text, long hideAfterMs) {
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideStatus);
        if (hideAfterMs > 0) ui.postDelayed(hideStatus, hideAfterMs);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return channels.size();
        }

        @Override
        public Object getItem(int position) {
            return channels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(PlayerActivity.this);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                tv.setSingleLine(true);
                tv.setPadding(dp(20), dp(10), dp(20), dp(10));
            }
            Playlist.Channel ch = channels.get(position);
            tv.setText(ch.number + "    " + ch.name);
            boolean broken = Playlist.isBroken(PlayerActivity.this, ch);
            tv.setTextColor(position == current ? 0xFFF2A33A : broken ? 0xFF6B7078 : Color.WHITE);
            return tv;
        }
    }
}
