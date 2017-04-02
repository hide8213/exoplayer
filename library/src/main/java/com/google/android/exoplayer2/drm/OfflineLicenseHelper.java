/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.google.android.exoplayer2.drm;

import android.media.MediaDrm;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Pair;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager.EventListener;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager.Mode;
import com.google.android.exoplayer2.drm.DrmSession.DrmSessionException;
import com.google.android.exoplayer2.source.dash.DashUtil;
import com.google.android.exoplayer2.source.dash.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.Period;
import com.google.android.exoplayer2.source.dash.manifest.Representation;
import com.google.android.exoplayer2.upstream.FileDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource.Factory;
import com.google.android.exoplayer2.util.Assertions;
import java.io.IOException;
import java.util.HashMap;

/**
 * Helper class to download, renew and release offline licenses. It utilizes {@link
 * DefaultDrmSessionManager}.
 */
public final class OfflineLicenseHelper<T extends ExoMediaCrypto> {

  private final ConditionVariable conditionVariable;
  private final DefaultDrmSessionManager<T> drmSessionManager;
  private final HandlerThread handlerThread;

  /**
   * Instantiates a new instance which uses Widevine CDM. Call {@link #releaseResources()} when
   * you're done with the helper instance.
   *
   * @param licenseUrl The default license URL.
   * @param httpDataSourceFactory A factory from which to obtain {@link HttpDataSource} instances.
   * @return A new instance which uses Widevine CDM.
   * @throws UnsupportedDrmException If the Widevine DRM scheme is unsupported or cannot be
   *     instantiated.
   */
  public static OfflineLicenseHelper<FrameworkMediaCrypto> newWidevineInstance(
      String licenseUrl, Factory httpDataSourceFactory) throws UnsupportedDrmException {
    if(httpDataSourceFactory instanceof HttpDataSource.Factory){
      return newWidevineInstance(
              new HttpMediaDrmCallback(licenseUrl, httpDataSourceFactory), null);
    }else {
      return newWidevineInstance(
              new HttpMediaDrmCallback(licenseUrl, httpDataSourceFactory), null);
    }
  }

  /**
   * Instantiates a new instance which uses Widevine CDM. Call {@link #releaseResources()} when
   * you're done with the helper instance.
   *
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @return A new instance which uses Widevine CDM.
   * @throws UnsupportedDrmException If the Widevine DRM scheme is unsupported or cannot be
   *     instantiated.
   * @see DefaultDrmSessionManager#DefaultDrmSessionManager(java.util.UUID, ExoMediaDrm,
   *     MediaDrmCallback, HashMap, Handler, EventListener)
   */
  public static OfflineLicenseHelper<FrameworkMediaCrypto> newWidevineInstance(
      MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters)
      throws UnsupportedDrmException {
    return new OfflineLicenseHelper<>(FrameworkMediaDrm.newInstance(C.WIDEVINE_UUID), callback,
        optionalKeyRequestParameters);
  }

  /**
   * Constructs an instance. Call {@link #releaseResources()} when you're done with it.
   *
   * @param mediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link MediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @see DefaultDrmSessionManager#DefaultDrmSessionManager(java.util.UUID, ExoMediaDrm,
   *     MediaDrmCallback, HashMap, Handler, EventListener)
   */
  public OfflineLicenseHelper(ExoMediaDrm<T> mediaDrm, MediaDrmCallback callback,
      HashMap<String, String> optionalKeyRequestParameters) {
    handlerThread = new HandlerThread("OfflineLicenseHelper");
    handlerThread.start();

    conditionVariable = new ConditionVariable();
    EventListener eventListener = new EventListener() {
      @Override
      public void onDrmKeysLoaded() {
        conditionVariable.open();
      }

      @Override
      public void onDrmSessionManagerError(Exception e) {
        conditionVariable.open();
      }

      @Override
      public void onDrmKeysRestored() {
        conditionVariable.open();
      }

      @Override
      public void onDrmKeysRemoved() {
        conditionVariable.open();
      }
    };
    drmSessionManager = new DefaultDrmSessionManager<>(C.WIDEVINE_UUID, mediaDrm, callback,
        optionalKeyRequestParameters, new Handler(handlerThread.getLooper()), eventListener);
  }

  /** Releases the used resources. */
  public void releaseResources() {
    handlerThread.quit();
  }

  /**
   * Downloads an offline license.
   *
   * @param dataSource The {@link HttpDataSource} to be used for download.
   * @param manifestUriString The URI of the manifest to be read.
   * @return The downloaded offline license key set id.
   * @throws IOException If an error occurs reading data from the stream.
   * @throws InterruptedException If the thread has been interrupted.
   * @throws DrmSessionException Thrown when there is an error during DRM session.
   */
  public byte[] download(HttpDataSource dataSource, String manifestUriString)
      throws IOException, InterruptedException, DrmSessionException {
    return download(dataSource, DashUtil.loadManifest(dataSource, manifestUriString));
  }

