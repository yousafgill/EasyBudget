/*
 *   Copyright 2015 Benoit LETONDOR
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package com.benoitletondor.easybudgetapp;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AlertDialog;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchaseHistoryRecord;
import com.android.billingclient.api.PurchaseHistoryResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.batch.android.Batch;
import com.batch.android.Config;
import com.batch.android.PushNotificationType;
import com.benoitletondor.easybudgetapp.helper.CurrencyHelper;
import com.benoitletondor.easybudgetapp.helper.Logger;
import com.benoitletondor.easybudgetapp.helper.ParameterKeys;
import com.benoitletondor.easybudgetapp.helper.Parameters;
import com.benoitletondor.easybudgetapp.helper.UIHelper;
import com.benoitletondor.easybudgetapp.helper.UserHelper;
import com.benoitletondor.easybudgetapp.notif.DailyNotifOptinService;
import com.benoitletondor.easybudgetapp.notif.MonthlyReportNotifService;
import com.benoitletondor.easybudgetapp.view.MainActivity;
import com.benoitletondor.easybudgetapp.view.RatingPopup;
import com.benoitletondor.easybudgetapp.view.SettingsActivity;
import com.crashlytics.android.Crashlytics;
import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.Tracker;

import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import io.fabric.sdk.android.Fabric;

import static com.batch.android.BatchNotificationChannelsManager.DEFAULT_CHANNEL_ID;
import static com.benoitletondor.easybudgetapp.notif.NotificationsChannels.CHANNEL_MONTHLY_REMINDERS;
import static com.benoitletondor.easybudgetapp.notif.NotificationsChannels.CHANNEL_NEW_FEATURES;
import static com.benoitletondor.easybudgetapp.notif.NotificationsChannels.CHANNEL_DAILY_REMINDERS;
import static com.benoitletondor.easybudgetapp.push.PushService.DAILY_REMINDER_KEY;
import static com.benoitletondor.easybudgetapp.push.PushService.MONTHLY_REMINDER_KEY;

/**
 * EasyBudget application. Implements GA tracking, Batch set-up, Crashlytics set-up && iab.
 *
 * @author Benoit LETONDOR
 */
public class EasyBudget extends Application implements PurchasesUpdatedListener, BillingClientStateListener, PurchaseHistoryResponseListener, SkuDetailsResponseListener
{
    /**
     * Default amount use for low money warning (can be changed in settings)
     */
    public static final int DEFAULT_LOW_MONEY_WARNING_AMOUNT = 100;
    /**
     * iab SDK used for premium
     */
    public static final String SKU_PREMIUM = "premium";
    /**
     * Intent action broadcast when the status of iab changed
     */
    public static final String INTENT_IAB_STATUS_CHANGED = "iabStatusChanged";
    /**
     * Key to retrieve the iab status on an {@link #INTENT_IAB_STATUS_CHANGED} intent
     */
    public static final String INTENT_IAB_STATUS_KEY = "iabKey";

// ------------------------------------------>

    /**
     * GA tracker
     */
    private Tracker analyticsTracker;

    /**
     * Helper to work with iab
     */
    private BillingClient iabHelper;
    /**
     * iab check status
     */
    private volatile PremiumCheckStatus iabStatus;
    /**
     * Listener for the current purchase
     */
    @Nullable
    private PremiumPurchaseListener premiumPurchaseListener;
    /**
     * Activity that triggered the current purchase
     */
    @NonNull
    private WeakReference<Activity> purchaseActivity = new WeakReference<>(null);

// ------------------------------------------>

    @Override
    public void onCreate()
    {
        super.onCreate();

        // Init actions
        init();

        // Check if an update occurred and perform action if needed
        checkUpdateAction();

        // Crashlytics
        if( BuildConfig.CRASHLYTICS_ACTIVATED )
        {
            Fabric.with(this, new Crashlytics());

            Crashlytics.setUserIdentifier(Parameters.getInstance(getApplicationContext()).getString(ParameterKeys.LOCAL_ID));
        }

        // Batch
        setUpBatchSDK();

        // Analytics
        GoogleAnalytics analytics = GoogleAnalytics.getInstance(this);
        analytics.setDryRun(!BuildConfig.ANALYTICS_ACTIVATED);

        analyticsTracker = analytics.newTracker(R.xml.analytics);
        analyticsTracker.enableAdvertisingIdCollection(false);

        // In-app billing
        setupIab();
    }

