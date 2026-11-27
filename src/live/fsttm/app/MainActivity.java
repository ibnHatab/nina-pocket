package live.fsttm.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.provider.AlarmClock;
import org.json.JSONObject;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import live.fsttm.mobile.Mobile;
import live.fsttm.mobile.Device;
import live.fsttm.mobile.Speaker;

/** The voice's page in a WebView; the mic goes to Go while the app is in front, Go's PCM to the speaker. */
public class MainActivity extends Activity implements Speaker, Device {
    static final String TAG = "fsttm";
    static final int RATE = 16000;
    static final int VOICE_RATE = 24000;
    WebView web;
    volatile boolean listening;
    Thread mic;
    AudioTrack voice; // her speech, streamed
    SharedPreferences prefs; // the person's mic and voice switches (her card's buttons), kept across restarts
    volatile boolean micOn = true, voiceOn = true;

    /** Her card's mic and voice buttons, in the page as window.NinaApp. */
    public class NinaApp {
        @JavascriptInterface public boolean get(String name) {
            return "mic".equals(name) ? micOn : "voice".equals(name) && voiceOn;
        }
        @JavascriptInterface public void set(final String name, final boolean on) {
            runOnUiThread(new Runnable() { public void run() {
                if ("mic".equals(name)) {
                    micOn = on;
                    if (on) startMic(); else { listening = false; Mobile.listen(false); }
                } else if ("voice".equals(name)) {
                    voiceOn = on;
                    if (!on) cut();
                }
                prefs.edit().putBoolean(name, on).commit(); // at once: a kill right after a tap lost an apply()
            }});
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        prefs = getSharedPreferences("switches", MODE_PRIVATE);
        micOn = prefs.getBoolean("mic", true);
        voiceOn = prefs.getBoolean("voice", true);
        web.addJavascriptInterface(new NinaApp(), "NinaApp");
        web.setWebViewClient(new WebViewClient());
        WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect over adb (proof stage)
        web.setWebChromeClient(new WebChromeClient() { // the page's console in logcat (tag fsttm-web)
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("fsttm-web", m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        setContentView(web);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON // she is on screen while in front
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED     // her tablet: over a swipe lock screen
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        int vmin = AudioTrack.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        voice = new AudioTrack(AudioManager.STREAM_MUSIC, VOICE_RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(vmin, VOICE_RATE), AudioTrack.MODE_STREAM);
        voice.play();
        Mobile.setDevice(this);
        web.loadData("<body style='background:#14100e;color:#e9ddd3;font:18px sans-serif;display:flex;align-items:center;justify-content:center;height:90vh'>Preparing Nina…</body>", "text/html", "utf-8");
        new Thread(new Runnable() { public void run() {
            installAssets(); // her files from the APK, on the first start and after an update
            runOnUiThread(new Runnable() { public void run() { startGo(); }});
        }}, "assets").start();
    }

    void startGo() {
        String url;
        try {
            url = Mobile.start(getFilesDir().getPath(), java.util.TimeZone.getDefault().getID(), this);
        } catch (Exception e) {
            Log.e(TAG, "start", e);
            web.loadData("<h3>Go side did not start: " + e + "</h3>", "text/html", "utf-8");
            return;
        }
        web.loadUrl(url);
    }

    /** The APK's assets (catalog, nina, nina-ui: her rules, character, config and face) copied into the
     *  app's files when this build's are not there yet; the keys (.config) are never touched. */
    void installAssets() {
        try {
            long stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            SharedPreferences sp = getSharedPreferences("assets", MODE_PRIVATE);
            if (sp.getLong("stamp", 0) == stamp) return;
            for (String top : new String[]{"catalog", "nina", "nina-ui"}) {
                java.io.File dst = new java.io.File(getFilesDir(), top);
                deleteTree(dst);
                copyAsset(top, dst);
            }
            sp.edit().putLong("stamp", stamp).commit();
            Log.i(TAG, "assets installed");
        } catch (Exception e) {
            Log.e(TAG, "assets", e);
        }
    }

    void copyAsset(String path, java.io.File dst) throws java.io.IOException {
        String[] kids = getAssets().list(path);
        if (kids != null && kids.length > 0) { // a directory
            dst.mkdirs();
            for (String k : kids) copyAsset(path + "/" + k, new java.io.File(dst, k));
            return;
        }
        java.io.InputStream in;
        try { in = getAssets().open(path); } catch (java.io.IOException empty) { dst.mkdirs(); return; } // an empty directory
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        byte[] b = new byte[65536];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        in.close();
        out.close();
    }

    static void deleteTree(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteTree(k);
        f.delete();
    }

    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        startMic();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) startMic();
    }

    @Override protected void onPause() {
        super.onPause();
        listening = false; // the mic is off whenever she is not in front
        Mobile.listen(false);
    }

    void startMic() {
        if (listening || !micOn) return;
        listening = true;
        Mobile.listen(true);
        mic = new Thread(new Runnable() { public void run() {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            // VOICE_COMMUNICATION: the platform's echo cancelling and noise suppression
            AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
            byte[] buf = new byte[RATE / 50 * 2]; // 20 ms
            ar.startRecording();
            while (listening) {
                int n = ar.read(buf, 0, buf.length);
                if (n > 0) Mobile.mic(n == buf.length ? buf : java.util.Arrays.copyOf(buf, n));
            }
            ar.stop();
            ar.release();
        }}, "mic");
        mic.start();
    }

    /** Speaker: Go's PCM (s16le mono, 16 kHz) to an AudioTrack. */
    @Override public void play(byte[] pcm) {
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(pcm.length, 4096), AudioTrack.MODE_STATIC);
        t.write(pcm, 0, pcm.length);
        t.play();
        try { Thread.sleep(pcm.length * 1000L / (RATE * 2) + 100); } catch (InterruptedException ignored) {}
        t.release();
    }

