package com.example.multiaudiorecord;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MicrophoneInfo;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static final String TAG = "MultiAudioRecordMain";

    private static final String MIME_TYPE = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;	// 44.1[KHz] is only setting guaranteed to be available on all devices.
    private static final int BIT_RATE = 64000;

    protected static final int TIMEOUT_USEC = 10000;	// 10[msec]
    protected static final String OUTPUT_FILENAME = "rec.mp4";

    // One media codec corresponds to one audio stream, but there is always only one muxer
    int mNumberOfTracks = 1;
    List<AudioRecord> mAudioRecords;
    List<MediaCodec> mMediaCodecs;
    MediaMuxer mMediaMuxer;

    MediaFormat mMediaFormat;
    MediaCodec.BufferInfo mBufferInfo;
    int mBufferSize;
    int mTrackIndex;

    Boolean mIsCapturing = false;
    Boolean mIsEOS = true;
    Boolean mMuxerStarted = false;

    String[] PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            setup();
        } catch (Exception e) {
            Log.e(TAG, "Setup failed", e);
        }
    }

    // Setup
    private void setup() throws IOException {

        if(!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, 1);
        }

        mAudioRecords = new ArrayList<>();
        mMediaCodecs = new ArrayList<>();

        // Setup AudioRecord list
        mBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        for (int i = 0; i < mNumberOfTracks; i++) {
            AudioRecord ar = new AudioRecord(i, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, mBufferSize);
            mAudioRecords.add(ar);
            Log.i(TAG, "There are " + ar.getActiveMicrophones().size() + " active microphones for track " + i);
        }

        // Setup MediaCodec list
        mMediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1);
        mMediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
        mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        mMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        for (int i = 0; i < mNumberOfTracks; i++) {
            MediaCodec mc = MediaCodec.createByCodecName(MIME_TYPE);
            mc.configure(mMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }

        // Generate muxer
        mMediaMuxer = new MediaMuxer(OUTPUT_FILENAME, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
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


    public void onStartButton(View view) throws IOException {

        Log.i(TAG, "Start");

        Snackbar snackBar = Snackbar.make(findViewById(R.id.mainConstraintLayout), "Start recording audio on " + mNumberOfTracks + " channels", Snackbar.LENGTH_SHORT);
        snackBar.show();

        startRecording();
    }

    public void onStopButton(View view) {
        Log.i(TAG, "Stop");

        Snackbar snackBar = Snackbar.make(findViewById(R.id.mainConstraintLayout), "Recording stopped. File saved to " + OUTPUT_FILENAME, Snackbar.LENGTH_SHORT);
        snackBar.show();

        stopRecording();
    }

    private void startRecording() {
        mIsCapturing = true;
        mIsEOS = false;
    }

    private void stopRecording() {

    }

    private class AudioThread extends Thread {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            ByteBuffer buf = ByteBuffer.allocateDirect(mBufferSize);
            int readBytes;
            for (int i = 0; i < mNumberOfTracks; i++) {
                mAudioRecords.get(i).startRecording();
            }

            try {
                for (; mIsCapturing && !mIsEOS; ) {
                    buf.clear();
                    for (int i = 0; i < mNumberOfTracks; i++) {
                        readBytes = mAudioRecords.get(i).read(buf, mBufferSize);
                        if (readBytes > 0) {
                            buf.position(readBytes);
                            buf.flip();
                            encode(i, buf, readBytes, getPTSUs());
                            drain(i);
                        }
                    }
                }
                for (int i = 0; i < mNumberOfTracks; i++)
                    drain(i);
            } finally {
                for (int i = 0; i < mNumberOfTracks; i++) {
                    mAudioRecords.get(i).stop();
                }
            }
        }
    }

    protected void drain(int index) {
        MediaCodec mc = mMediaCodecs.get(index);
        while (mIsCapturing) {
            int outputBufferIndex = mc.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (outputBufferIndex >= 0) {
                final ByteBuffer encodedData = mc.getOutputBuffer(outputBufferIndex);
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer at " + outputBufferIndex + " is null");
                }

                if (mBufferInfo.size != 0) {
                    mBufferInfo.presentationTimeUs = getPTSUs();
                    mMediaMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                }

                mc.releaseOutputBuffer(outputBufferIndex, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    mIsCapturing = false;
                    break;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // do nothing
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                final MediaFormat format = mc.getOutputFormat();
                mTrackIndex = mMediaMuxer.addTrack(format);
                mMuxerStarted = true;

            }
        }
    }

    protected void encode(int index, final ByteBuffer buffer, final int length, final long presentationTimeUs) {
        if (!mIsCapturing) return;
        MediaCodec mc = mMediaCodecs.get(index);
        while (mIsCapturing) {
            int inputBufferIndex = mc.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {

                final ByteBuffer inputBuffer = mc.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                if (buffer != null) {
                    inputBuffer.put(buffer);
                }

                if (length <= 0) {
                    // send EOS
                    mIsEOS = true;
                    mc.queueInputBuffer(inputBufferIndex, 0, 0,
                            presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    mc.queueInputBuffer(inputBufferIndex, 0, length,
                            presentationTimeUs, 0);
                }

            } else if (inputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.e(TAG, "DequeueInputBuffer returns " + inputBufferIndex);
            }
        }
    }

    private long prevOutputPTSUs = 0;
    protected long getPTSUs() {
        long result = System.nanoTime() / 1000L;
        if (result < prevOutputPTSUs) {
            result = (prevOutputPTSUs - result) + result;
        }
        return result;
    }
}
