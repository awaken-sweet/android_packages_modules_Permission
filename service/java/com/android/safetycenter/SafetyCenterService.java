/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.safetycenter;

import static android.Manifest.permission.MANAGE_SAFETY_CENTER;
import static android.Manifest.permission.READ_SAFETY_CENTER_STATUS;
import static android.Manifest.permission.SEND_SAFETY_CENTER_UPDATE;
import static android.os.Build.VERSION_CODES.TIRAMISU;
import static android.safetycenter.SafetyCenterManager.RefreshReason;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.provider.DeviceConfig;
import android.safetycenter.IOnSafetyCenterDataChangedListener;
import android.safetycenter.ISafetyCenterManager;
import android.safetycenter.SafetyCenterData;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceError;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.GuardedBy;
import com.android.permission.util.UserUtils;
import com.android.server.SystemService;

import java.util.Arrays;

/**
 * Service for the safety center.
 *
 * @hide
 */
@Keep
@RequiresApi(TIRAMISU)
public final class SafetyCenterService extends SystemService {

    private static final String TAG = "SafetyCenterService";

    /** Phenotype flag that determines whether SafetyCenter is enabled. */
    private static final String PROPERTY_SAFETY_CENTER_ENABLED = "safety_center_is_enabled";

    private final Object mApiLock = new Object();
    // Refresh/rescan is guarded by another lock: sending broadcasts can be a lengthy operation and
    // the APIs that will be exercised by the receivers are already protected by `mApiLock`.
    private final Object mRefreshLock = new Object();
    @GuardedBy("mApiLock")
    private final SafetyCenterListeners mSafetyCenterListeners = new SafetyCenterListeners();
    @NonNull
    private final SafetyCenterConfigReader mSafetyCenterConfigReader;
    @GuardedBy("mApiLock")
    @NonNull
    private final SafetyCenterDataTracker mSafetyCenterDataTracker;
    @GuardedBy("mRefreshLock")
    @NonNull
    private final SafetyCenterRefreshManager mSafetyCenterRefreshManager;
    @NonNull
    private final AppOpsManager mAppOpsManager;

    public SafetyCenterService(@NonNull Context context) {
        super(context);
        mSafetyCenterConfigReader = new SafetyCenterConfigReader(context);
        mSafetyCenterDataTracker = new SafetyCenterDataTracker(context, mSafetyCenterConfigReader);
        mSafetyCenterRefreshManager = new SafetyCenterRefreshManager(context,
                mSafetyCenterConfigReader);
        mAppOpsManager = requireNonNull(context.getSystemService(AppOpsManager.class));
    }

    @Override
    public void onStart() {
        publishBinderService(Context.SAFETY_CENTER_SERVICE, new Stub());
        mSafetyCenterConfigReader.loadConfig();
    }

    /** Service implementation of {@link ISafetyCenterManager.Stub}. */
    private final class Stub extends ISafetyCenterManager.Stub {
        @Override
        public void sendSafetyCenterUpdate(
                @NonNull SafetySourceData safetySourceData,
                @NonNull String packageName,
                @UserIdInt int userId) {
            // TODO(b/205706756): Security: check certs?
            getContext().enforceCallingOrSelfPermission(SEND_SAFETY_CENTER_UPDATE,
                    "sendSafetyCenterUpdate");
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
            if (!enforceCrossUserPermission("sendSafetyCenterUpdate", userId)) {
                return;
            }
            // TODO(b/218812582): Validate the SafetySourceData.

            SafetyCenterData safetyCenterData;
            RemoteCallbackList<IOnSafetyCenterDataChangedListener> listeners;
            synchronized (mApiLock) {
                safetyCenterData = mSafetyCenterDataTracker.setSafetySourceData(
                        safetySourceData, safetySourceData.getId(), packageName, userId);
                listeners = mSafetyCenterListeners.getListeners(userId);
            }

            // This doesn't need to be done while holding the lock, as RemoteCallbackList already
            // handles concurrent calls.
            // If the listener uses SafetyCenterManager and is executed on #directExecutor(),
            // doing this while holding the lock could also potentially lead to deadlocks.
            if (listeners != null && safetyCenterData != null) {
                // TODO(b/218811189): This should be called on all listeners associated with the
                //  userId, i.e. if #sendSafetyCenterUpdate is called with a work profile userId,
                //  we should also let the personal profile listeners know about the update.
                SafetyCenterListeners.deliverUpdate(listeners, safetyCenterData);
            }
        }

