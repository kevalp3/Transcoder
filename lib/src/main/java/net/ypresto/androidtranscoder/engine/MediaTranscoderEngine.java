/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ypresto.androidtranscoder.engine;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import net.ypresto.androidtranscoder.BuildConfig;
import net.ypresto.androidtranscoder.source.DataSource;
import net.ypresto.androidtranscoder.strategy.OutputStrategy;
import net.ypresto.androidtranscoder.strategy.OutputStrategyException;
import net.ypresto.androidtranscoder.utils.ISO6709LocationParser;
import net.ypresto.androidtranscoder.utils.Logger;

import java.io.FileDescriptor;
import java.io.IOException;

import androidx.annotation.NonNull;

/**
 * Internal engine, do not use this directly.
 */
// TODO: treat encrypted data
public class MediaTranscoderEngine {
    private static final String TAG = "MediaTranscoderEngine";
    private static final Logger LOG = new Logger(TAG);

    private static final double PROGRESS_UNKNOWN = -1.0;
    private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
    private static final long PROGRESS_INTERVAL_STEPS = 10;
    private DataSource mDataSource;
    private TrackTranscoder mVideoTrackTranscoder;
    private TrackTranscoder mAudioTrackTranscoder;
    private MediaExtractor mExtractor;
    private MediaMuxer mMuxer;
    private volatile double mProgress;
    private ProgressCallback mProgressCallback;
    private long mDurationUs;

    /**
     * Do not use this constructor unless you know what you are doing.
     */
    public MediaTranscoderEngine() {
    }

    public void setDataSource(DataSource dataSource) {
        mDataSource = dataSource;
    }

    public ProgressCallback getProgressCallback() {
        return mProgressCallback;
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        mProgressCallback = progressCallback;
    }

    /**
     * NOTE: This method is thread safe.
     */
    public double getProgress() {
        return mProgress;
    }

    /**
     * Performs transcoding. Blocks current thread.
     *
     * @param outputPath File path to output transcoded video file.
     * @param videoStrategy Output format strategy.
     * @param audioStrategy Output format strategy.
     * @throws IOException                  when input or output file could not be opened.
     * @throws InvalidOutputFormatException when output format is not supported.
     * @throws InterruptedException         when cancel to transcode.
     */
    public void transcode(@NonNull String outputPath,
                          @NonNull OutputStrategy videoStrategy,
                          @NonNull OutputStrategy audioStrategy) throws IOException, InterruptedException {
        if (mDataSource == null) {
            throw new IllegalStateException("Data source is not set.");
        }
        try {
            // NOTE: use single extractor to keep from running out audio track fast.
            mExtractor = new MediaExtractor();
            mDataSource.apply(mExtractor);
            mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupMetadata();
            setupTrackTranscoders(videoStrategy, audioStrategy);
            runPipelines();
            mMuxer.stop();
        } finally {
            try {
                if (mVideoTrackTranscoder != null) {
                    mVideoTrackTranscoder.release();
                    mVideoTrackTranscoder = null;
                }
                if (mAudioTrackTranscoder != null) {
                    mAudioTrackTranscoder.release();
                    mAudioTrackTranscoder = null;
                }
                if (mExtractor != null) {
                    mExtractor.release();
                    mExtractor = null;
                }
            } catch (RuntimeException e) {
                // Too fatal to make alive the app, because it may leak native resources.
                //noinspection ThrowFromFinallyBlock
                throw new Error("Could not shutdown extractor, codecs and muxer pipeline.", e);
            }
            try {
                if (mMuxer != null) {
                    mMuxer.release();
                    mMuxer = null;
                }
            } catch (RuntimeException e) {
                LOG.e("Failed to release muxer.", e);
            }
        }
    }

    private void setupMetadata() {
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        mDataSource.apply(mediaMetadataRetriever);

        String rotationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        try {
            mMuxer.setOrientationHint(Integer.parseInt(rotationString));
        } catch (NumberFormatException e) {
            // skip
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            String locationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            if (locationString != null) {
                float[] location = new ISO6709LocationParser().parse(locationString);
                if (location != null) {
                    mMuxer.setLocation(location[0], location[1]);
                } else {
                    LOG.v("Failed to parse the location metadata: " + locationString);
                }
            }
        }

        try {
            mDurationUs = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
        } catch (NumberFormatException e) {
            mDurationUs = -1;
        }
        LOG.v("Duration (us): " + mDurationUs);
    }

