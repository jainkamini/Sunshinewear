package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Created by Kamini on 2/19/2016.
 */
public class WatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFace.Engine> mWeakReference;

        public EngineHandler(WatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        private static final String TAG = "SunshineWatchFaceENGINE";

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private static final String FORECAST_PATH = "/forecast";
        private static final String MAX_TEMP_KEY = "max_temp";
        private static final String MIN_TEMP_KEY = "min_temp";
        private static final String WEATHER_ICON_KEY = "weather_icon";

        private String mHighTemp = "25";
        private String mLowTemp = "16";
        private Bitmap mWeatherIcon;
        private GoogleApiClient mGoogleApiClient;

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.i("SUNSHINE", "CONNECTED");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "onConnectionSuspended: " + i);
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: SOMETHING IS WRONG!    " + connectionResult.getErrorCode());
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged called!");
            for (DataEvent event : dataEventBuffer) {
                DataItem item = event.getDataItem();
                if (FORECAST_PATH.equals(item.getUri().getPath())) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    mHighTemp = dataMap.getString(MAX_TEMP_KEY);
                    mLowTemp = dataMap.getString(MIN_TEMP_KEY);
                    new LoadBitmapAsyncTask().execute(dataMap.getAsset(WEATHER_ICON_KEY));
                    Log.d(TAG, "Max Temp=" + mHighTemp + "; Min Temp=" + mLowTemp);
                }
            }
        }

        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {

                if (params.length > 0) {

                    Asset asset = params[0];

                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.w(TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e(TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if (bitmap != null) {
                    mWeatherIcon = Bitmap.createScaledBitmap(
                            bitmap,
                            getResources().getDimensionPixelSize(R.dimen.weather_icon_width),
                            getResources().getDimensionPixelSize(R.dimen.weather_icon_height),
                            false);
                }
            }
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(WatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WatchFace.this.getResources();

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        //Android Wear lets your watch face determine the screen shape at runtime.
        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFace.this.getResources();
            boolean isRound = insets.isRound();

            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);

            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);

            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();

            Resources resources = WatchFace.this.getResources();

            String text = String.format("%d:%02d", mTime.hour, mTime.minute);

            SimpleDateFormat sdf = new SimpleDateFormat(resources.getString(R.string.format_date), Locale.getDefault());

            String date = sdf.format(new Date());

            //draw hour
            //mTextPaint.setColor(resources.getColor(R.color.digital_text));
            mTextPaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_hour));
            mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            canvas.drawText(text, mXOffset + resources.getDimension(R.dimen.offset_hour_x), mYOffset, mTextPaint);

            //draw date
            mTextPaint.setTypeface(NORMAL_TYPEFACE);
            mTextPaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_date));
            canvas.drawText(date, mXOffset, mYOffset + resources.getDimension(R.dimen.offset_date_y), mTextPaint);

            //draw icon
            Paint paintLine = new Paint();
            paintLine.setColor(Color.WHITE);

            //draw line
            float x = mXOffset + resources.getDimension(R.dimen.line_initial_x);
            float y = mYOffset + resources.getDimension(R.dimen.line_y);
            float dx = x + resources.getDimension(R.dimen.line_dx);
            canvas.drawLine(x, y, dx, y, paintLine);

            int mBitmapWidthAndHeight = (int) resources.getDimension(R.dimen.image_size);

            Bitmap icon = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);
            icon = Bitmap.createScaledBitmap(icon, mBitmapWidthAndHeight, mBitmapWidthAndHeight, false);

            if(icon != null) {
                Paint paintIcon = new Paint();
                paintIcon.setAntiAlias(true);
                if(mWeatherIcon != null) {
                    canvas.drawBitmap(mWeatherIcon, mXOffset,
                            mYOffset + resources.getDimension(R.dimen.icon_dy), paintIcon);
                } else {
                    canvas.drawBitmap(icon, mXOffset,
                            mYOffset + resources.getDimension(R.dimen.icon_dy), paintIcon);
                }
            }

            //set bold the max temp
            mTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            //draw temp max
            String max = String.format("%s", mHighTemp);
            mTextPaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_temp));
            canvas.drawText(max, mXOffset + resources.getDimension(R.dimen.temp_max_dx),
                    mYOffset + resources.getDimension(R.dimen.temp_dy), mTextPaint);

            mTextPaint.setTypeface(NORMAL_TYPEFACE);

            //draw temp min
            String min = String.format("%s", mLowTemp);
            mTextPaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_temp));
            canvas.drawText(min, mXOffset + resources.getDimension(R.dimen.temp_min_dx),
                    mYOffset + resources.getDimension(R.dimen.temp_dy), mTextPaint);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                Log.d(TAG, "Google Api Client connected!");
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }

                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = WatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}