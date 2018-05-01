package com.revenuecat.purchases;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public final class Purchases implements BillingWrapper.PurchasesUpdatedListener, Application.ActivityLifecycleCallbacks {

    private final String appUserID;
    private Boolean usingAnonymousID = false;
    private final PurchasesListener listener;
    private final Backend backend;
    private final BillingWrapper billingWrapper;

    private Date subscriberInfoLastChecked;

    
    public interface PurchasesListener {
        void onCompletedPurchase(PurchaserInfo purchaserInfo);
        void onFailedPurchase(Exception reason);
        void onReceiveUpdatedPurchaserInfo(PurchaserInfo purchaserInfo);
    }

    public interface GetSkusResponseHandler {
        void onReceiveSkus(List<SkuDetails> skus);
    }

    public static String getFrameworkVersion() {
        return "0.1.0-SNAPSHOT";
    }

    Purchases(Application application,
              String appUserID, PurchasesListener listener,
              Backend backend, BillingWrapper.Factory billingWrapperFactory) {

        if (appUserID == null) {
            appUserID = UUID.randomUUID().toString();
            usingAnonymousID = true;
        }
        this.appUserID = appUserID;

        this.listener = listener;
        this.backend = backend;
        this.billingWrapper = billingWrapperFactory.buildWrapper(this);

        application.registerActivityLifecycleCallbacks(this);

        getSubscriberInfo();
    }

    public String getAppUserID() {
        return appUserID;
    }

    public void getSubscriptionSkus(List<String> skus, final GetSkusResponseHandler handler) {
        getSkus(skus, BillingClient.SkuType.SUBS, handler);
    }

    public void getNonSubscriptionSkus(List<String> skus, final GetSkusResponseHandler handler) {
        getSkus(skus, BillingClient.SkuType.INAPP, handler);
    }

    private void getSkus(List<String> skus, @BillingClient.SkuType String skuType, final GetSkusResponseHandler handler) {
        billingWrapper.querySkuDetailsAsync(skuType, skus, new BillingWrapper.SkuDetailsResponseListener() {
            @Override
            public void onReceiveSkuDetails(List<SkuDetails> skuDetails) {
                handler.onReceiveSkus(skuDetails);
            }
        });
    }

    public void makePurchase(final Activity activity, final String sku,
                             @BillingClient.SkuType final String skuType) {
        makePurchase(activity, sku, skuType, new ArrayList<String>());
    }

    public void makePurchase(final Activity activity, final String sku,
                             @BillingClient.SkuType final String skuType,
                             final ArrayList<String> oldSkus) {
        billingWrapper.makePurchaseAsync(activity, appUserID, sku, oldSkus, skuType);
    }

    public void restorePurchasesForPlayStoreAccount() {
        billingWrapper.queryPurchaseHistoryAsync(BillingClient.SkuType.SUBS, new BillingWrapper.PurchaseHistoryResponseListener() {
            @Override
            public void onReceivePurchaseHistory(List<Purchase> purchasesList) {
                postPurchases(purchasesList, true, false);
            }
        });

        billingWrapper.queryPurchaseHistoryAsync(BillingClient.SkuType.INAPP, new BillingWrapper.PurchaseHistoryResponseListener() {
            @Override
            public void onReceivePurchaseHistory(List<Purchase> purchasesList) {
                postPurchases(purchasesList, true, false);
            }
        });
    }


    private void getSubscriberInfo() {
        if (subscriberInfoLastChecked != null && (new Date().getTime() - subscriberInfoLastChecked.getTime()) < 60000) {
            return;
        }

        backend.getSubscriberInfo(appUserID, new Backend.BackendResponseHandler() {
            @Override
            public void onReceivePurchaserInfo(PurchaserInfo info) {
                subscriberInfoLastChecked = new Date();
                listener.onReceiveUpdatedPurchaserInfo(info);
            }

            @Override
            public void onError(Exception e) {
                Log.e("Purchases", "Error fetching subscriber data: " + e.getMessage());
            }
        });
    }

    private void postPurchases(List<Purchase> purchases, Boolean isRestore, final Boolean isPurchase) {
        for (Purchase p : purchases) {
            backend.postReceiptData(p.getPurchaseToken(), appUserID, p.getSku(), isRestore, new Backend.BackendResponseHandler() {
                @Override
                public void onReceivePurchaserInfo(PurchaserInfo info) {
                    if (isPurchase) {
                        listener.onCompletedPurchase(info);
                    } else {
                        listener.onReceiveUpdatedPurchaserInfo(info);
                    }
                }

                @Override
                public void onError(Exception e) {
                    listener.onFailedPurchase(e);
                }
            });
        }
    }

    @Override
    public void onPurchasesUpdated(List<Purchase> purchases) {
        postPurchases(purchases, usingAnonymousID, true);
    }

    @Override
    public void onPurchasesFailedToUpdate(int responseCode, String message) {
        listener.onFailedPurchase(new Exception(message));
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle bundle) {

    }

    @Override
    public void onActivityStarted(Activity activity) {

    }

    @Override
    public void onActivityResumed(Activity activity) {
        getSubscriberInfo();
    }

    @Override
    public void onActivityPaused(Activity activity) {

    }

    @Override
    public void onActivityStopped(Activity activity) {

    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {

    }

    @Override
    public void onActivityDestroyed(Activity activity) {

    }

    public static class Builder {
        private final Context context;
        private final String apiKey;
        private final Application application;
        private final PurchasesListener listener;
        private String appUserID;
        private ExecutorService service;

        private boolean hasPermission(Context context, String permission) {
            return context.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED;
        }

        public Builder(Context context, String apiKey, PurchasesListener listener) {

            if (!hasPermission(context, Manifest.permission.INTERNET)) {
                throw new IllegalArgumentException("Purchases requires INTERNET permission.");
            }

            if (context == null) {
                throw new IllegalArgumentException("Context must be set.");
            }

            if (apiKey == null || apiKey.length() == 0) {
                throw new IllegalArgumentException("API key must be set. Get this from the RevenueCat web app");
            }

            Application application = (Application) context.getApplicationContext();
            if (application == null) {
                throw new IllegalArgumentException("Needs an application context.");
            }

            if (listener == null) {
                throw new IllegalArgumentException("Purchases listener must be set");
            }

            this.context = context;
            this.apiKey = apiKey;
            this.application = application;
            this.listener = listener;
        }

        private ExecutorService createDefaultExecutor() {
            return new ThreadPoolExecutor(
                    1,
                    2,
                    0,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>()
            );
        }

        public Purchases build() {

            ExecutorService service = this.service;
            if (service == null) {
                service = createDefaultExecutor();
            }

            Backend backend = new Backend(this.apiKey, new Dispatcher(service), new HTTPClient(), new PurchaserInfo.Factory());

            BillingWrapper.Factory billingWrapperFactory = new BillingWrapper.Factory(new BillingWrapper.ClientFactory(context), new Handler(application.getMainLooper()));

            return new Purchases(this.application, this.appUserID, this.listener, backend, billingWrapperFactory);
        }

        public Builder appUserID(String appUserID) {
            this.appUserID = appUserID;
            return this;
        }

        public Builder networkExecutorService(ExecutorService service) {
            this.service = service;
            return this;
        }
    }
}
