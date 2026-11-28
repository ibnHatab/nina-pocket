package live.fsttm.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

// Restarter: her app started again (her setup wrote what she reads at her start). It runs in a process of its
// own (":restart"), opened while her app is on the screen: it ends her process, opens her app afresh and ends
// itself. Android does not let an app whose process ended bring itself back (the Pixel, Android 14: background
// activity launch blocked), so the restart is done from the front, as ProcessPhoenix does.
public class Restarter extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pid = getIntent().getIntExtra("pid", -1);
        if (pid > 0) Process.killProcess(pid);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        Process.killProcess(Process.myPid());
    }
}
