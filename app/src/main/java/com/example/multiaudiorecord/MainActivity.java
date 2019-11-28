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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static final String TAG = "MultiAudioRecordMain";

    private static final String MIME_TYPE = "audio/mp4a-latm";
    private static final int SAMPLE_RATE = 44100;	// 44.1[KHz] is only setting guaranteed to be available on all devices.
    private static final int BIT_RATE = 64000;
    public static final int SAMPLES_PER_FRAME = 1024;	// AAC, bytes/frame/channel
    public static final int FRAMES_PER_BUFFER = 25; 	// AAC, frame/buffer/sec

    protected static final int TIMEOUT_USEC = 10000;	// 10[msec]

    AudioRecord ar1, ar2, ar3, ar4;
    MediaMuxer mMediaMuxer;
    MediaCodec mMediaCodec;
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

    private void setup() throws IOException {

        if(!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, 1);
        }

        // Generate AudioRecord instances
        mBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        ar1 = audioRecordFactory(mBufferSize, 0);
        List<MicrophoneInfo> mics = ar1.getActiveMicrophones();
        Log.i(TAG, "There are " + mics.size() + " active microphones for ar1");

        // Generate codec
        mMediaFormat = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1);
        mMediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO);
        mMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        mMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);

        Log.i(TAG, "format: " + mMediaFormat);
        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mMediaCodec.configure(mMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        // Generate muxer
        mMediaMuxer = new MediaMuxer("rec.mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Misc
        mBufferInfo = new MediaCodec.BufferInfo();
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
        return new AudioRecord(audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
    }

    public void onStartButton(View view) throws IOException {

        Log.i(TAG, "Start");

        mIsCapturing = true;

        mMediaCodec.start();
    }

    private class AudioThread extends Thread {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            ByteBuffer buf = ByteBuffer.allocateDirect(SAMPLES_PER_FRAME);
            int readBytes;
            ar1.startRecording();
            try {
                for (; mIsCapturing && !mIsEOS; ) {
                    buf.clear();
                    readBytes = ar1.read(buf, SAMPLES_PER_FRAME);
                    if (readBytes > 0) {
                        buf.position(readBytes);
                        buf.flip();
                        encode(buf, readBytes, getPTSUs());
                        drain();
                    }
                }
                drain();
            } finally {
                ar1.stop();
            }
        }
    }

    protected void drain() {
        while (mIsCapturing) {
            int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (outputBufferIndex >= 0) {
                final ByteBuffer encodedData = mMediaCodec.getOutputBuffer(outputBufferIndex);
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer at " + outputBufferIndex + " is null");
                }

                if (mBufferInfo.size != 0) {
                    mBufferInfo.presentationTimeUs = getPTSUs();
                    mMediaMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                }

                mMediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    mIsCapturing = false;
                    break;
                }
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // do nothing
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                final MediaFormat format = mMediaCodec.getOutputFormat();
                mTrackIndex = mMediaMuxer.addTrack(format);
                mMuxerStarted = true;

            }
        }
    }

    protected void encode(final ByteBuffer buffer, final int length, final long presentationTimeUs) {
        if (!mIsCapturing) return;
        while (mIsCapturing) {
            int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {

                final ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex);
                inputBuffer.clear();
                if (buffer != null) {
                    inputBuffer.put(buffer);
                }

                if (length <= 0) {
                    // send EOS
                    mIsEOS = true;
                    mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0,
                            presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    mMediaCodec.queueInputBuffer(inputBufferIndex, 0, length,
                            presentationTimeUs, 0);
                }

            } else if (inputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.e(TAG, "DequeueInputBuffer returns " + inputBufferIndex);
            }
        }
    }

    public void onStopButton(View view
    ) {
        Log.i(TAG, "Stop");
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
