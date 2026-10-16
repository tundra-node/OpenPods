package com.dosse.airpods.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.dosse.airpods.R;
import com.dosse.airpods.aacp.AacpConstants;
import com.dosse.airpods.pods.Pod;
import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.pods.PodsStatus;
import com.dosse.airpods.pods.PodsStatusRepository;
import com.dosse.airpods.pods.models.IPods;
import com.dosse.airpods.pods.models.RegularPods;
import com.dosse.airpods.pods.models.SinglePods;
import com.dosse.airpods.receivers.StartupReceiver;
import com.dosse.airpods.utils.MIUIWarning;
import com.dosse.airpods.utils.PermissionUtils;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Objects;

public class MainActivity extends AppCompatActivity implements PodsStatusRepository.Listener {

    private final PodsStatusRepository repository = PodsStatusRepository.getInstance();

    private TextView statusPill, statusTitle, statusSubtitle;
    private View podsRow, emptyState;
    private ImageView leftImg, rightImg, caseImg;
    private TextView leftText, rightText, caseText, leftInEar, rightInEar;
    private LinearProgressIndicator leftBar, rightBar, caseBar;
    private View leftPod, rightPod, casePod;
    private View noiseTitle, noiseUnavailable;
    private MaterialButtonToggleGroup noiseGroup;

    /** Guards the toggle listener while we update it from the repository. */
    private boolean updatingNoise = false;

    @Override
    protected void onCreate (Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check if Bluetooth LE is available on this device. If not, show an error
        BluetoothAdapter btAdapter = ((BluetoothManager)Objects.requireNonNull(getSystemService(Context.BLUETOOTH_SERVICE))).getAdapter();
        if (btAdapter == null || (btAdapter.isEnabled() && btAdapter.getBluetoothLeScanner() == null) || (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))) {
            startActivity(new Intent(MainActivity.this, NoBTActivity.class));
            finish();
            return;
        }

        if (!PermissionUtils.checkAllPermissions(this)) {
            startActivity(new Intent(MainActivity.this, IntroActivity.class));
            finish();
            return;
        }

        setSupportActionBar((Toolbar)findViewById(R.id.toolbar));

        bindViews();
        setupNoiseControls();