    /**
     * Init app const and parameters
     */
    private void init()
    {
        /*
         * Save first launch date if needed
         */
        long initDate = Parameters.getInstance(getApplicationContext()).getLong(ParameterKeys.INIT_DATE, 0);
        if( initDate <= 0 )
        {
            Logger.debug("Registering first launch date");

            Parameters.getInstance(getApplicationContext()).putLong(ParameterKeys.INIT_DATE, new Date().getTime());
            CurrencyHelper.setUserCurrency(this, Currency.getInstance(Locale.getDefault())); // Set a default currency before onboarding
        }

        /*
         * Create local ID if needed
         */
        String localId = Parameters.getInstance(getApplicationContext()).getString(ParameterKeys.LOCAL_ID);
        if( localId == null )
        {
            localId = UUID.randomUUID().toString();
            Logger.debug("Generating local id : "+localId);

            Parameters.getInstance(getApplicationContext()).putString(ParameterKeys.LOCAL_ID, localId);
        }
        else
        {
            Logger.debug("Local id : " + localId);
        }

        // Activity counter for app foreground & background
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks()
        {
            private int activityCounter = 0;

            @Override
            public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState)
            {

            }

            @Override
            public void onActivityStarted(@NonNull Activity activity)
            {
                if (activityCounter == 0)
                {
                    onAppForeground(activity);
                }

                activityCounter++;
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity)
            {

            }

            @Override
            public void onActivityPaused(@NonNull Activity activity)
            {

            }

            @Override
            public void onActivityStopped(@NonNull Activity activity)
            {
                if (activityCounter == 1)
                {
                    onAppBackground();
                }

                activityCounter--;
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState)
            {

            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity)
            {

            }
        });
    }

    /**
     * Show the rating popup if the user didn't asked not to every day after the app has been open
     * in 3 different days.
     */
    private void showRatingPopupIfNeeded(@NonNull Activity activity)
    {
        try
        {
            if( !(activity instanceof MainActivity) )
            {
                Logger.debug("Not showing rating popup cause app is not opened by the MainActivity");
                return;
            }

            int dailyOpens = Parameters.getInstance(getApplicationContext()).getInt(ParameterKeys.NUMBER_OF_DAILY_OPEN, 0);
            if( dailyOpens > 2 )
            {
                if( !hasRatingPopupBeenShownToday() )
                {
                    boolean shown = new RatingPopup(activity).show(false);
                    if( shown )
                    {
                        Parameters.getInstance(getApplicationContext()).putLong(ParameterKeys.RATING_POPUP_LAST_AUTO_SHOW, new Date().getTime());
                    }
                }
            }
        }
        catch (Exception e)
        {
            Logger.error("Error while showing rating popup", e);
        }
    }

    private void showPremiumPopupIfNeeded(@NonNull final Activity activity)
    {
        try
        {
            if( !(activity instanceof MainActivity) )
            {
                return;
            }

            if( Parameters.getInstance(getApplicationContext()).getBoolean(ParameterKeys.PREMIUM_POPUP_COMPLETE, false) )
            {
                return;
            }

            if( UserHelper.isUserPremium(this) )
            {
                return;
            }

            if( !UserHelper.hasUserCompleteRating(activity) )
            {
                return;
            }

            RatingPopup.RatingPopupStep currentStep = RatingPopup.getUserStep(activity);
            if( currentStep == RatingPopup.RatingPopupStep.STEP_LIKE ||
                    currentStep == RatingPopup.RatingPopupStep.STEP_LIKE_NOT_RATED ||
                    currentStep == RatingPopup.RatingPopupStep.STEP_LIKE_RATED )
            {
                if( !hasRatingPopupBeenShownToday() && shouldShowPremiumPopup() )
                {
                    Parameters.getInstance(getApplicationContext()).putLong(ParameterKeys.PREMIUM_POPUP_LAST_AUTO_SHOW, new Date().getTime());

                    AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle(R.string.premium_popup_become_title)
                        .setMessage(R.string.premium_popup_become_message)
                        .setPositiveButton(R.string.premium_popup_become_cta, (dialog13, which) -> {
                            Intent startIntent = new Intent(activity, SettingsActivity.class);
                            startIntent.putExtra(SettingsActivity.SHOW_PREMIUM_INTENT_KEY, true);
                            ActivityCompat.startActivity(activity, startIntent, null);

                            dialog13.dismiss();
                        })
                        .setNegativeButton(R.string.premium_popup_become_not_now, (dialog12, which) -> dialog12.dismiss())
                        .setNeutralButton(R.string.premium_popup_become_not_ask_again, (dialog1, which) -> {
                            Parameters.getInstance(getApplicationContext()).putBoolean(ParameterKeys.PREMIUM_POPUP_COMPLETE, true);
                            dialog1.dismiss();
                        })
                        .show();

                    UIHelper.centerDialogButtons(dialog);
                }
            }
        }
        catch (Exception e)
        {
            Logger.error("Error while showing become premium popup", e);
        }
    }

    /**
     * Has the rating popup been shown automatically today
     *
     * @return true if the rating popup has been shown today, false otherwise
     */
    private boolean hasRatingPopupBeenShownToday()
    {
        long lastRatingTS = Parameters.getInstance(getApplicationContext()).getLong(ParameterKeys.RATING_POPUP_LAST_AUTO_SHOW, 0);
        if( lastRatingTS > 0 )
        {
            Calendar cal = Calendar.getInstance();
            int currentDay = cal.get(Calendar.DAY_OF_YEAR);

            cal.setTime(new Date(lastRatingTS));
            int lastTimeDay = cal.get(Calendar.DAY_OF_YEAR);

            return currentDay == lastTimeDay;
        }

        return false;
    }

    /**
     * Check that last time the premium popup was shown was 2 days ago or more
     *
     * @return true if we can show premium popup, false otherwise
     */
    private boolean shouldShowPremiumPopup()
    {
        long lastPremiumTS = Parameters.getInstance(getApplicationContext()).getLong(ParameterKeys.PREMIUM_POPUP_LAST_AUTO_SHOW, 0);
        if( lastPremiumTS == 0 )
        {
            return true;
        }

        // Set calendar to last time 00:00 + 2 days
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date(lastPremiumTS));
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, 2);

        return new Date().after(cal.getTime());
    }

    /**
     * Show the 1.5 app update notification
     */
    private void show1_5UpdateNotif()
    {
        NotificationCompat.Builder notifBuilder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_NEW_FEATURES)
            .setSmallIcon(R.drawable.ic_push)
            .setContentTitle(getResources().getString(R.string.app_name))
            .setContentText(getResources().getString(R.string.recurring_update_notification))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(getResources().getString(R.string.recurring_update_notification)))
            .setColor(ContextCompat.getColor(getApplicationContext(), R.color.accent))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_CANCEL_CURRENT));

        NotificationManagerCompat.from(getApplicationContext()).notify(4014, notifBuilder.build());
    }

    /**
     * Set-up Batch SDK config + lifecycle
     */
    private void setUpBatchSDK()
    {
        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ) {
            // Monthly report channel
            final CharSequence name = getString(R.string.setting_category_notifications_monthly_title);
            final String description = getString(R.string.setting_category_notifications_monthly_message);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;

            NotificationChannel monthlyReportChannel = new NotificationChannel(CHANNEL_MONTHLY_REMINDERS, name, importance);
            monthlyReportChannel.setDescription(description);

            // Daily reminder channel
            final CharSequence dailyName = getString(R.string.setting_category_notifications_daily_title);
            final String dailyDescription = getString(R.string.setting_category_notifications_daily_message);
            int dailyImportance = NotificationManager.IMPORTANCE_DEFAULT;

            NotificationChannel dailyReportChannel = new NotificationChannel(CHANNEL_DAILY_REMINDERS, dailyName, dailyImportance);
            dailyReportChannel.setDescription(dailyDescription);

            // New features channel
            final CharSequence newFeatureName = getString(R.string.setting_category_notifications_update_title);
            final String newFeatureDescription = getString(R.string.setting_category_notifications_update_message);
            int newFeatureImportance = NotificationManager.IMPORTANCE_LOW;

            NotificationChannel newFeatureChannel = new NotificationChannel(CHANNEL_NEW_FEATURES, newFeatureName, newFeatureImportance);
            newFeatureChannel.setDescription(newFeatureDescription);

            final NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if( notificationManager != null )
            {
                notificationManager.createNotificationChannel(newFeatureChannel);
                notificationManager.createNotificationChannel(monthlyReportChannel);
                notificationManager.createNotificationChannel(dailyReportChannel);

                // Remove Batch's default
                notificationManager.deleteNotificationChannel(DEFAULT_CHANNEL_ID);
            }
        }

        Batch.setConfig(new Config(BuildConfig.BATCH_API_KEY).setCanUseAdvertisingID(false));
        Batch.Push.setManualDisplay(true);
        Batch.Push.setSmallIconResourceId(R.drawable.ic_push);
        Batch.Push.setNotificationsColor(ContextCompat.getColor(this, R.color.accent));
        Batch.Push.getChannelsManager().setChannelIdInterceptor((payload, deductedChannelId) -> {
            if ( "true".equalsIgnoreCase(payload.getPushBundle().getString(DAILY_REMINDER_KEY)) )
            {
                return CHANNEL_DAILY_REMINDERS;
            }

            if ( "true".equalsIgnoreCase(payload.getPushBundle().getString(MONTHLY_REMINDER_KEY)) )
            {
                return CHANNEL_MONTHLY_REMINDERS;
            }

            return CHANNEL_NEW_FEATURES;
        });

        // Remove vibration & sound
        EnumSet<PushNotificationType> notificationTypes = EnumSet.allOf(PushNotificationType.class);
        notificationTypes.remove(PushNotificationType.VIBRATE);
        notificationTypes.remove(PushNotificationType.SOUND);
        Batch.Push.setNotificationsType(notificationTypes);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks()
        {
            @Override
            public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState)
            {

            }

            @Override
            public void onActivityStarted(@NonNull final Activity activity)
            {
                Batch.onStart(activity);
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity)
            {

            }

            @Override
            public void onActivityPaused(@NonNull Activity activity)
            {

            }

            @Override
            public void onActivityStopped(@NonNull Activity activity)
            {
                Batch.onStop(activity);
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState)
            {

            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity)
            {
                Batch.onDestroy(activity);
            }
        });
    }

    /**
     * Check if a an update occured and call {@link #onUpdate(int, int)} if so
     */
    private void checkUpdateAction()
    {
        int savedVersion = Parameters.getInstance(getApplicationContext()).getInt(ParameterKeys.APP_VERSION, 0);
        if( savedVersion > 0 && savedVersion != BuildConfig.VERSION_CODE )
        {
            onUpdate(savedVersion, BuildConfig.VERSION_CODE);
        }

        Parameters.getInstance(getApplicationContext()).putInt(ParameterKeys.APP_VERSION, BuildConfig.VERSION_CODE);
    }

    /**
     * Called when an update occurred
     */
    private void onUpdate(int previousVersion, int newVersion)
    {
        Logger.debug("Update detected, from " + previousVersion + " to " + newVersion);

        // Fix bad save of Batch premium before 1.1
        if( previousVersion <= BuildVersion.VERSION_1_1_3)
        {
            UserHelper.setBatchUserPremium(this);
        }

        if( newVersion == BuildVersion.VERSION_1_2 )
        {
            if( UserHelper.isUserPremium(this) && !DailyNotifOptinService.hasDailyReminderOptinNotifBeenShown(this) )
            {
                DailyNotifOptinService.showDailyReminderOptinNotif(getApplicationContext());
            }
        }

        if( newVersion == BuildVersion.VERSION_1_3 && !MonthlyReportNotifService.hasUserSeenMonthlyReportNotif(this) )
        {
            if( UserHelper.isUserPremium(this) )
            {
                MonthlyReportNotifService.showPremiumNotif(getApplicationContext());
            }
            else
            {
                MonthlyReportNotifService.showNotPremiumNotif(getApplicationContext());
            }
        }

        if( newVersion == BuildVersion.VERSION_1_5_2 )
        {
            show1_5UpdateNotif();
        }
    }