        @Override
        @Nullable
        public SafetySourceData getLastSafetyCenterUpdate(
                @NonNull String safetySourceId,
                @NonNull String packageName,
                @UserIdInt int userId) {
            // TODO(b/205706756): Security: check certs?
            getContext().enforceCallingOrSelfPermission(
                    SEND_SAFETY_CENTER_UPDATE, "getLastSafetyCenterUpdate");
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
            if (!enforceCrossUserPermission("getLastSafetyCenterUpdate", userId)) {
                return null;
            }

            synchronized (mApiLock) {
                return mSafetyCenterDataTracker.getSafetySourceData(safetySourceId, packageName,
                        userId);
            }
        }

        @Override
        public void reportSafetySourceError(
                @NonNull String safetySourceId,
                @NonNull SafetySourceError error,
                @NonNull String packageName,
                @UserIdInt int userId) {
            getContext().enforceCallingOrSelfPermission(
                    SEND_SAFETY_CENTER_UPDATE, "reportSafetySourceError");
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
            if (!enforceCrossUserPermission("reportSafetySourceError", userId)) {
                return;
            }
            // TODO(b/218379298): Add implementation
        }

        @Override
        public boolean isSafetyCenterEnabled() {
            enforceAnyCallingOrSelfPermissions("isSafetyCenterEnabled",
                    READ_SAFETY_CENTER_STATUS,
                    SEND_SAFETY_CENTER_UPDATE);
            // TODO(b/214568975): Decide if we should disable safety center if there is a problem
            //  reading the config.

            // We don't require the caller to have READ_DEVICE_CONFIG permission.
            final long callingId = Binder.clearCallingIdentity();
            try {
                return DeviceConfig.getBoolean(
                        DeviceConfig.NAMESPACE_PRIVACY,
                        PROPERTY_SAFETY_CENTER_ENABLED,
                        /* defaultValue = */ false)
                        && getSafetyCenterConfigValue();
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }

        @Override
        public void refreshSafetySources(
                @RefreshReason int refreshReason,
                @UserIdInt int userId) {
            getContext().enforceCallingPermission(MANAGE_SAFETY_CENTER, "refreshSafetySources");
            if (!enforceCrossUserPermission("refreshSafetySources", userId)) {
                return;
            }

            synchronized (mRefreshLock) {
                mSafetyCenterRefreshManager.refreshSafetySources(refreshReason);
            }
        }

        @Override
        @NonNull
        public SafetyCenterData getSafetyCenterData(@UserIdInt int userId) {
            getContext().enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER,
                    "getSafetyCenterData");
            if (!enforceCrossUserPermission("getSafetyCenterData", userId)) {
                // TODO(b/203098016): Return default value here instead. This is marked as @NonNull.
                return null;
            }

            synchronized (mApiLock) {
                return mSafetyCenterDataTracker.getSafetyCenterData(userId);
            }
        }

        @Override
        public void addOnSafetyCenterDataChangedListener(
                @NonNull IOnSafetyCenterDataChangedListener listener,
                @UserIdInt int userId) {
            getContext().enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER,
                    "addOnSafetyCenterDataChangedListener");
            if (!enforceCrossUserPermission("addOnSafetyCenterDataChangedListener", userId)) {
                return;
            }

