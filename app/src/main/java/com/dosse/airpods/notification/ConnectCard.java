package com.dosse.airpods.notification;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.Pod;
import com.dosse.airpods.pods.PodsStatus;
import com.dosse.airpods.pods.PodsStatusRepository;
import com.dosse.airpods.pods.models.AirPods1;
import com.dosse.airpods.pods.models.AirPods2;
import com.dosse.airpods.pods.models.AirPods3;
import com.dosse.airpods.pods.models.AirPodsMax;
import com.dosse.airpods.pods.models.AirPodsPro;
import com.dosse.airpods.pods.models.AirPodsPro2;
import com.dosse.airpods.pods.models.AirPodsPro3;
import com.dosse.airpods.pods.models.BeatsFlex;
import com.dosse.airpods.pods.models.BeatsSolo3;
import com.dosse.airpods.pods.models.BeatsStudio3;
import com.dosse.airpods.pods.models.BeatsX;
import com.dosse.airpods.pods.models.IPods;
import com.dosse.airpods.pods.models.Powerbeats3;
import com.dosse.airpods.pods.models.PowerbeatsPro;
import com.dosse.airpods.pods.models.RegularPods;
import com.dosse.airpods.pods.models.SinglePods;
import com.dosse.airpods.ui.MainActivity;
import com.dosse.airpods.utils.Logger;
import com.dosse.airpods.utils.SharedPreferencesUtils;
import com.dosse.airpods.utils.ThemeUtils;

import java.util.HashMap;
import java.util.Map;

public class ConnectCard implements PodsStatusRepository.Listener {

    private static final long AUTO_HIDE_MS = 6000;

    private final Context context;
    private final PodsStatusRepository repository;
    private WindowManager windowManager;
    private View cardView;
    private WindowManager.LayoutParams params;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::hide;

    private final Map<Class<? extends IPods>, String> modelNames = new HashMap<>();

    private boolean wasConnected = false;

    public ConnectCard (Context context) {
        this.context = context.getApplicationContext();
        this.repository = PodsStatusRepository.getInstance();
        initModelNames();
    }

    private void initModelNames () {
        modelNames.put(AirPods1.class, "AirPods 1");
        modelNames.put(AirPods2.class, "AirPods 2");
        modelNames.put(AirPods3.class, "AirPods 3");
        modelNames.put(AirPodsPro.class, "AirPods Pro");
        modelNames.put(AirPodsPro2.class, "AirPods Pro 2");
        modelNames.put(AirPodsPro3.class, "AirPods Pro 3");
        modelNames.put(AirPodsMax.class, "AirPods Max");
        modelNames.put(BeatsX.class, "Beats X");
        modelNames.put(BeatsFlex.class, "Beats Flex");
        modelNames.put(BeatsSolo3.class, "Beats Solo 3");
        modelNames.put(BeatsStudio3.class, "Beats Studio 3");
        modelNames.put(Powerbeats3.class, "Powerbeats 3");
        modelNames.put(PowerbeatsPro.class, "Powerbeats Pro");
    }

    @Override
    public void onConnectionChanged (boolean connected) {
        if (!connected) {
            hide();
        }
    }

    @Override
    public void onStatusChanged (PodsStatus status) {
        if (status == null || status.getAirpods() == null)
            return;
        if (!SharedPreferencesUtils.isShowingConnectCard(context))
            return;

        boolean nowConnected = repository.isMaybeConnected() && !status.getAirpods().isDisconnected();
        if (nowConnected && !wasConnected) {
            wasConnected = true;
            showCard(status);
        } else if (wasConnected) {
            updateCard(status);
            scheduleHide();
        }

        if (!nowConnected) {
            wasConnected = false;
        }
    }

