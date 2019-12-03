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
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MicrophoneInfo;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    static final String TAG = "MultiAudioRecordMain";

    private static final String MIME_TYPE = "audio/mp4a-latm";
    private static final String ENCODER_NAME = "OMX.google.aac.encoder";
    private static final int SAMPLE_RATE = 44100;	// 44.1[KHz] is only setting guaranteed to be available on all devices.
    private static final int BIT_RATE = 64000;

    protected static final int TIMEOUT_USEC = 10000;	// 10[msec]
    protected static final String OUTPUT_FILENAME = Environment.getExternalStorageDirectory() + "/rec.mp4";

    // One media codec corresponds to one audio stream, but there is always only one muxer
    int mNumberOfTracks = 1;
    List<AudioRecord> mAudioRecords;
    List<MediaCodec> mMediaCodecs;
    MediaMuxer mMediaMuxer;

    MediaFormat mMediaFormat;
    int mBufferSize;
    Map<Integer, Integer> mIndexTrackIndexMap;
    Map<Integer, MediaCodec.BufferInfo> mIndexBufferInfoMap;

    Boolean mIsCapturing = false;
    Boolean mIsEOS = true;
    Boolean mRequestStop = false;
    Boolean mMuxerStarted = false;
    AudioThread mAudioThread;

    String[] PERMISSIONS = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
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
        mIndexTrackIndexMap = new ArrayMap<>();
        mIndexBufferInfoMap = new ArrayMap<>();

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

        MediaCodecList mcl = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] infos = mcl.getCodecInfos();
        for (MediaCodecInfo info: infos) {
            if (info.isEncoder()) {
                Log.i(TAG, info.getName() + ", " + Arrays.toString(info.getSupportedTypes()));
            }
        }

        for (int i = 0; i < mNumberOfTracks; i++) {
            MediaCodec mc = MediaCodec.createByCodecName(ENCODER_NAME);
            mc.configure(mMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mMediaCodecs.add(mc);
            mIndexBufferInfoMap.put(i, new MediaCodec.BufferInfo());
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

    public void onStartButton(View view) {
        Log.i(TAG, "Start");

        startRecording();
    }

    public void onStopButton(View view) {
        Log.i(TAG, "Stop");

        stopRecording();
    }

    private void startRecording() {
        if (mIsCapturing) {
            showSnackBar("Recording in progress!");
            return;
        }
        showSnackBar("Start recording audio on " + mNumberOfTracks + " channels");
        mIsCapturing = true;
        mIsEOS = false;
        mRequestStop = false;

        mAudioThread = new AudioThread();
        mAudioThread.start();
    }

    private void showSnackBar(String message) {
        Snackbar snackBar = Snackbar.make(findViewById(R.id.mainConstraintLayout), message, Snackbar.LENGTH_SHORT);
        snackBar.show();
    }

    private void stopRecording() {
        if (mRequestStop) {
            if (mIsCapturing) {
                showSnackBar("Stop already requested!");
            }
            else {
                showSnackBar("Recording not in progress");
            }
            return;
        }
        showSnackBar("Request stop recording");
        mRequestStop = true;
    }

    private class AudioThread extends Thread {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            ByteBuffer buf = ByteBuffer.allocateDirect(mBufferSize);
            int readBytes;
            for (int i = 0; i < mNumberOfTracks; i++) {
                mAudioRecords.get(i).startRecording();
                mMediaCodecs.get(i).start();
            }

            while (!mRequestStop) {
                buf.clear();
                for (int i = 0; i < mNumberOfTracks; i++) {
                    readBytes = mAudioRecords.get(i).read(buf, mBufferSize);
                    if (readBytes > 0) {
                        buf.position(readBytes);
                        buf.flip();
                        Log.i(TAG, "read(): Write " + readBytes + " bytes");
                        encode(i, buf, readBytes, getPTSUs());
                        drain(i);
                    }
                }
            }
            // Request stop, send EOF
            for (int i = 0; i < mNumberOfTracks; i++) {
                Log.i(TAG, "Stopping");
                encode(i, null, 0, getPTSUs());
                drain(i);
                mAudioRecords.get(i).stop();
                mMediaCodecs.get(i).stop();
            }
            mMediaMuxer.stop();
        }
    }

    protected void encode(int index, final ByteBuffer buffer, final int length, final long presentationTimeUs) {
        MediaCodec mc = mMediaCodecs.get(index);
        while (mIsCapturing) {
            int inputBufferIndex = mc.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                final ByteBuffer inputBuffer = mc.getInputBuffer(inputBufferIndex);
                if (inputBuffer == null) {
                    Log.e(TAG, "getInputBuffer returns null");
                    continue;
                }
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
                    Log.i(TAG, "Queueing input buffer");
                    mc.queueInputBuffer(inputBufferIndex, 0, length,
                            presentationTimeUs, 0);
                }
                break;
            } else if (inputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.e(TAG, "DequeueInputBuffer returns " + inputBufferIndex);
            }
        }
    }

    protected void drain(int index) {
        int count = 0;
        MediaCodec mc = mMediaCodecs.get(index);
        MediaCodec.BufferInfo bi = mIndexBufferInfoMap.get(index);
        while (mIsCapturing) {
            int outputBufferIndex = mc.dequeueOutputBuffer(bi, TIMEOUT_USEC);
            if (outputBufferIndex >= 0) {
                final ByteBuffer encodedData = mc.getOutputBuffer(outputBufferIndex);
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer at " + outputBufferIndex + " is null");
                }

                if (bi.size != 0) {
                    Log.i(TAG, "drain(): Writing encodedData");
                    bi.presentationTimeUs = getPTSUs();
                    mMediaMuxer.writeSampleData(mIndexTrackIndexMap.get(index), encodedData, bi);
                    prevOutputPTSUs = bi.presentationTimeUs;
                }

                mc.releaseOutputBuffer(outputBufferIndex, false);
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    mIsCapturing = false;
                    break;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!mIsEOS) {
                    if (++count > 5)
                        break;		// out of while
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mMuxerStarted) {
                    Log.e(TAG, "Muxer started multiple times");
                }
                final MediaFormat format = mc.getOutputFormat();
                mIndexTrackIndexMap.put(index, mMediaMuxer.addTrack(format));
                mMediaMuxer.start();
                mMuxerStarted = true;
                Log.i(TAG, "Muxer started");
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