  public byte[] download(FileDataSource dataSource, String manifestUriString)
          throws IOException, InterruptedException, DrmSessionException {
    return download(dataSource, DashUtil.loadManifest(dataSource, manifestUriString));
  }

  /**
   * Downloads an offline license.
   *
   * @param dataSource The {@link HttpDataSource} to be used for download.
   * @param dashManifest The {@link DashManifest} of the DASH content.
   * @return The downloaded offline license key set id.
   * @throws IOException If an error occurs reading data from the stream.
   * @throws InterruptedException If the thread has been interrupted.
   * @throws DrmSessionException Thrown when there is an error during DRM session.
   */
  public byte[] download(HttpDataSource dataSource, DashManifest dashManifest)
      throws IOException, InterruptedException, DrmSessionException {
    // Get DrmInitData
    // Prefer drmInitData obtained from the manifest over drmInitData obtained from the stream,
    // as per DASH IF Interoperability Recommendations V3.0, 7.5.3.
    if (dashManifest.getPeriodCount() < 1) {
      return null;
    }
    Period period = dashManifest.getPeriod(0);
    int adaptationSetIndex = period.getAdaptationSetIndex(C.TRACK_TYPE_VIDEO);
    if (adaptationSetIndex == C.INDEX_UNSET) {
      adaptationSetIndex = period.getAdaptationSetIndex(C.TRACK_TYPE_AUDIO);
      if (adaptationSetIndex == C.INDEX_UNSET) {
        return null;
      }
    }
    AdaptationSet adaptationSet = period.adaptationSets.get(adaptationSetIndex);
    if (adaptationSet.representations.isEmpty()) {
      return null;
    }
    Representation representation = adaptationSet.representations.get(0);
    DrmInitData drmInitData = representation.format.drmInitData;
    if (drmInitData == null) {
      Format sampleFormat = DashUtil.loadSampleFormat(dataSource, representation);
      if (sampleFormat != null) {
        drmInitData = sampleFormat.drmInitData;
      }
      if (drmInitData == null) {
        return null;
      }
    }
    blockingKeyRequest(DefaultDrmSessionManager.MODE_DOWNLOAD, null, drmInitData);
    return drmSessionManager.getOfflineLicenseKeySetId();
  }

  public void playback(FileDataSource dataSource, String manifestUriString, byte[] offlineLicenseKeySetId)
          throws IOException, InterruptedException, DrmSessionException {
    playback(dataSource, DashUtil.loadManifest(dataSource, manifestUriString), offlineLicenseKeySetId);
  }

  public void playback(FileDataSource dataSource, DashManifest dashManifest, byte[] offlineLicenseKeySetId)
          throws IOException, InterruptedException, DrmSessionException {
    // Get DrmInitData
    // Prefer drmInitData obtained from the manifest over drmInitData obtained from the stream,
    // as per DASH IF Interoperability Recommendations V3.0, 7.5.3.
    if (dashManifest.getPeriodCount() < 1) {
      return;
    }
    Period period = dashManifest.getPeriod(0);
    int adaptationSetIndex = period.getAdaptationSetIndex(C.TRACK_TYPE_VIDEO);
    if (adaptationSetIndex == C.INDEX_UNSET) {
      adaptationSetIndex = period.getAdaptationSetIndex(C.TRACK_TYPE_AUDIO);
      if (adaptationSetIndex == C.INDEX_UNSET) {
        return;
      }
    }
    AdaptationSet adaptationSet = period.adaptationSets.get(adaptationSetIndex);
    if (adaptationSet.representations.isEmpty()) {
      return;
    }
    Representation representation = adaptationSet.representations.get(0);
    DrmInitData drmInitData = representation.format.drmInitData;
    if (drmInitData == null) {
      Format sampleFormat = DashUtil.loadSampleFormat(dataSource, representation);
      if (sampleFormat != null) {
        drmInitData = sampleFormat.drmInitData;
      }
      if (drmInitData == null) {
        return;
      }
    }
    drmSessionManager.setOfflineLicenseKeySetId(offlineLicenseKeySetId);
    DrmSession<T> session = drmSessionManager.acquireSession(handlerThread.getLooper(),
            drmInitData);
    drmSessionManager.releaseSession(session);
  }