        StartupReceiver.startPodsService(getApplicationContext());
        //Warn MIUI users that their rom has known issues
        MIUIWarning.show(this);
    }

    @Override
    protected void onResume () {
        super.onResume();
        repository.addListener(this);
        updateDashboard(repository.getStatus());
        updateNoiseControls(repository.isAacpConnected(), repository.getNoiseControlMode());
    }

    @Override
    protected void onPause () {
        super.onPause();
        repository.removeListener(this);
    }

    private void bindViews () {
        statusPill = findViewById(R.id.statusPill);
        statusTitle = findViewById(R.id.statusTitle);
        statusSubtitle = findViewById(R.id.statusSubtitle);

        podsRow = findViewById(R.id.podsRow);
        emptyState = findViewById(R.id.emptyState);

        leftPod = findViewById(R.id.leftPod);
        rightPod = findViewById(R.id.rightPod);
        casePod = findViewById(R.id.casePod);
        leftImg = findViewById(R.id.podLeftImg);
        rightImg = findViewById(R.id.podRightImg);
        caseImg = findViewById(R.id.podCaseImg);
        leftText = findViewById(R.id.podLeftText);
        rightText = findViewById(R.id.podRightText);
        caseText = findViewById(R.id.podCaseText);
        leftInEar = findViewById(R.id.podLeftInEar);
        rightInEar = findViewById(R.id.podRightInEar);
        leftBar = findViewById(R.id.podLeftBar);
        rightBar = findViewById(R.id.podRightBar);
        caseBar = findViewById(R.id.podCaseBar);

        noiseTitle = findViewById(R.id.noiseTitle);
        noiseGroup = findViewById(R.id.noiseGroup);
        noiseUnavailable = findViewById(R.id.noiseUnavailable);
    }

    private void setupNoiseControls () {
        noiseGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (updatingNoise || !isChecked)
                return;

            int mode = AacpConstants.NOISE_OFF;
            if (checkedId == R.id.noiseAnc) mode = AacpConstants.NOISE_ANC;
            else if (checkedId == R.id.noiseTransparency) mode = AacpConstants.NOISE_TRANSPARENCY;
            else if (checkedId == R.id.noiseAdaptive) mode = AacpConstants.NOISE_ADAPTIVE;

            Intent intent = new Intent(this, PodsService.class)
                    .setAction(PodsService.ACTION_SET_NOISE_CONTROL)
                    .putExtra(PodsService.EXTRA_NOISE_MODE, mode);
            startService(intent);
        });
    }

    // ------------------------------------------------------- repository events

    @Override
    public void onStatusChanged (PodsStatus status) {
        runOnUiThread(() -> updateDashboard(status));
    }

    @Override
    public void onConnectionChanged (boolean connected) {
        runOnUiThread(() -> updateDashboard(repository.getStatus()));
    }

    @Override
    public void onAacpStateChanged (boolean aacpConnected, int noiseControlMode) {
        runOnUiThread(() -> updateNoiseControls(aacpConnected, noiseControlMode));
    }

    // ------------------------------------------------------------------ views

    private void updateDashboard (PodsStatus status) {
        if (status == null || status == PodsStatus.DISCONNECTED || status.getAirpods() == null) {
            showDisconnected();
            return;
        }

        IPods airpods = status.getAirpods();

        setPill(true);

        statusTitle.setVisibility(View.VISIBLE);
        statusSubtitle.setVisibility(View.VISIBLE);

        podsRow.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);

        if (airpods instanceof RegularPods) {
            RegularPods regular = (RegularPods)airpods;

            leftPod.setVisibility(View.VISIBLE);
            rightPod.setVisibility(View.VISIBLE);
            casePod.setVisibility(View.VISIBLE);

            leftImg.setImageResource(regular.getLeftDrawable());
            rightImg.setImageResource(regular.getRightDrawable());
            caseImg.setImageResource(regular.getCaseDrawable());

            bindPodTile(regular.getPod(RegularPods.LEFT), leftText, leftBar, leftInEar);
            bindPodTile(regular.getPod(RegularPods.RIGHT), rightText, rightBar, rightInEar);
            bindPodTile(regular.getPod(RegularPods.CASE), caseText, caseBar, null);
        } else if (airpods instanceof SinglePods) {
            SinglePods single = (SinglePods)airpods;

            // A single-unit model (AirPods Max, Beats) fills the whole row
            leftPod.setVisibility(View.VISIBLE);
            rightPod.setVisibility(View.GONE);
            casePod.setVisibility(View.GONE);

            leftImg.setImageResource(single.getDrawable());
            bindPodTile(single.getPod(), leftText, leftBar, leftInEar);
        }

        statusTitle.setText(airpods.getModel());
        statusSubtitle.setText(R.string.updatedJustNow);
    }

    private void showDisconnected () {
        setPill(false);

        statusTitle.setVisibility(View.GONE);
        statusSubtitle.setVisibility(View.GONE);

        podsRow.setVisibility(View.GONE);
        emptyState.setVisibility(View.VISIBLE);
    }

    /** Fills one battery tile (percent, bar colour, in-ear badge). */
    private void bindPodTile (Pod pod, TextView text, LinearProgressIndicator bar, TextView inEar) {
        int percent = pod.getBatteryPercent();

        text.setText(pod.isDisconnected() ? "\u2014" : pod.parseStatus());

        if (percent < 0) {
            bar.setVisibility(View.INVISIBLE);
        } else {
            bar.setVisibility(View.VISIBLE);
            bar.setProgress(percent);
            bar.setIndicatorColor(ContextCompat.getColor(this,
                    pod.isLowBattery() ? R.color.md_error : R.color.md_primary));
        }

        if (inEar != null)
            inEar.setVisibility(pod.isInEar() ? View.VISIBLE : View.GONE);
    }

    private void setPill (boolean connected) {
        int bgRes, drawableRes, colorRes, textRes;
        if (connected) {
            bgRes = R.drawable.bg_pill_connected;
            drawableRes = R.drawable.ic_bluetooth;
            colorRes = R.color.md_on_success_container;
            textRes = R.string.connected;
        } else {
            bgRes = R.drawable.bg_pill_disconnected;
            drawableRes = R.drawable.ic_bluetooth_disabled;
            colorRes = R.color.md_on_neutral_container;
            textRes = R.string.notConnected;
        }

        int color = ContextCompat.getColor(this, colorRes);

        statusPill.setBackgroundResource(bgRes);
        statusPill.setText(textRes);
        statusPill.setTextColor(color);

        Drawable icon = ContextCompat.getDrawable(this, drawableRes);
        if (icon != null) {
            icon = DrawableCompat.wrap(icon.mutate());
            DrawableCompat.setTint(icon, color);
            int size = (int)(getResources().getDisplayMetrics().density * 14);
            icon.setBounds(0, 0, size, size);
            statusPill.setCompoundDrawablesRelative(icon, null, null, null);
        }
    }

    private void updateNoiseControls (boolean aacpConnected, int noiseControlMode) {
        noiseTitle.setVisibility(View.VISIBLE);

        if (!aacpConnected) {
            noiseGroup.setVisibility(View.GONE);
            noiseUnavailable.setVisibility(View.VISIBLE);
            return;
        }

        noiseGroup.setVisibility(View.VISIBLE);
        noiseUnavailable.setVisibility(View.GONE);

        int checkedId = R.id.noiseOff;
        if (noiseControlMode == AacpConstants.NOISE_ANC) checkedId = R.id.noiseAnc;
        else if (noiseControlMode == AacpConstants.NOISE_TRANSPARENCY) checkedId = R.id.noiseTransparency;
        else if (noiseControlMode == AacpConstants.NOISE_ADAPTIVE) checkedId = R.id.noiseAdaptive;

        if (noiseGroup.getCheckedButtonId() != checkedId) {
            updatingNoise = true;
            noiseGroup.check(checkedId);
            updatingNoise = false;
        }
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu (Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        // Tint the settings icon so it stays visible against the toolbar in
        // both light and dark mode.
        MenuItem item = menu.findItem(R.id.menu_ab_settings);
        if (item != null && item.getIcon() != null) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true);
            Drawable icon = DrawableCompat.wrap(item.getIcon().mutate());
            DrawableCompat.setTint(icon, ContextCompat.getColor(this, tv.resourceId));
            item.setIcon(icon);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected (@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_ab_settings) {
            startActivity(new Intent(this, SettingsActivity.class)); // Settings icon clicked
            return true;
        }
        return false;
    }

}