// -------------------------------------->

    /**
     * Called when the app goes foreground
     *
     * @param activity The activity that gone foreground
     */
    private void onAppForeground(@NonNull Activity activity)
    {
        Logger.debug("onAppForeground");

        /*
         * Increment the number of open
         */
        Parameters.getInstance(getApplicationContext()).putInt(ParameterKeys.NUMBER_OF_OPEN, Parameters.getInstance(getApplicationContext()).getInt(ParameterKeys.NUMBER_OF_OPEN, 0) + 1);

        /*
         * Check if last open is from another day
         */
        boolean shouldIncrementDailyOpen = false;

        long lastOpen = Parameters.getInstance(getApplicationContext()).getLong(ParameterKeys.LAST_OPEN_DATE, 0);
        if( lastOpen > 0 )
        {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date(lastOpen));

            int lastDay = cal.get(Calendar.DAY_OF_YEAR);

            cal.setTime(new Date());
            int currentDay = cal.get(Calendar.DAY_OF_YEAR);

            if( lastDay != currentDay )
            {
                shouldIncrementDailyOpen = true;
            }
        }
        else
        {
            shouldIncrementDailyOpen = true;
        }

        // Increment daily open
        if( shouldIncrementDailyOpen )
        {
            Parameters.getInstance(getApplicationContext()).putInt(ParameterKeys.NUMBER_OF_DAILY_OPEN, Parameters.getInstance(getApplicationContext()).getInt(ParameterKeys.NUMBER_OF_DAILY_OPEN, 0) + 1);
        }

        /*
         * Save last open date
         */
        Parameters.getInstance(getApplicationContext()).putLong(ParameterKeys.LAST_OPEN_DATE, new Date().getTime());

        /*
         * Rating popup every day after 3 opens
         */
        showRatingPopupIfNeeded(activity);

        /*
         * Premium popup after rating complete
         */
        showPremiumPopupIfNeeded(activity);

        /*
         * Update iap status if needed
         */
        updateIAPStatusIfNeeded();
    }

    /**
     * Called when the app goes background
     */
    private void onAppBackground()
    {
        Logger.debug("onAppBackground");
    }

