// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.appdistribution;

import static com.google.firebase.appdistribution.internal.ReleaseIdentificationUtils.calculateApkHash;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.pm.PackageInfoCompat;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appdistribution.internal.AppDistributionReleaseInternal;
import com.google.firebase.appdistribution.internal.ReleaseIdentificationUtils;
import com.google.firebase.installations.FirebaseInstallationsApi;
import com.google.firebase.installations.InstallationTokenResult;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class CheckForNewReleaseClient {
  private static final int NEW_RELEASE_THREAD_POOL_SIZE = 4;
  private static final String TAG = "CheckForNewReleaseClient:";

  private final FirebaseApp firebaseApp;
  private final FirebaseAppDistributionTesterApiClient firebaseAppDistributionTesterApiClient;
  private final FirebaseInstallationsApi firebaseInstallationsApi;
  private static final ConcurrentMap<String, String> cachedApkHashes = new ConcurrentHashMap<>();
  private final ReleaseIdentifierStorage releaseIdentifierStorage;

  Task<AppDistributionReleaseInternal> cachedCheckForNewRelease = null;
  private final Executor checkForNewReleaseExecutor;

  CheckForNewReleaseClient(
      @NonNull FirebaseApp firebaseApp,
      @NonNull FirebaseAppDistributionTesterApiClient firebaseAppDistributionTesterApiClient,
      @NonNull FirebaseInstallationsApi firebaseInstallationsApi) {
    this.firebaseApp = firebaseApp;
    this.firebaseAppDistributionTesterApiClient = firebaseAppDistributionTesterApiClient;
    this.firebaseInstallationsApi = firebaseInstallationsApi;
    // TODO: verify if this is best way to use executorservice here
    this.checkForNewReleaseExecutor = Executors.newFixedThreadPool(NEW_RELEASE_THREAD_POOL_SIZE);
    this.releaseIdentifierStorage =
        new ReleaseIdentifierStorage(firebaseApp.getApplicationContext());
  }

  CheckForNewReleaseClient(
      @NonNull FirebaseApp firebaseApp,
      @NonNull FirebaseAppDistributionTesterApiClient firebaseAppDistributionTesterApiClient,
      @NonNull FirebaseInstallationsApi firebaseInstallationsApi,
      @NonNull ReleaseIdentifierStorage releaseIdentifierStorage,
      @NonNull Executor executor) {
    this.firebaseApp = firebaseApp;
    this.firebaseAppDistributionTesterApiClient = firebaseAppDistributionTesterApiClient;
    this.firebaseInstallationsApi = firebaseInstallationsApi;
    this.releaseIdentifierStorage = releaseIdentifierStorage;
    this.checkForNewReleaseExecutor = executor;
  }

  @NonNull
  public synchronized Task<AppDistributionReleaseInternal> checkForNewRelease() {

    if (cachedCheckForNewRelease != null && !cachedCheckForNewRelease.isComplete()) {
      return cachedCheckForNewRelease;
    }

    Task<String> installationIdTask = firebaseInstallationsApi.getId();
    // forceRefresh is false to get locally cached token if available
    Task<InstallationTokenResult> installationAuthTokenTask =
        firebaseInstallationsApi.getToken(false);

    this.cachedCheckForNewRelease =
        Tasks.whenAllSuccess(installationIdTask, installationAuthTokenTask)
            .onSuccessTask(
                checkForNewReleaseExecutor,
                tasks -> {
                  String fid = installationIdTask.getResult();
                  InstallationTokenResult installationTokenResult =
                      installationAuthTokenTask.getResult();
                  try {
                    AppDistributionReleaseInternal newRelease =
                        getNewReleaseFromClient(
                            fid,
                            firebaseApp.getOptions().getApplicationId(),
                            firebaseApp.getOptions().getApiKey(),
                            installationTokenResult.getToken());
                    return Tasks.forResult(newRelease);
                  } catch (FirebaseAppDistributionException ex) {
                    return Tasks.forException(ex);
                  }
                })
            .continueWithTask(
                checkForNewReleaseExecutor,
                task ->
                    TaskUtils.handleTaskFailure(
                        task,
                        Constants.ErrorMessages.NETWORK_ERROR,
                        FirebaseAppDistributionException.Status.NETWORK_FAILURE));

    return cachedCheckForNewRelease;
  }

  @VisibleForTesting
  AppDistributionReleaseInternal getNewReleaseFromClient(
      String fid, String appId, String apiKey, String authToken)
      throws FirebaseAppDistributionException {
    try {
      AppDistributionReleaseInternal retrievedNewRelease =
          firebaseAppDistributionTesterApiClient.fetchNewRelease(
              fid, appId, apiKey, authToken, firebaseApp.getApplicationContext());

      if (isNewerBuildVersion(retrievedNewRelease)
          || !isSameAsInstalledRelease(retrievedNewRelease)) {
        return retrievedNewRelease;
      } else {
        // Return null if retrieved new release is older or currently installed
        LogWrapper.getInstance().v(TAG + "New Release is older or is currently installed");
        return null;
      }
    } catch (NumberFormatException e) {
      LogWrapper.getInstance().e(TAG + "Error parsing buildVersion.", e);
      throw new FirebaseAppDistributionException(
          Constants.ErrorMessages.NETWORK_ERROR,
          FirebaseAppDistributionException.Status.NETWORK_FAILURE,
          e);
    }
  }

  private boolean isNewerBuildVersion(AppDistributionReleaseInternal newRelease)
      throws FirebaseAppDistributionException {
    return Long.parseLong(newRelease.getBuildVersion())
        > getInstalledAppVersionCode(firebaseApp.getApplicationContext());
  }

  @VisibleForTesting
  boolean isSameAsInstalledRelease(AppDistributionReleaseInternal newRelease) {
    if (newRelease.getBinaryType().equals(BinaryType.APK)) {
      return hasSameHashAsInstalledRelease(newRelease);
    }

    if (newRelease.getIasArtifactId() == null) {
      return false;
    }
    // AAB BinaryType
    return newRelease
        .getIasArtifactId()
        .equals(
            ReleaseIdentificationUtils.extractInternalAppSharingArtifactId(
                firebaseApp.getApplicationContext()));
  }

  private long getInstalledAppVersionCode(Context context) throws FirebaseAppDistributionException {
    PackageInfo pInfo;
    try {
      pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
    } catch (PackageManager.NameNotFoundException e) {
      LogWrapper.getInstance().e(TAG + "Unable to locate Firebase App.", e);
      throw new FirebaseAppDistributionException(
          Constants.ErrorMessages.UNKNOWN_ERROR,
          FirebaseAppDistributionException.Status.UNKNOWN,
          e);
    }
    return PackageInfoCompat.getLongVersionCode(pInfo);
  }

  @VisibleForTesting
  String extractApkHash(PackageInfo packageInfo) {
    File sourceFile = new File(packageInfo.applicationInfo.sourceDir);

    String key =
        String.format(
            Locale.ENGLISH, "%s.%d", sourceFile.getAbsolutePath(), sourceFile.lastModified());
    if (!cachedApkHashes.containsKey(key)) {
      cachedApkHashes.put(key, calculateApkHash(sourceFile));
    }
    return cachedApkHashes.get(key);
  }

  private boolean hasSameHashAsInstalledRelease(AppDistributionReleaseInternal newRelease) {
    try {
      Context context = firebaseApp.getApplicationContext();
      PackageInfo metadataPackageInfo =
          context
              .getPackageManager()
              .getPackageInfo(context.getPackageName(), PackageManager.GET_META_DATA);
      String installedReleaseApkHash = extractApkHash(metadataPackageInfo);

      if (installedReleaseApkHash.isEmpty() || newRelease.getApkHash().isEmpty()) {
        // We don't have enough information about the APK hashes. Fallback to the external codehash.
        // TODO: Consider removing this when all returned releases have the efficient ApkHash
        String externalCodeHash =
            releaseIdentifierStorage.getExternalCodeHash(installedReleaseApkHash);
        LogWrapper.getInstance().v(TAG + "Defaulting to external codehash " + externalCodeHash);
        return externalCodeHash != null && externalCodeHash.equals(newRelease.getCodeHash());
      }
      // If the hash of the zipped APK for the retrieved newRelease is equal to the stored hash
      // of the installed release, then they are the same release.
      return installedReleaseApkHash.equals(newRelease.getApkHash());
    } catch (PackageManager.NameNotFoundException e) {
      LogWrapper.getInstance().e(TAG + "Unable to locate App.", e);
      return false;
    }
  }
}
