package com.example.multiaudiorecord;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MicrophoneInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static final String TAG = "MultiAudioRecordMain";

    AudioRecord ar1, ar2, ar3, ar4;

    String[] PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setup();
    }

    private void setup() {

        if(!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, 1);
        }

        // Generate AudioRecord instances
        int bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        ar1 = audioRecordFactory(bufferSize, 0);
        try {
            List<MicrophoneInfo> mics = ar1.getActiveMicrophones();
            Log.i(TAG, "There are " + mics.size() + " active microphones");

        } catch (IOException e) {
            Log.e(TAG, "Unable to setup AudioRecord instance");
        }
    }

    public static boolean hasPermissions(Context context, String... permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    AudioRecord audioRecordFactory(int bufferSize, int audioSource) {
        return new AudioRecord(audioSource, 44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
    }

    public void onStartButton(View view) {
        Log.i(TAG, "Start");
    }

    public void onStopButton(View view
    ) {
        Log.i(TAG, "Stop");
    }
}