// -------------------------------------->
    // region iab

    private void setupIab()
    {
        try
        {
            setIabStatusAndNotify(PremiumCheckStatus.INITIALIZING);

            iabHelper = BillingClient.newBuilder(this)
                .setListener(this)
                .enablePendingPurchases()
                .build();

            iabHelper.startConnection(this);
        }
        catch (Exception e)
        {
            Logger.error("Error while checking iab status", e);
            setIabStatusAndNotify(PremiumCheckStatus.ERROR);
        }
    }

    /**
     * Set the new iab status and notify the app by sending an {@link #INTENT_IAB_STATUS_CHANGED} intent
     *
     * @param status the new status
     */
    private void setIabStatusAndNotify(@NonNull PremiumCheckStatus status)
    {
        iabStatus = status;

        // Save status only on success
        if( status == PremiumCheckStatus.PREMIUM || status == PremiumCheckStatus.NOT_PREMIUM )
        {
            Parameters.getInstance(this).putBoolean(ParameterKeys.PREMIUM, iabStatus == PremiumCheckStatus.PREMIUM);
        }

        Intent intent = new Intent(INTENT_IAB_STATUS_CHANGED);
        intent.putExtra(INTENT_IAB_STATUS_KEY, status);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * Update the current IAP status if already checked
     */
    private void updateIAPStatusIfNeeded()
    {
        Logger.debug("updateIAPStatusIfNeeded: "+iabStatus);

        if( iabStatus == PremiumCheckStatus.NOT_PREMIUM )
        {
            setIabStatusAndNotify(PremiumCheckStatus.CHECKING);
            iabHelper.queryPurchaseHistoryAsync(BillingClient.SkuType.INAPP, this);
        } else if ( iabStatus == PremiumCheckStatus.ERROR ) {
            setupIab();
        }
    }

    /**
     * Launch the redeem promocode flow
     *
     * @param promocode the promocode to redeem
     * @param activity the current activity
     */
    public boolean launchRedeemPromocodeFlow(@NonNull String promocode, @NonNull Activity activity)
    {
        try
        {
            String url = "https://play.google.com/redeem?code=" + URLEncoder.encode(promocode, "UTF-8");
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        }
        catch (Exception e)
        {
            Logger.error(false, "Error while redeeming promocode", e);
            return false;
        }
    }

    /**
     * Launch the premium purchase flow
     *
     * @param activity activity that started this purchase
     * @param listener listener for purchase events
     */
    public void launchPremiumPurchaseFlow(final @NonNull Activity activity, final @NonNull PremiumPurchaseListener listener)
    {
        if( iabStatus != PremiumCheckStatus.NOT_PREMIUM )
        {
            if( iabStatus == PremiumCheckStatus.ERROR )
            {
                listener.onPurchaseError("Unable to connect to your Google account. Please restart the app and try again");
            }
            else if( iabStatus == PremiumCheckStatus.PREMIUM )
            {
                listener.onPurchaseError("You already bought Premium with that Google account. Restart the app if you don't have access to premium features.");
            }
            else
            {
                listener.onPurchaseError("Runtime error: "+iabStatus);
            }

            return;
        }

        premiumPurchaseListener = listener;
        purchaseActivity = new WeakReference<>(activity);

        final List<String> skuList = new ArrayList<>(1);
        skuList.add(EasyBudget.SKU_PREMIUM);

        Logger.debug("Launching querySkuDetailsAsync");

        iabHelper.querySkuDetailsAsync(
            SkuDetailsParams.newBuilder()
                .setSkusList(skuList)
                .setType(BillingClient.SkuType.INAPP)
                .build(),
            this
        );
    }

    @Override
    public void onSkuDetailsResponse(@NonNull BillingResult billingResult, List<SkuDetails> skuDetailsList)
    {
        Logger.debug("onSkuDetailsResponse");
        final Activity activity = purchaseActivity.get();
        final PremiumPurchaseListener listener = premiumPurchaseListener;

        if( activity == null || listener == null ) {
            Logger.debug("onSkuDetailsResponse: activity or listener null");
            return;
        }

        if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK)
        {
            if( billingResult.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED )
            {
                setIabStatusAndNotify(PremiumCheckStatus.PREMIUM);
                listener.onPurchaseSuccess();
                return;
            }

            Objects.requireNonNull(premiumPurchaseListener).onPurchaseError("Unable to connect to reach PlayStore (response code: "+billingResult.getResponseCode()+"). Please restart the app and try again");
            return;
        }

        if( skuDetailsList.isEmpty() )
        {
            Objects.requireNonNull(premiumPurchaseListener).onPurchaseError("Unable to fetch content from PlayStore (response code: skuDetailsList is empty). Please restart the app and try again");
            return;
        }

        iabHelper.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setSkuDetails(skuDetailsList.get(0))
            .build()
        );
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult)
    {
        Logger.debug("iab setup finished.");

        if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK)
        {
            // Oh noes, there was a problem.
            setIabStatusAndNotify(PremiumCheckStatus.ERROR);
            Logger.error("Error while setting-up iab: "+billingResult.getResponseCode());
            return;
        }

        setIabStatusAndNotify(PremiumCheckStatus.CHECKING);

        iabHelper.queryPurchaseHistoryAsync(BillingClient.SkuType.INAPP, this);
    }

    @Override
    public void onBillingServiceDisconnected()
    {
        Logger.debug("onBillingServiceDisconnected");

        setIabStatusAndNotify(PremiumCheckStatus.ERROR);
    }

    @Override
    public void onPurchaseHistoryResponse(@NonNull BillingResult billingResult, List<PurchaseHistoryRecord> purchaseHistoryRecordList)
    {
        Logger.debug("iab query inventory finished.");

        // Is it a failure?
        if ( billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK )
        {
            Logger.error("Error while querying iab inventory: "+billingResult.getResponseCode());
            setIabStatusAndNotify(PremiumCheckStatus.ERROR);
            return;
        }

        boolean premium = false;
        if (purchaseHistoryRecordList != null) {
            for (PurchaseHistoryRecord purchase : purchaseHistoryRecordList) {
                if( EasyBudget.SKU_PREMIUM.equals(purchase.getSku()) ) {
                    premium = true;
                }
            }
        }

        Logger.debug("iab query inventory was successful: "+premium);

        setIabStatusAndNotify(premium ? PremiumCheckStatus.PREMIUM : PremiumCheckStatus.NOT_PREMIUM);
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> purchases)
    {
        final PremiumPurchaseListener listener = premiumPurchaseListener;
        premiumPurchaseListener = null;

        if( listener == null ) {
            return;
        }

        Logger.debug("Purchase finished: " + billingResult.getResponseCode());

        if ( billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK )
        {
            Logger.error("Error while purchasing premium: " + billingResult.getResponseCode());
            if( billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED )
            {
                listener.onUserCancelled();
            }
            else if( billingResult.getResponseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED )
            {
                setIabStatusAndNotify(PremiumCheckStatus.PREMIUM);
                listener.onPurchaseSuccess();
                return;
            }
            else
            {
                listener.onPurchaseError("An error occurred (status code: "+billingResult.getResponseCode()+")");
            }

            return;
        }

        Logger.debug("Purchase successful.");

        if( purchases == null || purchases.size() == 0 )
        {
            listener.onPurchaseError("No purchased item found");
            return;
        }

        boolean premiumBought = false;
        for(Purchase purchase : purchases) {
            if( EasyBudget.SKU_PREMIUM.equals(purchase.getSku()) ) {
                premiumBought = true;
            }
        }

        if( !premiumBought ) {
            listener.onPurchaseError("No purchased item found");
            return;
        }

        setIabStatusAndNotify(PremiumCheckStatus.PREMIUM);
        listener.onPurchaseSuccess();
    }

    //endregion
}
