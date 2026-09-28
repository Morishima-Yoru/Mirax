package com.secondscreen.receiver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Older launches open the sidebar home. The picture is drawn there. */
public class PlayerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, SetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
