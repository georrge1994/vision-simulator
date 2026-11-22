package ru.georgiy.vision;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.util.Linkify;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import java.text.NumberFormat;
import java.util.Locale;

public final class VisionActivity extends Activity
        implements CameraFeed.Listener, PreviewView.Listener, DisplayManager.DisplayListener {
    private static final int REQUEST_CAMERA = 1;
    private static final int USE_FILTER = -1;
    private static final int BACKGROUND = 0xff101713;
    private static final int PANEL = 0xf017221b;
    private static final int PANEL_EDGE = 0xff3b4a3f;
    private static final int BUTTON_SURFACE = 0xff2c382f;
    private static final int TEXT = 0xfff3f7ee;
    private static final int MUTED = 0xffc5d0c4;
    private static final int ACCENT = 0xffd9f99c;
    private static final int INK = 0xff1b2816;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final int[] values = {0, 6, 36, 42};
    private final Button[] filters = new Button[3];
    private final Button[] distances = new Button[4];
    private final View[] sliderRows = new View[4];
    private final SeekBar[] strengths = new SeekBar[4];
    private final TextView[] readouts = new TextView[4];
    private FrameLayout root;
    private PreviewView preview;
    private CameraFeed camera;
    private DisplayManager displayManager;
    private SurfaceTexture texture;
    private SharedPreferences preferences;
    private NumberFormat integers;
    private NumberFormat percentages;
    private LinearLayout statusPanel;
    private TextView statusText;
    private Button statusAction;
    private Button normalVision;
    private Button sharpVision;
    private View filterOptions;
    private View distanceBlock;
    private TextView depthStatus;
    private Session arSession;
    private ArCoreApk.Availability arAvailability;
    private int arCheckGeneration;
    private boolean arChecking;
    private boolean arRunning;
    private boolean arFailed;
    private boolean hasDepth;
    private boolean resumed;
    private boolean rendererFailed;
    private int enabledFilters;
    private int acuity = USE_FILTER;
    private int distance;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        integers = NumberFormat.getIntegerInstance(locale);
        percentages = NumberFormat.getPercentInstance(locale);
        preferences = getSharedPreferences("vision", MODE_PRIVATE);
        int previousFilter = clamp(preferences.getInt("filter", Optics.MYOPIA),
                Optics.MYOPIA, Optics.HAZE);
        enabledFilters = preferences.getInt("filters", Optics.filterBit(previousFilter))
                & Optics.ALL_FILTERS;
        distance = clamp(preferences.getInt("depth_distance", Optics.AUTOMATIC),
                Optics.AUTOMATIC, Optics.DISTANCES_METERS.length - 1);
        for (int mode = Optics.MYOPIA; mode <= Optics.HAZE; mode++) {
            values[mode] = clamp(preferences.getInt("strength_" + mode, values[mode]), 1,
                    maxStrength(mode));
        }
        if (savedInstanceState != null) {
            int previousAcuity = savedInstanceState.getBoolean("clear") ? Optics.NORMAL : USE_FILTER;
            acuity = savedInstanceState.getInt("acuity", previousAcuity);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);

        displayManager = getSystemService(DisplayManager.class);
        camera = new CameraFeed(this, this);
        createUi();
        renderSelection();
        if (!preferences.getBoolean("privacy_notice_v1", false)) {
            root.post(() -> showPrivacyPolicy(true));
        } else if (!hasPermission() && !preferences.getBoolean("camera_asked", false)) {
            root.post(this::askForCamera);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        arAvailability = null;
        displayManager.registerDisplayListener(this, null);
        preview.onResume();
        preview.setDisplayRotation(displayDegrees());
        openPreview();
    }

    @Override protected void onPause() {
        resumed = false;
        arCheckGeneration++;
        arChecking = false;
        displayManager.unregisterDisplayListener(this);
        camera.close();
        preview.onPause();
        if (arRunning) stopArPreview();
        super.onPause();
    }

    @Override protected void onDestroy() {
        closeArSession();
        preview.dispose();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("acuity", acuity);
        super.onSaveInstanceState(state);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions,
            int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQUEST_CAMERA) openPreview();
    }

    @Override public void onTextureReady(SurfaceTexture ready) {
        if (isDestroyed()) return;
        camera.close();
        texture = ready;
        rendererFailed = false;
        openPreview();
    }

    @Override public void onFirstFrame(SurfaceTexture source) {
        if (resumed && texture == source && hasPermission()) statusPanel.setVisibility(View.GONE);
    }

    @Override public void onDepthFrame(Session source, boolean available) {
        if (!resumed || !arRunning || source != arSession) return;
        statusPanel.setVisibility(View.GONE);
        hasDepth = available;
        updateDepthStatus();
    }

    @Override public void onDepthError(Session source) {
        if (source != arSession) return;
        arFailed = true;
        closeArSession();
        updateDepthStatus();
        openPreview();
    }

    @Override public void onRendererError() {
        if (isDestroyed()) return;
        camera.close();
        closeArSession();
        texture = null;
        rendererFailed = true;
        openPreview();
    }

    @Override public void onGeometry(CameraFeed.Geometry geometry) {
        preview.setGeometry(geometry, displayDegrees());
    }

    @Override public void onError(int message) {
        preview.clearFrame();
        showStatus(message, R.string.action_retry, this::openPreview);
    }

    @Override public void onDisplayAdded(int displayId) {}

    @Override public void onDisplayRemoved(int displayId) {}

    @Override public void onDisplayChanged(int displayId) {
        preview.setDisplayRotation(displayDegrees());
    }

    private void openPreview() {
        if (!resumed || !preferences.getBoolean("privacy_notice_v1", false)
                || camera.isActive() || arRunning) return;
        if (rendererFailed) {
            showStatus(R.string.preview_renderer_failed, R.string.action_retry, this::recreate);
        } else if (!hasPermission()) {
            showStatus(R.string.camera_permission_message, R.string.action_allow_camera,
                    this::askForCamera);
        } else {
            showStatus(R.string.camera_opening, 0, null);
            if (!arFailed && arSession != null && startArPreview()) return;
            if (!arFailed && arAvailability == null) {
                checkArAvailability();
            } else if (!arFailed && arAvailability == ArCoreApk.Availability.SUPPORTED_INSTALLED
                    && startArPreview()) {
                return;
            } else {
                updateDepthStatus();
                if (texture != null) {
                    preview.clearFrame();
                    camera.open(texture);
                }
            }
        }
    }

    private void checkArAvailability() {
        if (arChecking) return;
        arChecking = true;
        int generation = ++arCheckGeneration;
        updateDepthStatus();
        try {
            ArCoreApk.getInstance().checkAvailabilityAsync(this, availability -> runOnUiThread(() -> {
                if (generation != arCheckGeneration || !resumed || isDestroyed()) return;
                arChecking = false;
                arAvailability = availability;
                openPreview();
            }));
        } catch (RuntimeException | LinkageError error) {
            Log.w("VisionCamera", "Could not check ARCore availability", error);
            arChecking = false;
            arFailed = true;
            openPreview();
        }
        root.postDelayed(() -> {
            if (generation != arCheckGeneration || !arChecking || !resumed) return;
            arCheckGeneration++;
            arChecking = false;
            arAvailability = ArCoreApk.Availability.UNKNOWN_TIMED_OUT;
            openPreview();
        }, 2500);
    }

    private boolean startArPreview() {
        try {
            if (arSession == null) {
                arSession = new Session(this);
                if (!arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    arFailed = true;
                    closeArSession();
                    return false;
                }
                Config config = arSession.getConfig();
                config.setDepthMode(Config.DepthMode.AUTOMATIC);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
                config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
                config.setUpdateMode(Config.UpdateMode.BLOCKING);
                arSession.configure(config);
            }
            arSession.resume();
            arRunning = true;
            preview.setArSession(arSession);
            updateDepthStatus();
            return true;
        } catch (UnavailableException | CameraNotAvailableException | RuntimeException
                | LinkageError error) {
            Log.w("VisionCamera", "Could not start ARCore camera", error);
            arFailed = true;
            closeArSession();
            return false;
        }
    }

    private void stopArPreview() {
        preview.setArSession(null);
        arRunning = false;
        hasDepth = false;
        arSession.pause();
    }

    private void closeArSession() {
        Session session = arSession;
        if (session == null) return;
        if (arRunning) stopArPreview();
        arSession = null;
        new Thread(session::close, "VisionArClose").start();
    }

    private boolean canInstallAr() {
        return !arFailed && (arAvailability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
                || arAvailability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD);
    }

    private void installAr() {
        if (!canInstallAr()) return;
        try {
            ArCoreApk.getInstance().requestInstall(this, true);
        } catch (UnavailableException | RuntimeException error) {
            Log.w("VisionCamera", "Could not install ARCore", error);
            arFailed = true;
            updateDepthStatus();
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void askForCamera() {
        if (hasPermission()) {
            openPreview();
        } else if (preferences.getBoolean("camera_asked", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(settings);
        } else {
            preferences.edit().putBoolean("camera_asked", true).apply();
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    private int displayDegrees() {
        Display display = preview.getDisplay();
        if (display == null) display = getWindowManager().getDefaultDisplay();
        switch (display.getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void createUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        preview = new PreviewView(this, this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xb8101713, 0x00101713, 0x00101713, 0xdb101713}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left + dp(16), top + dp(10), right + dp(16), bottom + dp(10));
            return insets;
        });

        LinearLayout header = createHeader();
        LinearLayout controls = createControls();
        overlay.addView(header, new FrameLayout.LayoutParams(-1, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        overlay.addView(controls, new FrameLayout.LayoutParams(-1, -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        overlay.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop,
                oldRight, oldBottom) -> {
            int width = Math.min(dp(490), right - left - view.getPaddingLeft()
                    - view.getPaddingRight());
            if (width > 0) {
                setWidth(header, width);
                setWidth(controls, width);
            }
        });

        statusPanel = vertical();
        statusPanel.setGravity(Gravity.CENTER);
        statusPanel.setPadding(dp(18), dp(14), dp(18), dp(14));
        statusPanel.setBackground(shape(PANEL, PANEL_EDGE, 22));
        statusText = text("", 15, TEXT, MEDIUM);
        statusText.setGravity(Gravity.CENTER);
        statusText.setMaxWidth(dp(240));
        statusPanel.addView(statusText);
        statusAction = button("");
        styleButton(statusAction, true, 22);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-2, -2);
        actionParams.topMargin = dp(10);
        statusPanel.addView(statusAction, actionParams);
        overlay.addView(statusPanel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        showStatus(R.string.camera_opening, 0, null);
        setContentView(root);
        overlay.requestApplyInsets();
    }

    private LinearLayout createHeader() {
        LinearLayout headerGroup = vertical();
        LinearLayout header = horizontal(Gravity.CENTER_VERTICAL);
        header.setPaddingRelative(dp(12), dp(5), dp(6), dp(5));
        header.setBackground(shape(PANEL, PANEL_EDGE, 26));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.app_icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView title = text(getString(R.string.app_name), 16, TEXT, MEDIUM);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMarginStart(dp(8));
        header.addView(title, titleParams);
        normalVision = addAcuityButton(header, 1.0, R.string.acuity_normal, Optics.NORMAL);
        sharpVision = addAcuityButton(header, 1.2, R.string.acuity_sharp, Optics.SHARP);
        headerGroup.addView(header, new LinearLayout.LayoutParams(-1, -2));

        Button privacy = button(getString(R.string.privacy_title));
        styleButton(privacy, false, 22);
        privacy.setPaddingRelative(dp(12), dp(5), dp(12), dp(5));
        privacy.setOnClickListener(view -> showPrivacyPolicy());
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(-2, -2);
        privacyParams.gravity = Gravity.END;
        privacyParams.topMargin = dp(4);
        headerGroup.addView(privacy, privacyParams);
        return headerGroup;
    }

    private void showPrivacyPolicy() {
        showPrivacyPolicy(false);
    }

    private void showPrivacyPolicy(boolean firstRun) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.privacy_title)
                .setMessage(getString(R.string.privacy_policy, getString(R.string.privacy_arcore))
                        + "\n\n" + getString(R.string.privacy_developer_contact))
                .setPositiveButton(android.R.string.ok, (notice, which) -> {
                    if (firstRun) {
                        preferences.edit().putBoolean("privacy_notice_v1", true).apply();
                        if (!hasPermission()) askForCamera();
                        else openPreview();
                    }
                })
                .setNeutralButton(R.string.privacy_google, null)
                .setCancelable(!firstRun)
                .create();
        dialog.show();
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) Linkify.addLinks(message, Linkify.EMAIL_ADDRESSES);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            Intent googlePrivacy = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://policies.google.com/privacy"));
            try {
                startActivity(googlePrivacy);
            } catch (ActivityNotFoundException unavailable) {
                Log.w("VisionPrivacy", "No app can open the privacy link");
            }
        });
    }

    private Button addAcuityButton(LinearLayout header, double fraction, int description, int mode) {
        String label = percentages.format(fraction);
        Button acuityButton = button(label);
        acuityButton.setContentDescription(getString(description, label));
        acuityButton.setMinWidth(dp(59));
        acuityButton.setOnClickListener(view -> {
            acuity = acuity == mode ? USE_FILTER : mode;
            renderSelection();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(4));
        header.addView(acuityButton, params);
        return acuityButton;
    }

    private LinearLayout createControls() {
        LinearLayout controls = vertical();
        controls.setPadding(dp(12), dp(10), dp(12), dp(12));
        controls.setBackground(shape(PANEL, PANEL_EDGE, 26));

        String[] names = {getString(R.string.filter_myopia), getString(R.string.filter_astigmatism),
                getString(R.string.filter_haze)};
        LinearLayout modes = horizontal(Gravity.CENTER_VERTICAL);
        for (int index = 0; index < names.length; index++) {
            int mode = index + 1;
            Button chip = button(names[index]);
            chip.setTextSize(12);
            chip.setOnClickListener(view -> {
                int bit = Optics.filterBit(mode);
                if (acuity == USE_FILTER) {
                    enabledFilters ^= bit;
                } else {
                    enabledFilters |= bit;
                }
                acuity = USE_FILTER;
                preferences.edit().putInt("filters", enabledFilters).apply();
                renderSelection();
            });
            filters[index] = chip;
            addChip(modes, chip, index, index == 2 ? 0.78f : 1.12f);
        }
        controls.addView(modes, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout options = vertical();
        filterOptions = options;
        controls.addView(options, new LinearLayout.LayoutParams(-1, -2));
        for (int index = 0; index < names.length; index++) {
            addStrengthControl(options, index + 1, names[index]);
        }

        LinearLayout distancesLayout = vertical();
        distancesLayout.setPadding(0, dp(6), 0, 0);
        depthStatus = text("", 12, MUTED, MEDIUM);
        depthStatus.setPaddingRelative(dp(3), 0, 0, dp(5));
        distancesLayout.addView(depthStatus);

        LinearLayout distanceChips = horizontal(Gravity.CENTER_VERTICAL);
        String[] distanceNames = {getString(R.string.distance_auto), getString(R.string.distance_far),
                getString(R.string.distance_cm, integers.format(30)),
                getString(R.string.distance_cm, integers.format(15))};
        for (int index = 0; index < distanceNames.length; index++) {
            int distanceMode = index + Optics.AUTOMATIC;
            Button chip = button(distanceNames[index]);
            chip.setOnClickListener(view -> {
                distance = distanceMode;
                preferences.edit().putInt("depth_distance", distance).apply();
                renderSelection();
                if (distance == Optics.AUTOMATIC) installAr();
            });
            distances[index] = chip;
            addChip(distanceChips, chip, index, 1f);
        }
        distancesLayout.addView(distanceChips, new LinearLayout.LayoutParams(-1, -2));
        distanceBlock = distancesLayout;
        options.addView(distancesLayout, new LinearLayout.LayoutParams(-1, -2));
        return controls;
    }

    private void addStrengthControl(LinearLayout options, int mode, String name) {
        LinearLayout row = vertical();
        row.setPadding(0, dp(7), 0, 0);
        LinearLayout label = horizontal(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(3), 0, dp(3), 0);
        TextView title = text(name, 13, MUTED, MEDIUM);
        label.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text("", 19, ACCENT, MEDIUM);
        value.setFontFeatureSettings("tnum");
        label.addView(value);
        row.addView(label, new LinearLayout.LayoutParams(-1, -2));
        readouts[mode] = value;

        SeekBar slider = new SeekBar(this);
        slider.setPadding(dp(11), 0, dp(11), 0);
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(0xff657269));
        slider.setKeyProgressIncrement(1);
        slider.setSplitTrack(false);
        slider.setContentDescription(name);
        slider.setMax(maxStrength(mode) - 1);
        slider.setProgress(values[mode] - 1);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                values[mode] = progress + 1;
                updateReadout(mode);
                if (mode == Optics.MYOPIA) updateDepthStatus();
                applyEffect();
                preferences.edit().putInt("strength_" + mode, values[mode]).apply();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        strengths[mode] = slider;
        row.addView(slider, new LinearLayout.LayoutParams(-1, dp(46)));
        sliderRows[mode] = row;
        options.addView(row, new LinearLayout.LayoutParams(-1, -2));
        updateReadout(mode);
    }

    private void renderSelection() {
        boolean filtered = acuity == USE_FILTER;
        styleButton(normalVision, acuity == Optics.NORMAL || (filtered && enabledFilters == 0), 22);
        styleButton(sharpVision, acuity == Optics.SHARP, 22);
        filterOptions.setVisibility(filtered && enabledFilters != 0 ? View.VISIBLE : View.GONE);
        for (int mode = Optics.MYOPIA; mode <= Optics.HAZE; mode++) {
            boolean enabled = isEnabled(mode);
            styleButton(filters[mode - 1], filtered && enabled, 21);
            sliderRows[mode].setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        for (int index = 0; index < distances.length; index++) {
            styleButton(distances[index], distance == index + Optics.AUTOMATIC, 21);
        }
        distanceBlock.setVisibility(isEnabled(Optics.MYOPIA) ? View.VISIBLE : View.GONE);
        updateDepthStatus();
        applyEffect();
    }

    private boolean isEnabled(int mode) {
        return (enabledFilters & Optics.filterBit(mode)) != 0;
    }

    private void updateReadout(int mode) {
        int value = values[mode];
        boolean myopia = mode == Optics.MYOPIA;
        String formatted = myopia
                ? getString(R.string.diopter_value, integers.format(-value).replace('-', '−'))
                : percentages.format(value / 100.0);
        readouts[mode].setText(formatted);
        if (Build.VERSION.SDK_INT >= 30) {
            strengths[mode].setStateDescription(formatted);
        }
    }

    private void updateDepthStatus() {
        if (distance != Optics.AUTOMATIC) {
            depthStatus.setText(getString(R.string.far_point,
                    integers.format(Optics.farPointCentimeters(values[Optics.MYOPIA]))));
        } else if (arRunning && hasDepth) {
            depthStatus.setText(R.string.depth_active);
        } else if (arRunning) {
            depthStatus.setText(R.string.depth_move);
        } else if (canInstallAr()) {
            depthStatus.setText(getString(R.string.depth_install, getString(R.string.distance_auto)));
        } else if (!arFailed && arAvailability == null) {
            depthStatus.setText(R.string.depth_starting);
        } else {
            depthStatus.setText(R.string.depth_unavailable);
        }
    }

    private void applyEffect() {
        boolean filtered = acuity == USE_FILTER;
        preview.setEffect(filtered && isEnabled(Optics.MYOPIA) ? values[Optics.MYOPIA] : 0,
                filtered && isEnabled(Optics.ASTIGMATISM) ? values[Optics.ASTIGMATISM] : 0,
                filtered && isEnabled(Optics.HAZE) ? values[Optics.HAZE] : 0,
                acuity == Optics.SHARP, distance);
    }

    private void showStatus(int message, int actionLabel, Runnable action) {
        statusPanel.setVisibility(View.VISIBLE);
        statusText.setText(message);
        statusAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) {
            statusAction.setText(actionLabel);
            statusAction.setOnClickListener(view -> action.run());
        }
    }

    private void addChip(LinearLayout parent, Button chip, int index, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weight);
        if (index > 0) params.setMarginStart(dp(4));
        parent.addView(chip, params);
    }

    private void styleButton(Button button, boolean selected, int corner) {
        button.setSelected(selected);
        button.setTextColor(selected ? INK : TEXT);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(selected ? 0x26101e0b : 0x40ffffff),
                shape(selected ? ACCENT : BUTTON_SURFACE, Color.TRANSPARENT, corner), null));
    }

    private GradientDrawable shape(int color, int stroke, int corner) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(corner));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTypeface(MEDIUM);
        button.setAllCaps(false);
        button.setLetterSpacing(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(5), dp(5), dp(5), dp(5));
        button.setStateListAnimator(null);
        return button;
    }

    private TextView text(String label, int size, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(typeface);
        return view;
    }

    private LinearLayout horizontal(int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity);
        return row;
    }

    private LinearLayout vertical() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        return group;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static void setWidth(View view, int width) {
        if (view.getLayoutParams().width != width) {
            view.getLayoutParams().width = width;
            view.requestLayout();
        }
    }

    private static int maxStrength(int mode) {
        return mode == Optics.MYOPIA ? 12 : 100;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

}
