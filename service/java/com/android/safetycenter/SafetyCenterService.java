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
import static android.safetycenter.config.SafetySource.SAFETY_SOURCE_TYPE_STATIC;

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
import android.safetycenter.SafetyEvent;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceError;
import android.safetycenter.config.SafetyCenterConfig;
import android.safetycenter.config.SafetySource;
import android.safetycenter.config.SafetySourcesGroup;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.GuardedBy;
import com.android.permission.util.UserUtils;
import com.android.server.SystemService;

import java.util.Arrays;
import java.util.List;

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
        public void setSafetySourceData(
                @NonNull String safetySourceId,
                @Nullable SafetySourceData safetySourceData,
                @NonNull SafetyEvent safetyEvent,
                @NonNull String packageName,
                @UserIdInt int userId) {
            // TODO(b/205706756): Security: check certs?
            getContext().enforceCallingOrSelfPermission(SEND_SAFETY_CENTER_UPDATE,
                    "setSafetySourceData");
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
            if (!enforceCrossUserPermission("setSafetySourceData", userId)) {
                return;
            }
            // TODO(b/218812582): Validate the SafetySourceData.

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);
            SafetyCenterData safetyCenterData = null;
            List<RemoteCallbackList<IOnSafetyCenterDataChangedListener>> listeners = null;
            synchronized (mApiLock) {
                boolean hasUpdate = mSafetyCenterDataTracker.setSafetySourceData(
                        safetySourceData, safetySourceId, packageName, userId);
                if (hasUpdate) {
                    safetyCenterData = mSafetyCenterDataTracker.getSafetyCenterData(
                            userProfileGroup);
                    listeners = mSafetyCenterListeners.getListeners(userProfileGroup);
                }
            }

            // This doesn't need to be done while holding the lock, as RemoteCallbackList already
            // handles concurrent calls.
            if (listeners != null && safetyCenterData != null) {
                SafetyCenterListeners.deliverUpdate(listeners, safetyCenterData);
            }
        }

        @Override
        @Nullable
        public SafetySourceData getSafetySourceData(
                @NonNull String safetySourceId,
                @NonNull String packageName,
                @UserIdInt int userId) {
            // TODO(b/205706756): Security: check certs?
            getContext().enforceCallingOrSelfPermission(
                    SEND_SAFETY_CENTER_UPDATE, "getSafetySourceData");
            mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
            if (!enforceCrossUserPermission("getSafetySourceData", userId)) {
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
        public void refreshSafetySources(
                @RefreshReason int refreshReason,
                @UserIdInt int userId) {
            getContext().enforceCallingPermission(MANAGE_SAFETY_CENTER, "refreshSafetySources");
            if (!enforceCrossUserPermission("refreshSafetySources", userId)) {
                return;
            }

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);
            synchronized (mRefreshLock) {
                mSafetyCenterRefreshManager.refreshSafetySources(refreshReason, userProfileGroup);
            }
        }

        @Override
        @NonNull
        public SafetyCenterData getSafetyCenterData(@UserIdInt int userId) {
            getContext().enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER,
                    "getSafetyCenterData");
            if (!enforceCrossUserPermission("getSafetyCenterData", userId)) {
                return SafetyCenterDataTracker.getDefaultSafetyCenterData();
            }

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);
            synchronized (mApiLock) {
                return mSafetyCenterDataTracker.getSafetyCenterData(userProfileGroup);
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

            UserProfileGroup userProfileGroup = UserProfileGroup.from(getContext(), userId);
            SafetyCenterData safetyCenterData = null;
            synchronized (mApiLock) {
                boolean registered = mSafetyCenterListeners.addListener(listener, userId);
                if (registered) {
                    safetyCenterData = mSafetyCenterDataTracker.getSafetyCenterData(
                            userProfileGroup);
                }
            }

            // This doesn't need to be done while holding the lock.
            if (safetyCenterData != null) {
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
        public void clearAllSafetySourceData() {
            getContext().enforceCallingOrSelfPermission(
                    MANAGE_SAFETY_CENTER, "clearAllSafetySourceData");

            synchronized (mApiLock) {
                mSafetyCenterDataTracker.clear();
            }
        }

        @Override
        public void setSafetyCenterConfigOverride(
                @NonNull SafetyCenterConfig safetyCenterConfig) {
            getContext().enforceCallingOrSelfPermission(MANAGE_SAFETY_CENTER,
                    "setSafetyCenterConfigOverride");

            synchronized (mRefreshLock) {
                // TODO(b/217944317): Implement properly by overriding config in
                //  SafetyCenterConfigReader instead. This placeholder impl serves to allow this
                //  API to be merged in tm-dev, and final impl will be in tm-mainline-prod.
                for (int i = 0; i < safetyCenterConfig.getSafetySourcesGroups().size(); i++) {
                    SafetySourcesGroup group = safetyCenterConfig.getSafetySourcesGroups().get(i);
                    for (int j = 0; j < group.getSafetySources().size(); j++) {
                        SafetySource safetySource = group.getSafetySources().get(j);
                        if (safetySource.getType() != SAFETY_SOURCE_TYPE_STATIC
                                && safetySource.getBroadcastReceiverClassName() != null) {
                            mSafetyCenterRefreshManager
                                    .addAdditionalSafetySourceBroadcastReceiverComponent(
                                            new ComponentName(
                                                    safetySource.getPackageName(),
                                                    safetySource.getBroadcastReceiverClassName()
                                            ));
                        }
                    }
                }
            }
        }

        @Override
        public void clearSafetyCenterConfigOverride() {
            getContext().enforceCallingOrSelfPermission(
                    MANAGE_SAFETY_CENTER, "clearSafetyCenterConfigOverride");

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
                Log.e(TAG, String.format(
                        "Called %s with user id %s, which does not correspond to an existing user",
                        message, userId));
                return false;
            }
            // TODO(b/223132917): Check if user is enabled, running and if quiet mode is enabled?
            return true;
        }
    }
}
