// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.DIRECTORY_KEY_DELIMITER;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.FILE_KEY_DELIMITER;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.MAX_KEY_LENGTH;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.MTSV_SENTINEL;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.computeCacheKey;
import static com.google.devtools.build.lib.vfs.PathFragment.SEPARATOR_CHAR;
import static com.google.protobuf.ExtensionRegistry.getEmptyRegistry;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.concurrent.QuiescingFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.StringKey;
import com.google.devtools.build.lib.skyframe.serialization.proto.DirectoryListingInvalidationData;
import com.google.devtools.build.lib.skyframe.serialization.proto.FileInvalidationData;
import com.google.devtools.build.lib.skyframe.serialization.proto.Symlink;
import com.google.devtools.build.lib.vfs.OsPathPolicy;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Deserializes dependency information persisted by {@link FileDependencySerializer}.
 *
 * <p>Fetching a file dependency is a mostly linear asynchronous state machine that performs actions
 * then waits in an alternating manner.
 *
 * <ol>
 *   <li>Request the data for a given key.
 *   <li>{@link WaitForFileInvalidationData}.
 *   <li>Request the data for the parent directory (a recursive call).
 *   <li>{@link WaitForParent}.
 *   <li>Process any symlinks, resolving symlink parents as needed.
 *   <li>{@link WaitForSymlinkParent}.
 *   <li>Processing symlinks repeats for all the symlinks associated with an entry.
 * </ol>
 *
 * <p>A similar, but simpler state machine is used for directory listings.
 *
 * <ol>
 *   <li>Request the data for a given key.
 *   <li>{@link WaitForListingInvalidationData}.
 *   <li>Request the file data corresponding to the directory (delegating to {@link
 *       #getFileDependencies}).
 *   <li>{@link WaitForListingFileDependencies}.
 *   <li>Create and cache the {@link ListingDependencies} instance.
 * </ol>
 */
final class FileDependencyDeserializer {
  private static final OsPathPolicy OS = OsPathPolicy.getFilePathOs();

  /** Singleton representing the root file. */
  static final FileDependencies ROOT_FILE = FileDependencies.builder("").build();

  private final FingerprintValueService fingerprintValueService;

  /**
   * A cache for {@link FileDependencies}, primarily for deduplication.
   *
   * <p>The cache keys are as described at {@link FileInvalidationData}. We can potentially strip
   * the version information here, but keeping the version enables a single {@link
   * FileDependencyDeserializer} instance to be shared across disparate builds.
   *
   * <p>While in-flight, the value has type {@link FutureFileDependencies}, which is replaced by
   * {@link FileDependencies} once the computation completes.
   *
   * <p>References to {@link FileDependencies} form DAGs where certain top-level entries are
   * retained by the {@code SkyValue}s that depend on them. When all such associated {@code
   * SkyValue}s are invalidated, the dependency information becomes eligible for GC.
   */
  private final Cache<String, GetFileDependenciesResult> fileCache =
      Caffeine.newBuilder().weakValues().build();

  /**
   * A cache for {@link ListingDependencies}, primarily for deduplication.
   *
   * <p>This follows the design of {@link #fileCache} but is for directory listings.
   */
  private final Cache<String, GetListingDependenciesResult> listingCache =
      Caffeine.newBuilder().weakValues().build();

  private final Cache<PackedFingerprint, GetNestedDependenciesResult> nestedCache =
      Caffeine.newBuilder().weakValues().build();

  FileDependencyDeserializer(FingerprintValueService fingerprintValueService) {
    this.fingerprintValueService = fingerprintValueService;
  }

  sealed interface GetFileDependenciesResult permits FileDependencies, FutureFileDependencies {}

  /**
   * The main purpose of this class is to act as a {@link ListenableFuture<FileDependencies>}.
   *
   * <p>Its specific type is explicitly visible to clients to allow them to cleanly distinguish it
   * as a permitted subtype of {@link GetFileDependenciesResult}.
   */
  static final class FutureFileDependencies extends SettableFutureWithOwnership<FileDependencies>
      implements GetFileDependenciesResult {}

  /**
   * Reconstitutes the set of file dependencies associated with {@code key}.
   *
   * <p>Performs lookups and parent resolution (recursively) and symlink resolution to obtain all
   * files associated with {@code key} represented as {@link FileDependencies}.
   *
   * @param key as described in {@link FileInvalidationData}.
   * @return either an immediate {@link FileDependencies} instance or effectively a {@link
   *     ListenableFuture<FileDependencies>} instance.
   */
  GetFileDependenciesResult getFileDependencies(String key) {
    FutureFileDependencies ownedFuture;
    switch (fileCache.get(key, unused -> new FutureFileDependencies())) {
      case FileDependencies dependencies:
        return dependencies;
      case FutureFileDependencies future:
        if (!future.tryTakeOwnership()) {
          return future; // Owned by a another thread.
        }
        ownedFuture = future;
        break;
    }
    // `ownedFuture` is owned by this thread, which must complete its value.
    fetchInvalidationData(key, this::getKeyBytes, WaitForFileInvalidationData::new, ownedFuture);
    return ownedFuture;
  }

  sealed interface GetListingDependenciesResult
      permits ListingDependencies, FutureListingDependencies {}

  /**
   * The main purpose of this class is to act as a {@link ListenableFuture<ListingDependencies>}.
   *
   * <p>Its specific type is explicitly visible to clients to allow them to cleanly distinguish it
   * as a permitted subtype of {@link GetListingDependenciesResult}.
   */
  static final class FutureListingDependencies
      extends SettableFutureWithOwnership<ListingDependencies>
      implements GetListingDependenciesResult {}

  /**
   * Deserializes the resolved directory listing information associated with {@code key}.
   *
   * @param key should be as described at {@link DirectoryListingInvalidationData}.
   * @return either an immediate {@link ListingDependencies} instance or effectively a {@link
   *     ListenableFuture<ListingDependencies>} instance.
   */
  GetListingDependenciesResult getListingDependencies(String key) {
    FutureListingDependencies ownedFuture;
    switch (listingCache.get(key, unused -> new FutureListingDependencies())) {
      case ListingDependencies dependencies:
        return dependencies;
      case FutureListingDependencies future:
        if (!future.tryTakeOwnership()) {
          return future; // Owned by another thread.
        }
        ownedFuture = future;
        break;
    }
    // `ownedFuture` is owned by this thread, which must complete its value.
    fetchInvalidationData(key, this::getKeyBytes, WaitForListingInvalidationData::new, ownedFuture);
    return ownedFuture;
  }

  sealed interface GetNestedDependenciesResult
      permits NestedDependencies, FutureNestedDependencies {}

  static final class FutureNestedDependencies
      extends SettableFutureWithOwnership<NestedDependencies>
      implements GetNestedDependenciesResult {}

  /**
   * Retrieves the nested dependency information associated with {@code key}.
   *
   * <p>Like the other implementations, this can be thought of as a simple state machine. There's
   * one explicit state represented by {@link WaitForNestedNodeBytes}, which waits for the bytes
   * associated with {@code key}. There's a second implicit state that waits for child elements,
   * which may be files, listings or other nested nodes.
   *
   * @param key is a fingerprint of the byte representation described at {@link
   *     FileDependencySerializer#computeNodeBytes}.
   */
  GetNestedDependenciesResult getNestedDependencies(PackedFingerprint key) {
    FutureNestedDependencies ownedFuture;
    switch (nestedCache.get(key, unused -> new FutureNestedDependencies())) {
      case NestedDependencies dependencies:
        return dependencies;
      case FutureNestedDependencies future:
        if (!future.tryTakeOwnership()) {
          return future; // Owned by another thread.
        }
        ownedFuture = future;
        break;
    }
    // `ownedFuture` is owned by this thread, which must complete its value.
    fetchInvalidationData(key, Functions.identity(), WaitForNestedNodeBytes::new, ownedFuture);
    return ownedFuture;
  }

  // ---------- Begin FileDependencies deserialization implementation ----------

  private class WaitForFileInvalidationData implements AsyncFunction<byte[], FileDependencies> {
    private final String key;

    private WaitForFileInvalidationData(String key) {
      this.key = key;
    }

    @Override
    public ListenableFuture<FileDependencies> apply(byte[] bytes)
        throws InvalidProtocolBufferException {
      var data = FileInvalidationData.parseFrom(bytes, getEmptyRegistry());
      if (data.hasOverflowKey() && !data.getOverflowKey().equals(key)) {
        return immediateFailedFuture(
            new SerializationException(
                String.format(
                    "Non-matching overflow key. This is possible if there is a key fingerprint"
                        + " collision. Expected %s got %s",
                    key, data)));
      }

      int pathBegin = key.indexOf(FILE_KEY_DELIMITER) + 1;
      int parentDirectoryEnd = key.lastIndexOf(SEPARATOR_CHAR);

      if (parentDirectoryEnd == -1) {
        checkState(
            !data.hasParentMtsv(), "no parent directory, but had parent MTSV %s, %s", key, data);
        return resolveParent(key, data, key.substring(pathBegin), /* parentKey= */ null);
      }

      String parentDirectory = key.substring(pathBegin, parentDirectoryEnd);
      String parentKey =
          computeCacheKey(
              parentDirectory,
              data.hasParentMtsv() ? data.getParentMtsv() : MTSV_SENTINEL,
              FILE_KEY_DELIMITER);
      String basename = key.substring(parentDirectoryEnd + 1);
      return resolveParent(key, data, basename, parentKey);
    }
  }

  private ListenableFuture<FileDependencies> resolveParent(
      String key, FileInvalidationData data, String basename, @Nullable String parentKey) {
    var waitForParent = new WaitForParent(key, data, basename);

    if (parentKey == null) {
      return waitForParent.apply(/* parent= */ null);
    }

    switch (getFileDependencies(parentKey)) {
      case FileDependencies parent:
        return waitForParent.apply(parent);
      case FutureFileDependencies future:
        return Futures.transformAsync(future, waitForParent, directExecutor());
    }
  }

  private class WaitForParent implements AsyncFunction<FileDependencies, FileDependencies> {
    private final String key;
    private final FileInvalidationData data;
    private final String basename;

    private WaitForParent(String key, FileInvalidationData data, String basename) {
      this.key = key;
      this.data = data;
      this.basename = basename;
    }

    @Override
    public ListenableFuture<FileDependencies> apply(@Nullable FileDependencies parent) {
      FileDependencies.Builder builder;
      String parentDirectory;
      if (parent == null) {
        parentDirectory = null;
        builder = FileDependencies.builder(basename);
      } else {
        parentDirectory = parent.resolvedPath();
        builder =
            FileDependencies.builder(getRelative(parentDirectory, basename)).addDependency(parent);
      }
      return processSymlinks(key, data, /* symlinkIndex= */ 0, parentDirectory, builder);
    }
  }

  /**
   * Processes any symlinks that my be present in {@code data}.
   *
   * @param key the main key that this symlink belongs to
   * @param parentDirectory the real directory containing the symlink
   */
  private ListenableFuture<FileDependencies> processSymlinks(
      String key,
      FileInvalidationData data,
      int symlinkIndex,
      @Nullable String parentDirectory, // null if root-level
      FileDependencies.Builder builder) {
    if (symlinkIndex >= data.getSymlinksCount()) {
      FileDependencies dependencies = builder.build();
      // Replaces the cache value with the completed value. The future is likely to become eligible
      // for GC shortly after the return below. Clients are expected to retain the meaningful
      // top-level values.
      fileCache.put(key, dependencies);
      return immediateFuture(dependencies);
    }

    Symlink link = data.getSymlinks(symlinkIndex);
    String linkContents = link.getContents();
    checkState(
        OS.getDriveStrLength(linkContents) == 0,
        "expected symlink contents to be a relative path: %s",
        data);
    // Combines the parent directory of the link with its contents and normalizes.
    String normalizedLinkTarget = getRelativeAndNormalize(parentDirectory, linkContents);
    String normalizedLinkParent = getParentDirectory(normalizedLinkTarget);

    if (!doesSymlinkParentNeedResolution(parentDirectory, normalizedLinkParent)) {
      checkState(
          !link.hasParentMtsv(),
          "no resolution needed for data=%s, symlinkIndex=%s, parentDirectory=%s,"
              + " normalizedLinkParent=%s but symlink had parent MTSV",
          data,
          symlinkIndex,
          parentDirectory,
          normalizedLinkParent);
      // Since `normalizedLinkParent` is already a real directory, `normalizedLinkTarget` is the
      // resolved symlink path.
      if (!normalizedLinkTarget.isEmpty()) { // Avoids adding root as a resolved path.
        builder.addPath(normalizedLinkTarget);
      }
      return processSymlinks(key, data, symlinkIndex + 1, normalizedLinkParent, builder);
    }

    String linkBasename = normalizedLinkTarget.substring(normalizedLinkParent.length() + 1);

    String newParentKey =
        computeCacheKey(
            normalizedLinkParent,
            link.hasParentMtsv() ? link.getParentMtsv() : MTSV_SENTINEL,
            FILE_KEY_DELIMITER);

    var waitForSymlinkParent =
        new WaitForSymlinkParent(key, data, symlinkIndex, linkBasename, builder);

    switch (getFileDependencies(newParentKey)) {
      case FileDependencies resolvedParent:
        return waitForSymlinkParent.apply(resolvedParent);
      case FutureFileDependencies future:
        return Futures.transformAsync(future, waitForSymlinkParent, directExecutor());
    }
  }

  private class WaitForSymlinkParent implements AsyncFunction<FileDependencies, FileDependencies> {
    private final String key;
    private final FileInvalidationData data;
    private final int symlinkIndex;
    private final String linkBasename;
    private final FileDependencies.Builder builder;

    private WaitForSymlinkParent(
        String key,
        FileInvalidationData data,
        int symlinkIndex,
        String linkBasename,
        FileDependencies.Builder builder) {
      this.key = key;
      this.data = data;
      this.symlinkIndex = symlinkIndex;
      this.linkBasename = linkBasename;
      this.builder = builder;
    }

    @Override
    public ListenableFuture<FileDependencies> apply(FileDependencies parent) {
      String parentPath = parent.resolvedPath();
      builder.addPath(getRelative(parentPath, linkBasename)).addDependency(parent);
      return processSymlinks(key, data, symlinkIndex + 1, parentPath, builder);
    }
  }

  private static String getRelative(@Nullable String parentDirectory, String basename) {
    if (parentDirectory == null) {
      return basename;
    }
    return parentDirectory + SEPARATOR_CHAR + basename;
  }

  private static String getRelativeAndNormalize(
      @Nullable String parentDirectory, String linkContents) {
    int normalizationLevel = OS.needsToNormalize(linkContents);
    return OS.normalize(getRelative(parentDirectory, linkContents), normalizationLevel);
  }

  @Nullable // null if `path` is at the root level
  private static String getParentDirectory(String path) {
    int lastSeparator = path.lastIndexOf(SEPARATOR_CHAR);
    if (lastSeparator == -1) { // no separator
      return null;
    }
    return path.substring(0, lastSeparator);
  }

  /**
   * Predicate specifying when a symlink parent directory needs further resolution.
   *
   * <p>A relative path specifier in symlink contents can modify the parent directory but it does
   * not always do so. For example, the symlink could point to a file in the same directory or the
   * symlink could point to a file in an ancestor directory. In both of these cases, the parent
   * directory is already fully resolved.
   *
   * @param previousParent the parent of the actual symlink itself. Null if the parent is actually
   *     the root directory.
   * @param newParent the parent directory after combining the symlink with {@code previousParent}.
   *     Null if the result is the root directory.
   */
  private static boolean doesSymlinkParentNeedResolution(
      @Nullable String previousParent, @Nullable String newParent) {
    if (newParent == null) {
      return false; // Already root level. No parent resolution needed.
    }
    if (previousParent == null) {
      return true; // No previousParent so resolution is needed.
    }
    // `newParent` is already a resolved path if it is the same as or an ancestor of the already
    // resolved `previousParent`.
    return !previousParent.startsWith(newParent);
  }

  // ---------- Begin ListingDependencies deserialization implementation ----------

  private class WaitForListingInvalidationData
      implements AsyncFunction<byte[], ListingDependencies> {
    private final String key;

    private WaitForListingInvalidationData(String key) {
      this.key = key;
    }

    @Override
    public ListenableFuture<ListingDependencies> apply(byte[] bytes)
        throws InvalidProtocolBufferException {
      var data = DirectoryListingInvalidationData.parseFrom(bytes, getEmptyRegistry());
      if (data.hasOverflowKey() && !data.getOverflowKey().equals(key)) {
        return immediateFailedFuture(
            new SerializationException(
                String.format(
                    "Non-matching overflow key. This is possible if there is a key fingerprint"
                        + " collision. Expected %s got %s",
                    key, data)));
      }

      int pathBegin = key.indexOf(DIRECTORY_KEY_DELIMITER) + 1;

      String path = key.substring(pathBegin);
      if (path.isEmpty()) {
        return immediateFuture(createAndCacheListingDependencies(key, ROOT_FILE));
      }

      String fileKey =
          computeCacheKey(
              path, data.hasFileMtsv() ? data.getFileMtsv() : MTSV_SENTINEL, FILE_KEY_DELIMITER);
      switch (getFileDependencies(fileKey)) {
        case FileDependencies dependencies:
          return immediateFuture(createAndCacheListingDependencies(key, dependencies));
        case FutureFileDependencies future:
          return Futures.transform(
              future, new WaitForListingFileDependencies(key), directExecutor());
      }
    }
  }

  private class WaitForListingFileDependencies
      implements Function<FileDependencies, ListingDependencies> {
    private final String key;

    private WaitForListingFileDependencies(String key) {
      this.key = key;
    }

    @Override
    public ListingDependencies apply(FileDependencies dependencies) {
      return createAndCacheListingDependencies(key, dependencies);
    }
  }

  private ListingDependencies createAndCacheListingDependencies(
      String key, FileDependencies dependencies) {
    var result = new ListingDependencies(dependencies);
    listingCache.put(key, result);
    return result;
  }

  // ---------- Begin NestedDependencies deserialization implementation ----------

  private class WaitForNestedNodeBytes implements AsyncFunction<byte[], NestedDependencies> {
    private final PackedFingerprint key;

    private WaitForNestedNodeBytes(PackedFingerprint key) {
      this.key = key;
    }

    /**
     * Parses the {@code bytes} to create a {@link NestedDependencies} instance.
     *
     * <p>Refer to comment at {@link FileDependencySerializer#computeNodeBytes} for the data format.
     * Uses delegation for children, which might not be completely resolved.
     */
    @Override
    public ListenableFuture<NestedDependencies> apply(byte[] bytes) {
      try {
        var codedIn = CodedInputStream.newInstance(bytes);
        int fileCount = codedIn.readInt32();
        int listingCount = codedIn.readInt32();
        int nestedCount = codedIn.readInt32();

        var elements = new FileSystemDependencies[fileCount + listingCount + nestedCount];
        var countdown = new PendingElementCountdown(key, elements);

        for (int i = 0; i < fileCount; i++) {
          String key = codedIn.readString();
          switch (getFileDependencies(key)) {
            case FileDependencies dependencies:
              elements[i] = dependencies;
              break;
            case FutureFileDependencies future:
              countdown.registerPendingElement();
              Futures.addCallback(
                  future, new WaitingForElement(elements, i, countdown), directExecutor());
              break;
          }
        }

        int directCount = fileCount + listingCount;
        for (int i = fileCount; i < directCount; i++) {
          String key = codedIn.readString();
          switch (getListingDependencies(key)) {
            case ListingDependencies dependencies:
              elements[i] = dependencies;
              break;
            case FutureListingDependencies future:
              countdown.registerPendingElement();
              Futures.addCallback(
                  future, new WaitingForElement(elements, i, countdown), directExecutor());
              break;
          }
        }

        int total = directCount + nestedCount;
        for (int i = directCount; i < total; i++) {
          var key = PackedFingerprint.readFrom(codedIn);
          switch (getNestedDependencies(key)) {
            case NestedDependencies dependencies:
              elements[i] = dependencies;
              break;
            case FutureNestedDependencies future:
              countdown.registerPendingElement();
              Futures.addCallback(
                  future, new WaitingForElement(elements, i, countdown), directExecutor());
              break;
          }
        }
        countdown.notifyInitializationDone();
        return countdown;
      } catch (IOException e) {
        return immediateFailedFuture(e);
      }
    }
  }

  /**
   * A future that keeps track of the count of elements that still need to be set.
   *
   * <p>This future completes once all the elements are set.
   */
  private class PendingElementCountdown extends QuiescingFuture<NestedDependencies> {
    private final PackedFingerprint key;
    private final FileSystemDependencies[] elements;

    private PendingElementCountdown(PackedFingerprint key, FileSystemDependencies[] elements) {
      this.key = key;
      this.elements = elements;
    }

    private void registerPendingElement() {
      increment();
    }

    private void notifyInitializationDone() {
      decrement();
    }

    private void notifyElementSuccess() {
      decrement();
    }

    private void notifyElementFailure(Throwable e) {
      notifyException(e);
    }

    @Override
    protected NestedDependencies getValue() {
      var result = new NestedDependencies(elements);
      nestedCache.put(key, result); // Replaces the future in the cache with the completed value.
      return result;
    }
  }

  /**
   * Callback that populates the element at {@link #index} upon success.
   *
   * <p>Performs required bookkeeping for {@link PendingElementCountdown}.
   */
  private static class WaitingForElement implements FutureCallback<FileSystemDependencies> {
    private final FileSystemDependencies[] elements;
    private final int index;
    private final PendingElementCountdown countdown;

    private WaitingForElement(
        FileSystemDependencies[] elements, int index, PendingElementCountdown countdown) {
      this.elements = elements;
      this.index = index;
      this.countdown = countdown;
    }

    @Override
    public void onSuccess(FileSystemDependencies dependencies) {
      elements[index] = dependencies;
      countdown.notifyElementSuccess();
    }

    @Override
    public void onFailure(Throwable t) {
      countdown.notifyElementFailure(t);
    }
  }

  // ---------- Begin shared helpers ----------

  private <KeyT, T, FutureT extends SettableFutureWithOwnership<T>> void fetchInvalidationData(
      KeyT key,
      Function<KeyT, ? extends KeyBytesProvider> keyConverter,
      Function<KeyT, AsyncFunction<byte[], T>> waitFactory,
      FutureT ownedFuture) {
    try {
      ListenableFuture<byte[]> futureBytes;
      try {
        futureBytes = fingerprintValueService.get(keyConverter.apply(key));
      } catch (IOException e) {
        ownedFuture.failWith(e);
        return;
      }

      ownedFuture.completeWith(
          Futures.transformAsync(
              futureBytes, waitFactory.apply(key), fingerprintValueService.getExecutor()));
    } finally {
      ownedFuture.verifyComplete();
    }
  }

  private KeyBytesProvider getKeyBytes(String cacheKey) {
    if (cacheKey.length() > MAX_KEY_LENGTH) {
      return fingerprintValueService.fingerprint(cacheKey.getBytes(UTF_8));
    }
    return new StringKey(cacheKey);
  }
}