    private boolean canDrawOverlays () {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(context);
        }
        return true;
    }

    private void showCard (PodsStatus status) {
        if (!canDrawOverlays()) {
            Logger.debug("CONNECT CARD: overlay permission not granted");
            return;
        }

        hide();

        windowManager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return;
        }

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(type,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = (int)(24 * context.getResources().getDisplayMetrics().density);

        Context themed = inflateContext();
        cardView = LayoutInflater.from(themed).inflate(R.layout.connect_card, null, false);
        applyTheme(themed, cardView);
        updateCardView(status);
        cardView.setOnClickListener(v -> {
            try {
                Intent i = new Intent(context, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
                hide();
            } catch (Throwable ignored) {
            }
        });

        try {
            windowManager.addView(cardView, params);
        } catch (Exception e) {
            Logger.error(e);
            return;
        }

        scheduleHide();
    }

    private void applyTheme (Context themed, View v) {
        v.setBackground(ContextCompat.getDrawable(themed, R.drawable.connect_card_bg));
    }

    /**
     * A context that can inflate connect_card.xml.
     * <p>
     * The card is added straight to the WindowManager, so it is inflated from
     * the application context. An application context carries the platform
     * default theme rather than the one declared in the manifest, so the
     * ?attr/ references in the layout (colorPrimary, colorOnSurface,
     * textAppearance*, ...) fail to resolve, and the card used to disappear as
     * soon as the AirPods connected, with an UnsupportedOperationException
     * from ImageView's tint attribute. Wrapping the application context in the
     * app theme fixes the resolution, and pinning the night mode keeps the
     * card in step with the in-app theme, which Android 7 cannot do on its
     * own because it has no system dark mode.
     */
    private Context inflateContext () {
        ContextThemeWrapper wrapper = new ContextThemeWrapper(context, R.style.AppTheme);

        Configuration cfg = new Configuration(context.getResources().getConfiguration());
        cfg.uiMode = ThemeUtils.isDark(context)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        wrapper.applyOverrideConfiguration(cfg);

        return wrapper;
    }

    private void updateCard (PodsStatus status) {
        if (cardView == null) {
            return;
        }
        updateCardView(status);
    }

    private void updateCardView (PodsStatus status) {
        IPods pods = status.getAirpods();
        if (pods == null) {
            return;
        }

        TextView modelName = cardView.findViewById(R.id.cardModelName);
        modelName.setText(modelNames.getOrDefault(pods.getClass(), pods.getModel()));

        View leftInEar = cardView.findViewById(R.id.cardLeftInEar);
        View rightInEar = cardView.findViewById(R.id.cardRightInEar);

        if (pods instanceof RegularPods) {
            RegularPods r = (RegularPods)pods;
            setupPod(r.getPod(RegularPods.LEFT), (ImageView)cardView.findViewById(R.id.cardLeftImg), (TextView)cardView.findViewById(R.id.cardLeftText), leftInEar);
            setupPod(r.getPod(RegularPods.RIGHT), (ImageView)cardView.findViewById(R.id.cardRightImg), (TextView)cardView.findViewById(R.id.cardRightText), rightInEar);
            setupPod(r.getPod(RegularPods.CASE), (ImageView)cardView.findViewById(R.id.cardCaseImg), (TextView)cardView.findViewById(R.id.cardCaseText), null);
        } else if (pods instanceof SinglePods) {
            setupPod(((SinglePods)pods).getPod(), (ImageView)cardView.findViewById(R.id.cardLeftImg), (TextView)cardView.findViewById(R.id.cardLeftText), leftInEar);
            // Right side hidden for single pods
            cardView.findViewById(R.id.cardRightImg).setVisibility(View.INVISIBLE);
            cardView.findViewById(R.id.cardRightText).setVisibility(View.INVISIBLE);
            cardView.findViewById(R.id.cardRightInEar).setVisibility(View.GONE);
            cardView.findViewById(R.id.cardCaseSpacer).setVisibility(View.GONE);
            cardView.findViewById(R.id.cardCaseContainer).setVisibility(View.GONE);
            return;
        }

        // Reset single-pod hiding
        cardView.findViewById(R.id.cardRightImg).setVisibility(View.VISIBLE);
        cardView.findViewById(R.id.cardRightText).setVisibility(View.VISIBLE);
        cardView.findViewById(R.id.cardRightInEar).setVisibility(View.VISIBLE);
        cardView.findViewById(R.id.cardCaseSpacer).setVisibility(View.VISIBLE);
        cardView.findViewById(R.id.cardCaseContainer).setVisibility(View.VISIBLE);
    }

    private void setupPod (Pod pod, ImageView img, TextView text, View inEarView) {
        if (pod == null) {
            img.setVisibility(View.INVISIBLE);
            text.setVisibility(View.INVISIBLE);
            if (inEarView != null) inEarView.setVisibility(View.GONE);
            return;
        }

        img.setVisibility(View.VISIBLE);
        text.setVisibility(View.VISIBLE);

        if (inEarView != null) {
            inEarView.setVisibility(pod.isInEar() ? View.VISIBLE : View.GONE);
        }

        img.setImageResource(pod.isConnected() ? R.drawable.pod : R.drawable.pod_disconnected);
        text.setText(pod.isConnected() ? pod.parseStatus() : "");
    }

    private void scheduleHide () {
        mainHandler.removeCallbacks(hideRunnable);
        mainHandler.postDelayed(hideRunnable, AUTO_HIDE_MS);
    }

    private void hide () {
        mainHandler.removeCallbacks(hideRunnable);
        if (cardView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(cardView);
            } catch (Exception ignored) {
            }
        }
        cardView = null;
        windowManager = null;
        wasConnected = false;
    }

}
