package com.dosse.airpods.notification;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.dosse.airpods.R;
import com.dosse.airpods.pods.PodsStatus;
import com.dosse.airpods.pods.models.IPods;
import com.dosse.airpods.pods.models.RegularPods;
import com.dosse.airpods.pods.models.SinglePods;
import com.dosse.airpods.utils.ThemeUtils;

public class NotificationBuilder {
    public static final String TAG = "AirPods";
    public static final long TIMEOUT_CONNECTED = 30000;
    public static final int NOTIFICATION_ID = 1;

    private final RemoteViews[] notificationArr;
    private final NotificationCompat.Builder mBuilder;
    private final Context appContext;

    public NotificationBuilder (Context context) {
        appContext = context.getApplicationContext();
        notificationArr = new RemoteViews[] {new RemoteViews(appContext.getPackageName(), R.layout.status_big), new RemoteViews(appContext.getPackageName(), R.layout.status_small)};

        mBuilder = new NotificationCompat.Builder(appContext, TAG);
        mBuilder.setShowWhen(false);
        mBuilder.setOngoing(true);
        mBuilder.setSmallIcon(R.mipmap.notification_icon);
        mBuilder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        mBuilder.setCustomContentView(notificationArr[1]);
        mBuilder.setCustomBigContentView(notificationArr[0]);
    }

    public Notification build (PodsStatus status) {
        /*
         * RemoteViews are inflated by System UI with the system's
         * configuration, so resource qualifiers alone can't follow the
         * in-app theme on Android 7 (which has no system dark mode).
         * Colors are therefore resolved here and applied explicitly.
         *
         * On API >= 29 the system styles the notification background, so
         * only the in-app icon tint follows the system there.
         */
        boolean dark = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ThemeUtils.isSystemDark(appContext)
                : ThemeUtils.isDark(appContext);
        Context themed = ThemeUtils.nightConfigured(appContext, dark);

        int bgColor = ContextCompat.getColor(themed, R.color.bgColorPrimary);
        int textColor = ContextCompat.getColor(themed, R.color.textColorPrimary);
        int imageTint = ContextCompat.getColor(themed, R.color.imageTintPrimary);

        for (RemoteViews notification : notificationArr) {
            notification.setInt(R.id.notiRoot, "setBackgroundColor", bgColor);
            notification.setTextColor(R.id.leftPodText, textColor);
            notification.setTextColor(R.id.rightPodText, textColor);
            notification.setTextColor(R.id.podCaseText, textColor);

            // The pod images are dark silhouettes; tint them light in dark mode
            notification.setInt(R.id.leftPodImg, "setColorFilter", imageTint);
            notification.setInt(R.id.rightPodImg, "setColorFilter", imageTint);
            notification.setInt(R.id.podCaseImg, "setColorFilter", imageTint);
            notification.setInt(R.id.leftInEarImg, "setColorFilter", imageTint);
            notification.setInt(R.id.rightInEarImg, "setColorFilter", imageTint);
        }

        IPods airpods = status.getAirpods();
        boolean single = airpods.isSingle();

        for (RemoteViews notification : notificationArr) {
            if (!single) {
                notification.setImageViewResource(R.id.leftPodImg, ((RegularPods)airpods).getLeftDrawable());
                notification.setImageViewResource(R.id.rightPodImg, ((RegularPods)airpods).getRightDrawable());
                notification.setImageViewResource(R.id.podCaseImg, ((RegularPods)airpods).getCaseDrawable());
            } else {
                notification.setImageViewResource(R.id.podCaseImg, ((SinglePods)airpods).getDrawable());
            }

            notification.setViewVisibility(R.id.leftPod, single ? View.GONE : View.VISIBLE);
            notification.setViewVisibility(R.id.rightPod, single ? View.GONE : View.VISIBLE);
        }

        if (isFreshStatus(status)) for (RemoteViews notification : notificationArr) {
            notification.setViewVisibility(R.id.leftPodText, View.VISIBLE);
            notification.setViewVisibility(R.id.rightPodText, View.VISIBLE);
            notification.setViewVisibility(R.id.podCaseText, View.VISIBLE);
            notification.setViewVisibility(R.id.leftPodUpdating, View.INVISIBLE);
            notification.setViewVisibility(R.id.rightPodUpdating, View.INVISIBLE);
            notification.setViewVisibility(R.id.podCaseUpdating, View.INVISIBLE);

            if (!single) {
                RegularPods regularPods = (RegularPods)airpods;

                notification.setTextViewText(R.id.leftPodText, regularPods.getParsedStatus(RegularPods.LEFT));
                notification.setTextViewText(R.id.rightPodText, regularPods.getParsedStatus(RegularPods.RIGHT));
                notification.setTextViewText(R.id.podCaseText, regularPods.getParsedStatus(RegularPods.CASE));

                notification.setImageViewResource(R.id.leftBatImg, regularPods.getBatImgSrcId(RegularPods.LEFT));
                notification.setImageViewResource(R.id.rightBatImg, regularPods.getBatImgSrcId(RegularPods.RIGHT));
                notification.setImageViewResource(R.id.caseBatImg, regularPods.getBatImgSrcId(RegularPods.CASE));

                notification.setViewVisibility(R.id.leftBatImg, regularPods.getBatImgVisibility(RegularPods.LEFT));
                notification.setViewVisibility(R.id.rightBatImg, regularPods.getBatImgVisibility(RegularPods.RIGHT));
                notification.setViewVisibility(R.id.caseBatImg, regularPods.getBatImgVisibility(RegularPods.CASE));

                notification.setViewVisibility(R.id.leftInEarImg, regularPods.getInEarVisibility(RegularPods.LEFT));
                notification.setViewVisibility(R.id.rightInEarImg, regularPods.getInEarVisibility(RegularPods.RIGHT));
            } else {
                SinglePods singlePods = (SinglePods)airpods;

                notification.setTextViewText(R.id.podCaseText, singlePods.getParsedStatus());
                notification.setImageViewResource(R.id.caseBatImg, singlePods.getBatImgSrcId());
                notification.setViewVisibility(R.id.caseBatImg, singlePods.getBatImgVisibility());
            }
        }
        else for (RemoteViews notification : notificationArr) {
            notification.setViewVisibility(R.id.leftPodText, View.INVISIBLE);
            notification.setViewVisibility(R.id.rightPodText, View.INVISIBLE);
            notification.setViewVisibility(R.id.podCaseText, View.INVISIBLE);
            notification.setViewVisibility(R.id.leftBatImg, View.GONE);
            notification.setViewVisibility(R.id.rightBatImg, View.GONE);
            notification.setViewVisibility(R.id.caseBatImg, View.GONE);
            notification.setViewVisibility(R.id.leftPodUpdating, View.VISIBLE);
            notification.setViewVisibility(R.id.rightPodUpdating, View.VISIBLE);
            notification.setViewVisibility(R.id.podCaseUpdating, View.VISIBLE);
            notification.setViewVisibility(R.id.leftInEarImg, View.INVISIBLE);
            notification.setViewVisibility(R.id.rightInEarImg, View.INVISIBLE);
        }

        return mBuilder.build();
    }

    private boolean isFreshStatus (PodsStatus status) {
        return System.currentTimeMillis() - status.getTimestamp() < TIMEOUT_CONNECTED;
    }

}