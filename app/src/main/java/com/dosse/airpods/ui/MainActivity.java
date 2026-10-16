package com.dosse.airpods.ui;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.TypedValue;

import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

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

import java.util.Objects;

public class MainActivity extends AppCompatActivity implements PodsStatusRepository.Listener {

    private final PodsStatusRepository repository = PodsStatusRepository.getInstance();

    private TextView statusTitle, statusModel, statusSubtitle;
    private ImageView leftImg, rightImg, caseImg;
    private TextView leftText, rightText, caseText, leftInEar, rightInEar;
    private View leftPod, rightPod;
    private View noiseTitle;
    private RadioGroup noiseGroup;

    /** Guards the radio listener while we update it from the repository. */
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
        statusTitle = findViewById(R.id.statusTitle);
        statusModel = findViewById(R.id.statusModel);
        statusSubtitle = findViewById(R.id.statusSubtitle);

        leftPod = findViewById(R.id.leftPod);
        rightPod = findViewById(R.id.rightPod);
        leftImg = findViewById(R.id.podLeftImg);
        rightImg = findViewById(R.id.podRightImg);
        caseImg = findViewById(R.id.podCaseImg);
        leftText = findViewById(R.id.podLeftText);
        rightText = findViewById(R.id.podRightText);
        caseText = findViewById(R.id.podCaseText);
        leftInEar = findViewById(R.id.podLeftInEar);
        rightInEar = findViewById(R.id.podRightInEar);

        noiseTitle = findViewById(R.id.noiseTitle);
        noiseGroup = findViewById(R.id.noiseGroup);
    }

    private void setupNoiseControls () {
        noiseGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (updatingNoise)
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
            statusTitle.setText(R.string.notConnected);
            statusModel.setText("");
            statusSubtitle.setText("");
            leftPod.setVisibility(View.INVISIBLE);
            rightPod.setVisibility(View.INVISIBLE);
            leftInEar.setVisibility(View.GONE);
            rightInEar.setVisibility(View.GONE);
            return;
        }

        statusTitle.setText(R.string.connected);

        IPods airpods = status.getAirpods();

        if (airpods instanceof RegularPods) {
            RegularPods regular = (RegularPods)airpods;

            leftPod.setVisibility(View.VISIBLE);
            rightPod.setVisibility(View.VISIBLE);

            leftImg.setImageResource(regular.getLeftDrawable());
            rightImg.setImageResource(regular.getRightDrawable());
            caseImg.setImageResource(regular.getCaseDrawable());

            leftText.setText(regular.getParsedStatus(RegularPods.LEFT));
            rightText.setText(regular.getParsedStatus(RegularPods.RIGHT));
            caseText.setText(regular.getParsedStatus(RegularPods.CASE));

            leftInEar.setVisibility(regular.getPod(RegularPods.LEFT).isInEar() ? View.VISIBLE : View.GONE);
            rightInEar.setVisibility(regular.getPod(RegularPods.RIGHT).isInEar() ? View.VISIBLE : View.GONE);
        } else if (airpods instanceof SinglePods) {
            SinglePods single = (SinglePods)airpods;

            leftPod.setVisibility(View.VISIBLE);
            rightPod.setVisibility(View.INVISIBLE);
            leftInEar.setVisibility(View.GONE);
            rightInEar.setVisibility(View.GONE);

            leftImg.setImageResource(single.getDrawable());
            caseImg.setImageResource(single.getDrawable());
            leftText.setText(single.getParsedStatus());
            caseText.setText(single.getParsedStatus());
        }

        statusModel.setText(airpods.getModel());
        statusSubtitle.setText(R.string.updatedJustNow);
    }

    private void updateNoiseControls (boolean aacpConnected, int noiseControlMode) {
        if (!aacpConnected) {
            noiseTitle.setVisibility(View.GONE);
            noiseGroup.setVisibility(View.GONE);
            return;
        }

        noiseTitle.setVisibility(View.VISIBLE);
        noiseGroup.setVisibility(View.VISIBLE);

        int checkedId = R.id.noiseOff;
        if (noiseControlMode == AacpConstants.NOISE_ANC) checkedId = R.id.noiseAnc;
        else if (noiseControlMode == AacpConstants.NOISE_TRANSPARENCY) checkedId = R.id.noiseTransparency;
        else if (noiseControlMode == AacpConstants.NOISE_ADAPTIVE) checkedId = R.id.noiseAdaptive;

        if (noiseGroup.getCheckedRadioButtonId() != checkedId) {
            updatingNoise = true;
            noiseGroup.check(checkedId);
            updatingNoise = false;
        }
    }

    // ------------------------------------------------------------------ menu

    @Override
    public boolean onCreateOptionsMenu (Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        // The gear icon is white; tint it to stay visible on the
        // theme-colored action bar in both light and dark mode.
        MenuItem item = menu.findItem(R.id.menu_ab_settings);
        if (item != null && item.getIcon() != null) {
            TypedValue tv = new TypedValue();
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true);
            item.getIcon().mutate().setColorFilter(new PorterDuffColorFilter(ContextCompat.getColor(this, tv.resourceId), PorterDuff.Mode.SRC_IN));
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