    @SuppressWarnings("CaughtExceptionImmediatelyRethrown")
    private void setupTrackTranscoders(OutputStrategy videoStrategy, OutputStrategy audioStrategy) {
        QueuedMuxer queuedMuxer = new QueuedMuxer(mMuxer, new QueuedMuxer.Listener() {
            @Override
            public void onDetermineOutputFormat() {
                MediaFormatValidator.validateVideoOutputFormat(mVideoTrackTranscoder.getDeterminedFormat());
                MediaFormatValidator.validateAudioOutputFormat(mAudioTrackTranscoder.getDeterminedFormat());
            }
        });
        TracksInfo info = TracksInfo.fromExtractor(mExtractor);

        // Video format.
        if (!info.hasVideo()) {
            mVideoTrackTranscoder = new NoOpTrackTranscoder();
        } else {
            try {
                MediaFormat videoFormat = videoStrategy.createOutputFormat(info.videoTrackFormat);
                if (videoFormat == null) {
                    mVideoTrackTranscoder = new NoOpTrackTranscoder();
                } else if (videoFormat == info.videoTrackFormat) {
                    mVideoTrackTranscoder = new PassThroughTrackTranscoder(mExtractor,
                            info.videoTrackIndex, queuedMuxer, QueuedMuxer.SampleType.VIDEO);
                } else {
                    mVideoTrackTranscoder = new VideoTrackTranscoder(mExtractor,
                            info.videoTrackIndex, videoFormat, queuedMuxer);
                }
            } catch (OutputStrategyException strategyException) {
                // Abort. TODO add policy for alreadyCompressed
                throw strategyException;
            }
        }
        mVideoTrackTranscoder.setup();

        // Audio format.
        if (!info.hasAudio()) {
            mAudioTrackTranscoder = new NoOpTrackTranscoder();
        } else {
            try {
                MediaFormat audioFormat = audioStrategy.createOutputFormat(info.audioTrackFormat);
                if (audioFormat == null) {
                    mAudioTrackTranscoder = new NoOpTrackTranscoder();
                } else if (audioFormat == info.audioTrackFormat) {
                    mAudioTrackTranscoder = new PassThroughTrackTranscoder(mExtractor,
                            info.audioTrackIndex, queuedMuxer, QueuedMuxer.SampleType.AUDIO);
                } else {
                    mAudioTrackTranscoder = new AudioTrackTranscoder(mExtractor,
                            info.audioTrackIndex, audioFormat, queuedMuxer);
                }
            } catch (OutputStrategyException strategyException) {
                // Abort. TODO add policy for alreadyCompressed
                throw strategyException;
            }
        }
        mAudioTrackTranscoder.setup();

        if (info.hasVideo()) mExtractor.selectTrack(info.videoTrackIndex);
        if (info.hasAudio()) mExtractor.selectTrack(info.audioTrackIndex);
    }

    private void runPipelines() throws InterruptedException {
        long loopCount = 0;
        if (mDurationUs <= 0) {
            double progress = PROGRESS_UNKNOWN;
            mProgress = progress;
            if (mProgressCallback != null) mProgressCallback.onProgress(progress); // unknown
        }
        while (!(mVideoTrackTranscoder.isFinished() && mAudioTrackTranscoder.isFinished())) {
            boolean stepped = mVideoTrackTranscoder.stepPipeline()
                    || mAudioTrackTranscoder.stepPipeline();
            loopCount++;
            if (mDurationUs > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
                double videoProgress = mVideoTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) mVideoTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
                double audioProgress = mAudioTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) mAudioTrackTranscoder.getWrittenPresentationTimeUs() / mDurationUs);
                double progress = (videoProgress + audioProgress) / 2.0;
                mProgress = progress;
                if (mProgressCallback != null) mProgressCallback.onProgress(progress);
            }
            if (!stepped) {
                Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
            }
        }
    }

    public interface ProgressCallback {
        /**
         * Called to notify progress. Same thread which initiated transcode is used.
         *
         * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
         */
        void onProgress(double progress);
    }
}