            boolean registered;
            SafetyCenterData safetyCenterData;
            synchronized (mApiLock) {
                registered = mSafetyCenterListeners.addListener(listener, userId);
                safetyCenterData = mSafetyCenterDataTracker.getSafetyCenterData(userId);
            }

            // This doesn't need to be done while holding the lock.
            // If the listener uses SafetyCenterManager and is executed on #directExecutor(),
            // doing this while holding the lock could also potentially lead to deadlocks.
            if (registered) {
                SafetyCenterListeners.deliverUpdate(listener, safetyCenterData);
            }
        }

        @Override
        public void removeOnSafetyCenterDataChangedListener(
                @NonNull IOnSafetyCenterDataChangedListener listener,
                @UserIdInt int userId) {
            getContext().enforceCallingOrSelfPermission(
                    MANAGE_SAFETY_CENTER, "removeOnSafetyCenterDataChangedListener");
            if (!enforceCrossUserPermission("removeOnSafetyCenterDataChangedListener", userId)) {
                return;
            }

            synchronized (mApiLock) {
                mSafetyCenterListeners.removeListener(listener, userId);
            }
        }

        @Override
        public void dismissSafetyIssue(String issueId, @UserIdInt int userId) {
            getContext().enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER, "dismissSafetyIssue");
            if (!enforceCrossUserPermission("dismissSafetyIssue", userId)) {
                return;
            }
            // TODO(b/202387059): Implement issue dismissal.

        }

        @Override
        public void clearSafetyCenterData() {
            getContext().enforceCallingOrSelfPermission(
                    MANAGE_SAFETY_CENTER, "clearSafetyCenterData");

            synchronized (mApiLock) {
                mSafetyCenterDataTracker.clear();
            }
        }

        @Override
        public void executeAction(
                @NonNull String safetyCenterIssueId,
                @NonNull String safetyCenterActionId,
                @UserIdInt int userId) {
            getContext().enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER,
                    "executeAction");
            if (!enforceCrossUserPermission("executeAction", userId)) {
                return;
            }
            // TODO(b/218379298): Add implementation
        }

        @Override
        public void addAdditionalSafetySource(
                @NonNull String sourceId,
                @NonNull String packageName,
                @NonNull String broadcastReceiverName) {
            getContext().enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER,
                    "addAdditionalSafetySource");

            synchronized (mRefreshLock) {
                mSafetyCenterRefreshManager.addAdditionalSafetySourceBroadcastReceiverComponent(
                        new ComponentName(packageName, broadcastReceiverName));
            }
        }

        @Override
        public void clearAdditionalSafetySources() {
            getContext().enforceCallingOrSelfPermission(
                    MANAGE_SAFETY_CENTER, "clearAdditionalSafetySources");

            synchronized (mRefreshLock) {
                mSafetyCenterRefreshManager
                        .clearAdditionalSafetySourceBroadcastReceiverComponents();
            }
        }

        private boolean getSafetyCenterConfigValue() {
            return getContext().getResources().getBoolean(Resources.getSystem().getIdentifier(
                    "config_enableSafetyCenter",
                    "bool",
                    "android"));
        }

        private void enforceAnyCallingOrSelfPermissions(@NonNull String message,
                String... permissions) {
            if (permissions.length == 0) {
                throw new IllegalArgumentException("Must check at least one permission");
            }
            for (int i = 0; i < permissions.length; i++) {
                if (getContext().checkCallingOrSelfPermission(permissions[i])
                        == PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            throw new SecurityException(message + " requires any of: "
                    + Arrays.toString(permissions) + ", but none were granted");
        }

        /** Enforces cross user permission and returns whether the user is existent. */
        private boolean enforceCrossUserPermission(@NonNull String message, @UserIdInt int userId) {
            UserUtils.enforceCrossUserPermission(userId, false, message, getContext());
            if (!UserUtils.isUserExistent(userId, getContext())) {
                Log.e(TAG, String.format("User id %s does not correspond to an existing user",
                        userId));
                return false;
            }
            return true;
        }
    }
}
