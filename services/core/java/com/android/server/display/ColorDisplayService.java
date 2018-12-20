/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.display;

import static com.android.server.display.DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.display.IColorDisplayManager;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.provider.Settings.System;
import android.util.MathUtils;
import android.util.Slog;
import android.view.animation.AnimationUtils;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ColorDisplayController;
import com.android.server.DisplayThread;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Controls the display's color transforms.
 */
public final class ColorDisplayService extends SystemService {

    private static final String TAG = "ColorDisplayService";

    /**
     * The transition time, in milliseconds, for Night Display to turn on/off.
     */
    private static final long TRANSITION_DURATION = 3000L;

    /**
     * The identity matrix, used if one of the given matrices is {@code null}.
     */
    private static final float[] MATRIX_IDENTITY = new float[16];

    static {
        Matrix.setIdentityM(MATRIX_IDENTITY, 0);
    }

    /**
     * Evaluator used to animate color matrix transitions.
     */
    private static final ColorMatrixEvaluator COLOR_MATRIX_EVALUATOR = new ColorMatrixEvaluator();

    private final Handler mHandler;

    private float[] mMatrixNight = new float[16];

    private final float[] mColorTempCoefficients = new float[9];

    private int mCurrentUser = UserHandle.USER_NULL;
    private ContentObserver mUserSetupObserver;
    private boolean mBootCompleted;

    private ColorDisplayController mNightDisplayController;
    private ContentObserver mContentObserver;
    private ValueAnimator mColorMatrixAnimator;

    private Boolean mIsNightDisplayActivated;
    private NightDisplayAutoMode mNightDisplayAutoMode;

    public ColorDisplayService(Context context) {
        super(context);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COLOR_DISPLAY_SERVICE, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase >= PHASE_BOOT_COMPLETED) {
            mBootCompleted = true;

            // Register listeners now that boot is complete.
            if (mCurrentUser != UserHandle.USER_NULL && mUserSetupObserver == null) {
                setUp();
            }
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        super.onStartUser(userHandle);

        if (mCurrentUser == UserHandle.USER_NULL) {
            onUserChanged(userHandle);
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);

        onUserChanged(userHandle);
    }

    @Override
    public void onStopUser(int userHandle) {
        super.onStopUser(userHandle);

        if (mCurrentUser == userHandle) {
            onUserChanged(UserHandle.USER_NULL);
        }
    }

    private void onUserChanged(int userHandle) {
        final ContentResolver cr = getContext().getContentResolver();

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (mUserSetupObserver != null) {
                cr.unregisterContentObserver(mUserSetupObserver);
                mUserSetupObserver = null;
            } else if (mBootCompleted) {
                tearDown();
            }
        }