  public byte[] download(FileDataSource dataSource, DashManifest dashManifest)
          throws IOException, InterruptedException, DrmSessionException {
    // Get DrmInitData
    // Prefer drmInitData obtained from the manifest over drmInitData obtained from the stream,
    // as per DASH IF Interoperability Recommendations V3.0, 7.5.3.
    if (dashManifest.getPeriodCount() < 1) {
      return null;
    }
    Period period = dashManifest.getPeriod(0);
    int adaptationSetIndex = period.getAdaptationSetIndex(C.TRACK_TYPE_VIDEO);
    if (adaptationSetIndex == C.INDEX_UNSET) {
      adaptationSetIndex = period.getAdaptationSetIndex(C.TRACK_TYPE_AUDIO);
      if (adaptationSetIndex == C.INDEX_UNSET) {
        return null;
      }
    }
    AdaptationSet adaptationSet = period.adaptationSets.get(adaptationSetIndex);
    if (adaptationSet.representations.isEmpty()) {
      return null;
    }
    Representation representation = adaptationSet.representations.get(0);
    DrmInitData drmInitData = representation.format.drmInitData;
    if (drmInitData == null) {
      Format sampleFormat = DashUtil.loadSampleFormat(dataSource, representation);
      if (sampleFormat != null) {
        drmInitData = sampleFormat.drmInitData;
      }
      if (drmInitData == null) {
        return null;
      }
    }
    blockingKeyRequest(DefaultDrmSessionManager.MODE_PLAYBACK, null, drmInitData);
    return drmSessionManager.getOfflineLicenseKeySetId();
  }

  /**
   * Renews an offline license.
   *
   * @param offlineLicenseKeySetId The key set id of the license to be renewed.
   * @return Renewed offline license key set id.
   * @throws DrmSessionException Thrown when there is an error during DRM session.
   */
  public byte[] renew(byte[] offlineLicenseKeySetId) throws DrmSessionException {
    Assertions.checkNotNull(offlineLicenseKeySetId);
    blockingKeyRequest(DefaultDrmSessionManager.MODE_DOWNLOAD, offlineLicenseKeySetId, null);
    return drmSessionManager.getOfflineLicenseKeySetId();
  }

  /**
   * Releases an offline license.
   *
   * @param offlineLicenseKeySetId The key set id of the license to be released.
   * @throws DrmSessionException Thrown when there is an error during DRM session.
   */
  public void release(byte[] offlineLicenseKeySetId) throws DrmSessionException {
    Assertions.checkNotNull(offlineLicenseKeySetId);
    blockingKeyRequest(DefaultDrmSessionManager.MODE_RELEASE, offlineLicenseKeySetId, null);
  }

  /**
   * Returns license and playback durations remaining in seconds of the given offline license.
   *
   * @param offlineLicenseKeySetId The key set id of the license.
   */
  public Pair<Long, Long> getLicenseDurationRemainingSec(byte[] offlineLicenseKeySetId)
      throws DrmSessionException {
    Assertions.checkNotNull(offlineLicenseKeySetId);
    DrmSession<T> session = openBlockingKeyRequest(DefaultDrmSessionManager.MODE_QUERY,
        offlineLicenseKeySetId, null);
    Pair<Long, Long> licenseDurationRemainingSec =
        WidevineUtil.getLicenseDurationRemainingSec(drmSessionManager);
    drmSessionManager.releaseSession(session);
    return licenseDurationRemainingSec;
  }

  private void blockingKeyRequest(@Mode int licenseMode, byte[] offlineLicenseKeySetId,
      DrmInitData drmInitData) throws DrmSessionException {
    DrmSession<T> session = openBlockingKeyRequest(licenseMode, offlineLicenseKeySetId,
        drmInitData);
    DrmSessionException error = session.getError();
    if (error != null) {
      throw error;
    }
    drmSessionManager.releaseSession(session);
  }

  private DrmSession<T> openBlockingKeyRequest(@Mode int licenseMode, byte[] offlineLicenseKeySetId,
      DrmInitData drmInitData) {
    drmSessionManager.setMode(licenseMode, offlineLicenseKeySetId);
    conditionVariable.close();
    DrmSession<T> session = drmSessionManager.acquireSession(handlerThread.getLooper(),
        drmInitData);
    // Block current thread until key loading is finished
    conditionVariable.block();
    return session;
  }

  private DrmSession<T> openOfflineRequest(@Mode int licenseMode, byte[] offlineLicenseKeySetId,
                                               DrmInitData drmInitData) {
    drmSessionManager.setMode(licenseMode, offlineLicenseKeySetId);
    conditionVariable.close();
    DrmSession<T> session = drmSessionManager.acquireSession(handlerThread.getLooper(),
            drmInitData);
    // Block current thread until key loading is finished
    conditionVariable.block();
    return session;
  }

}
