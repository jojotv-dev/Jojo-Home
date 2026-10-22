package com.jojotv.home;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/** Isolates the Android TV listings permission request from the launcher screen. */
public final class TvListingsPermissionActivity extends Activity {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { PERMISSION }, 1);
        } else {
            finishWithResult();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        finishWithResult();
    }

    private void finishWithResult() {
        setResult(RESULT_OK);
        finish();
    }
}