        mCurrentUser = userHandle;

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (!isUserSetupCompleted(cr, mCurrentUser)) {
                mUserSetupObserver = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (isUserSetupCompleted(cr, mCurrentUser)) {
                            cr.unregisterContentObserver(this);
                            mUserSetupObserver = null;

                            if (mBootCompleted) {
                                setUp();
                            }
                        }
                    }
                };
                cr.registerContentObserver(Secure.getUriFor(Secure.USER_SETUP_COMPLETE),
                        false /* notifyForDescendants */, mUserSetupObserver, mCurrentUser);
            } else if (mBootCompleted) {
                setUp();
            }
        }
    }

    private static boolean isUserSetupCompleted(ContentResolver cr, int userHandle) {
        return Secure.getIntForUser(cr, Secure.USER_SETUP_COMPLETE, 0, userHandle) == 1;
    }

    private void setUp() {
        Slog.d(TAG, "setUp: currentUser=" + mCurrentUser);

        mNightDisplayController = new ColorDisplayController(getContext(), mCurrentUser);

        // Listen for external changes to any of the settings.
        if (mContentObserver == null) {
            mContentObserver = new ContentObserver(new Handler(DisplayThread.get().getLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);

                    final String setting = uri == null ? null : uri.getLastPathSegment();
                    if (setting != null) {
                        switch (setting) {
                            case Secure.NIGHT_DISPLAY_ACTIVATED:
                                onNightDisplayActivated(mNightDisplayController.isActivated());
                                break;
                            case Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE:
                                onNightDisplayColorTemperatureChanged(
                                        mNightDisplayController.getColorTemperature());
                                break;
                            case Secure.NIGHT_DISPLAY_AUTO_MODE:
                                onNightDisplayAutoModeChanged(
                                        mNightDisplayController.getAutoMode());
                                break;
                            case Secure.NIGHT_DISPLAY_CUSTOM_START_TIME:
                                onNightDisplayCustomStartTimeChanged(
                                        mNightDisplayController.getCustomStartTime());
                                break;
                            case Secure.NIGHT_DISPLAY_CUSTOM_END_TIME:
                                onNightDisplayCustomEndTimeChanged(
                                        mNightDisplayController.getCustomEndTime());
                                break;
                            case System.DISPLAY_COLOR_MODE:
                                onDisplayColorModeChanged(mNightDisplayController.getColorMode());
                                break;
                            case Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED:
                            case Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED:
                                onAccessibilityTransformChanged();
                                break;
                        }
                    }
                }
            };
        }
        final ContentResolver cr = getContext().getContentResolver();
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_ACTIVATED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_AUTO_MODE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_START_TIME),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_END_TIME),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(System.getUriFor(System.DISPLAY_COLOR_MODE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(
                Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(
                Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);

        // Set the color mode, if valid, and immediately apply the updated tint matrix based on the
        // existing activated state. This ensures consistency of tint across the color mode change.
        onDisplayColorModeChanged(mNightDisplayController.getColorMode());

        // Reset the activated state.
        mIsNightDisplayActivated = null;

        setCoefficientMatrix(getContext(), DisplayTransformManager.needsLinearColorMatrix());

        // Prepare color transformation matrix.
        setMatrix(mNightDisplayController.getColorTemperature(), mMatrixNight);

        // Initialize the current auto mode.
        onNightDisplayAutoModeChanged(mNightDisplayController.getAutoMode());

        // Force the initialization current activated state.
        if (mIsNightDisplayActivated == null) {
            onNightDisplayActivated(mNightDisplayController.isActivated());
        }
    }

    private void tearDown() {
        Slog.d(TAG, "tearDown: currentUser=" + mCurrentUser);

        getContext().getContentResolver().unregisterContentObserver(mContentObserver);

        if (mNightDisplayController != null) {
            mNightDisplayController = null;
        }

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onStop();
            mNightDisplayAutoMode = null;
        }

        if (mColorMatrixAnimator != null) {
            mColorMatrixAnimator.end();
            mColorMatrixAnimator = null;
        }
    }

    private void onNightDisplayActivated(boolean activated) {
        if (mIsNightDisplayActivated == null || mIsNightDisplayActivated != activated) {
            Slog.i(TAG, activated ? "Turning on night display" : "Turning off night display");

            mIsNightDisplayActivated = activated;

            if (mNightDisplayAutoMode != null) {
                mNightDisplayAutoMode.onActivated(activated);
            }

            applyTint(false);
        }
    }

    private void onNightDisplayAutoModeChanged(int autoMode) {
        Slog.d(TAG, "onNightDisplayAutoModeChanged: autoMode=" + autoMode);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onStop();
            mNightDisplayAutoMode = null;
        }

        if (autoMode == ColorDisplayController.AUTO_MODE_CUSTOM) {
            mNightDisplayAutoMode = new CustomNightDisplayAutoMode();
        } else if (autoMode == ColorDisplayController.AUTO_MODE_TWILIGHT) {
            mNightDisplayAutoMode = new TwilightNightDisplayAutoMode();
        }

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onStart();
        }
    }

    private void onNightDisplayCustomStartTimeChanged(LocalTime startTime) {
        Slog.d(TAG, "onNightDisplayCustomStartTimeChanged: startTime=" + startTime);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    private void onNightDisplayCustomEndTimeChanged(LocalTime endTime) {
        Slog.d(TAG, "onNightDisplayCustomEndTimeChanged: endTime=" + endTime);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    private void onNightDisplayColorTemperatureChanged(int colorTemperature) {
        setMatrix(colorTemperature, mMatrixNight);
        applyTint(true);
    }

    private void onDisplayColorModeChanged(int mode) {
        if (mode == -1) {
            return;
        }

        // Cancel the night display tint animator if it's running.
        if (mColorMatrixAnimator != null) {
            mColorMatrixAnimator.cancel();
        }

        setCoefficientMatrix(getContext(), DisplayTransformManager.needsLinearColorMatrix(mode));
        setMatrix(mNightDisplayController.getColorTemperature(), mMatrixNight);

        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        dtm.setColorMode(mode, (mIsNightDisplayActivated != null && mIsNightDisplayActivated)
                ? mMatrixNight : MATRIX_IDENTITY);
    }

    private void onAccessibilityTransformChanged() {
        onDisplayColorModeChanged(mNightDisplayController.getColorMode());
    }

    /**
     * Set coefficients based on whether the color matrix is linear or not.
     */
    private void setCoefficientMatrix(Context context, boolean needsLinear) {
        final String[] coefficients = context.getResources().getStringArray(needsLinear
                ? R.array.config_nightDisplayColorTemperatureCoefficients
                : R.array.config_nightDisplayColorTemperatureCoefficientsNative);
        for (int i = 0; i < 9 && i < coefficients.length; i++) {
            mColorTempCoefficients[i] = Float.parseFloat(coefficients[i]);
        }
    }

    /**
     * Applies current color temperature matrix, or removes it if deactivated.
     *
     * @param immediate {@code true} skips transition animation
     */
    private void applyTint(boolean immediate) {
        // Cancel the old animator if still running.
        if (mColorMatrixAnimator != null) {
            mColorMatrixAnimator.cancel();
        }

        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        final float[] from = dtm.getColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY);
        final float[] to = mIsNightDisplayActivated ? mMatrixNight : MATRIX_IDENTITY;

        if (immediate) {
            dtm.setColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY, to);
        } else {
            mColorMatrixAnimator = ValueAnimator.ofObject(COLOR_MATRIX_EVALUATOR,
                    from == null ? MATRIX_IDENTITY : from, to);
            mColorMatrixAnimator.setDuration(TRANSITION_DURATION);
            mColorMatrixAnimator.setInterpolator(AnimationUtils.loadInterpolator(
                    getContext(), android.R.interpolator.fast_out_slow_in));
            mColorMatrixAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animator) {
                    final float[] value = (float[]) animator.getAnimatedValue();
                    dtm.setColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY, value);
                }
            });
            mColorMatrixAnimator.addListener(new AnimatorListenerAdapter() {

                private boolean mIsCancelled;

                @Override
                public void onAnimationCancel(Animator animator) {
                    mIsCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animator) {
                    if (!mIsCancelled) {
                        // Ensure final color matrix is set at the end of the animation. If the
                        // animation is cancelled then don't set the final color matrix so the new
                        // animator can pick up from where this one left off.
                        dtm.setColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY, to);
                    }
                    mColorMatrixAnimator = null;
                }
            });
            mColorMatrixAnimator.start();
        }
    }

    /**
     * Set the color transformation {@code MATRIX_NIGHT} to the given color temperature.
     *
     * @param colorTemperature color temperature in Kelvin
     * @param outTemp the 4x4 display transformation matrix for that color temperature
     */
    private void setMatrix(int colorTemperature, float[] outTemp) {
        if (outTemp.length != 16) {
            Slog.d(TAG, "The display transformation matrix must be 4x4");
            return;
        }

        Matrix.setIdentityM(mMatrixNight, 0);

        final float squareTemperature = colorTemperature * colorTemperature;
        final float red = squareTemperature * mColorTempCoefficients[0]
                + colorTemperature * mColorTempCoefficients[1] + mColorTempCoefficients[2];
        final float green = squareTemperature * mColorTempCoefficients[3]
                + colorTemperature * mColorTempCoefficients[4] + mColorTempCoefficients[5];
        final float blue = squareTemperature * mColorTempCoefficients[6]
                + colorTemperature * mColorTempCoefficients[7] + mColorTempCoefficients[8];
        outTemp[0] = red;
        outTemp[5] = green;
        outTemp[10] = blue;
    }

    /**
     * Returns the first date time corresponding to the local time that occurs before the provided
     * date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the prior LocalDateTime corresponding to this local time
     */
    @VisibleForTesting
    static LocalDateTime getDateTimeBefore(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time yesterday.
        return ldt.isAfter(compareTime) ? ldt.minusDays(1) : ldt;
    }

    /**
     * Returns the first date time corresponding to this local time that occurs after the provided
     * date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the next LocalDateTime corresponding to this local time
     */
    @VisibleForTesting
    static LocalDateTime getDateTimeAfter(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time tomorrow.
        return ldt.isBefore(compareTime) ? ldt.plusDays(1) : ldt;
    }

    private boolean isDeviceColorManagedInternal() {
        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        return dtm.isDeviceColorManaged();
    }

    /**
     * Returns the last time the night display transform activation state was changed, or {@link
     * LocalDateTime#MIN} if night display has never been activated.
     */
    private @NonNull LocalDateTime getNightDisplayLastActivatedTimeSetting() {
        final ContentResolver cr = getContext().getContentResolver();
        final String lastActivatedTime = Secure.getStringForUser(
                cr, Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME, getContext().getUserId());
        if (lastActivatedTime != null) {
            try {
                return LocalDateTime.parse(lastActivatedTime);
            } catch (DateTimeParseException ignored) {
            }
            // Uses the old epoch time.
            try {
                return LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(lastActivatedTime)),
                        ZoneId.systemDefault());
            } catch (DateTimeException | NumberFormatException ignored) {
            }
        }
        return LocalDateTime.MIN;
    }

    private abstract class NightDisplayAutoMode {

        public abstract void onActivated(boolean activated);

        public abstract void onStart();

        public abstract void onStop();

        public void onCustomStartTimeChanged(LocalTime startTime) {
        }

        public void onCustomEndTimeChanged(LocalTime endTime) {
        }
    }

    private final class CustomNightDisplayAutoMode extends NightDisplayAutoMode implements
            AlarmManager.OnAlarmListener {

        private final AlarmManager mAlarmManager;
        private final BroadcastReceiver mTimeChangedReceiver;

        private LocalTime mStartTime;
        private LocalTime mEndTime;

        private LocalDateTime mLastActivatedTime;

        CustomNightDisplayAutoMode() {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActivated();
                }
            };
        }

        private void updateActivated() {
            final LocalDateTime now = LocalDateTime.now();
            final LocalDateTime start = getDateTimeBefore(mStartTime, now);
            final LocalDateTime end = getDateTimeAfter(mEndTime, start);
            boolean activate = now.isBefore(end);

            if (mLastActivatedTime != null) {
                // Maintain the existing activated state if within the current period.
                if (mLastActivatedTime.isBefore(now) && mLastActivatedTime.isAfter(start)
                        && (mLastActivatedTime.isAfter(end) || now.isBefore(end))) {
                    activate = mNightDisplayController.isActivated();
                }
            }

            if (mIsNightDisplayActivated == null || mIsNightDisplayActivated != activate) {
                mNightDisplayController.setActivated(activate);
            }

            updateNextAlarm(mIsNightDisplayActivated, now);
        }

        private void updateNextAlarm(@Nullable Boolean activated, @NonNull LocalDateTime now) {
            if (activated != null) {
                final LocalDateTime next = activated ? getDateTimeAfter(mEndTime, now)
                        : getDateTimeAfter(mStartTime, now);
                final long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                mAlarmManager.setExact(AlarmManager.RTC, millis, TAG, this, null);
            }
        }

        @Override
        public void onStart() {
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);

            mStartTime = mNightDisplayController.getCustomStartTime();
            mEndTime = mNightDisplayController.getCustomEndTime();

            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            getContext().unregisterReceiver(mTimeChangedReceiver);

            mAlarmManager.cancel(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();
            updateNextAlarm(activated, LocalDateTime.now());
        }

        @Override
        public void onCustomStartTimeChanged(LocalTime startTime) {
            mStartTime = startTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onCustomEndTimeChanged(LocalTime endTime) {
            mEndTime = endTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onAlarm() {
            Slog.d(TAG, "onAlarm");
            updateActivated();
        }
    }

    private final class TwilightNightDisplayAutoMode extends NightDisplayAutoMode implements
            TwilightListener {

        private final TwilightManager mTwilightManager;
        private LocalDateTime mLastActivatedTime;

        TwilightNightDisplayAutoMode() {
            mTwilightManager = getLocalService(TwilightManager.class);
        }

        private void updateActivated(TwilightState state) {
            if (state == null) {
                // If there isn't a valid TwilightState then just keep the current activated
                // state.
                return;
            }

            boolean activate = state.isNight();
            if (mLastActivatedTime != null) {
                final LocalDateTime now = LocalDateTime.now();
                final LocalDateTime sunrise = state.sunrise();
                final LocalDateTime sunset = state.sunset();
                // Maintain the existing activated state if within the current period.
                if (mLastActivatedTime.isBefore(now) && (mLastActivatedTime.isBefore(sunrise)
                        ^ mLastActivatedTime.isBefore(sunset))) {
                    activate = mNightDisplayController.isActivated();
                }
            }

            if (mIsNightDisplayActivated == null || mIsNightDisplayActivated != activate) {
                mNightDisplayController.setActivated(activate);
            }
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();
        }

        @Override
        public void onStart() {
            mTwilightManager.registerListener(this, mHandler);
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();

            // Force an update to initialize state.
            updateActivated(mTwilightManager.getLastTwilightState());
        }

        @Override
        public void onStop() {
            mTwilightManager.unregisterListener(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            Slog.d(TAG, "onTwilightStateChanged: isNight="
                    + (state == null ? null : state.isNight()));
            updateActivated(state);
        }
    }

    /**
     * Interpolates between two 4x4 color transform matrices (in column-major order).
     */
    private static class ColorMatrixEvaluator implements TypeEvaluator<float[]> {

        /**
         * Result matrix returned by {@link #evaluate(float, float[], float[])}.
         */
        private final float[] mResultMatrix = new float[16];

        @Override
        public float[] evaluate(float fraction, float[] startValue, float[] endValue) {
            for (int i = 0; i < mResultMatrix.length; i++) {
                mResultMatrix[i] = MathUtils.lerp(startValue[i], endValue[i], fraction);
            }
            return mResultMatrix;
        }
    }

    private final class BinderService extends IColorDisplayManager.Stub {

        @Override
        public boolean isDeviceColorManaged() {
            final long token = Binder.clearCallingIdentity();
            try {
                return isDeviceColorManagedInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