    /** Speaker: her voice as it streams (s16le mono, 24 kHz); Go paces it in real time. */
    @Override public void voice(byte[] pcm) {
        if (!voiceOn) return; // her voice switched off on her card: she speaks to no one
        duck();
        voice.write(pcm, 0, pcm.length);
    }

    /** Speaker: she was interrupted: what the track holds is dropped. */
    @Override public void cut() {
        voice.pause();
        voice.flush();
        voice.play();
    }

    // ---- Device: the actions the persona may ask for (a fixed list: no other intent) ----

    MediaPlayer radio;
    float radioVolume = 0.6f;
    long duckedUntil;
    static final float DUCK = 0.25f; // the radio under her voice

    @Override public String act(String kind, String args) {
        try {
            final JSONObject a = new JSONObject(args == null || args.isEmpty() ? "{}" : args);
            switch (kind) {
                case "radio.play": {
                    final String url = a.getString("url");
                    if (a.has("volume")) radioVolume = (float) a.getDouble("volume");
                    runOnUiThread(new Runnable() { public void run() { playRadio(url); }});
                    return ok("starting");
                }
                case "radio.stop":
                    runOnUiThread(new Runnable() { public void run() { stopRadio(); }});
                    return ok("stopped");
                case "radio.volume":
                    radioVolume = (float) Math.max(0, Math.min(1, a.getDouble("volume")));
                    runOnUiThread(new Runnable() { public void run() { if (radio != null) radio.setVolume(radioVolume, radioVolume); }});
                    return ok("volume");
                case "alarm.set": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, a.getInt("hour"))
                            .putExtra(AlarmClock.EXTRA_MINUTES, a.getInt("minute"))
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Nina"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    return launch(i, "alarm set");
                }
                case "timer.set": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, a.getInt("seconds"))
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Nina"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    return launch(i, "timer set");
                }
                case "alarms.show":
                    return launch(new Intent(AlarmClock.ACTION_SHOW_ALARMS), "shown");
                default:
                    return err("no such action: " + kind);
            }
        } catch (Exception e) {
            return err(e.toString());
        }
    }

    String launch(Intent i, String what) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) == null) return err("no app on this device does that");
        startActivity(i);
        return ok(what);
    }

    void playRadio(String url) {
        stopRadio();
        radio = new MediaPlayer();
        try {
            radio.setAudioStreamType(AudioManager.STREAM_MUSIC);
            radio.setDataSource(url);
            radio.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) { mp.setVolume(radioVolume, radioVolume); mp.start(); }});
            radio.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) { Log.w(TAG, "radio error " + what + "/" + extra); stopRadio(); return true; }});
            radio.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "radio", e);
            stopRadio();
        }
    }

    void stopRadio() {
        if (radio != null) { try { radio.release(); } catch (Exception ignored) {} radio = null; }
    }

    /** Her voice over the radio: the radio down while she speaks, up 800 ms after her last word. */
    void duck() {
        final MediaPlayer r = radio;
        if (r == null) return;
        boolean was = System.currentTimeMillis() < duckedUntil;
        duckedUntil = System.currentTimeMillis() + 800;
        if (was) return;
        try { r.setVolume(radioVolume * DUCK, radioVolume * DUCK); } catch (Exception ignored) {}
        final android.os.Handler h = new android.os.Handler(getMainLooper());
        h.postDelayed(new Runnable() { public void run() {
            if (System.currentTimeMillis() < duckedUntil) { h.postDelayed(this, 200); return; }
            if (radio == r) try { r.setVolume(radioVolume, radioVolume); } catch (Exception ignored) {}
        }}, 900);
    }

    static String ok(String status) { return "{\"status\":\"" + status + "\"}"; }
    static String err(String msg) { return JSONObject.quote(msg).isEmpty() ? "{}" : "{\"status\":\"error\",\"error\":" + JSONObject.quote(msg) + "}"; }

    @Override protected void onDestroy() {
        listening = false;
        Mobile.stop();
        stopRadio();
        voice.release();
        super.onDestroy();
    }
}
