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

import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;

/**
 * An interface providing the functionalities used for analysis caching serialization and
 * deserialization.
 */
public interface RemoteAnalysisCachingDependenciesProvider {

  /** Returns true if the {@link PackageIdentifier} is in the set of active directories. */
  boolean withinActiveDirectories(PackageIdentifier pkg);

  /**
   * Returns the {@link ObjectCodecs} supplier for remote analysis caching.
   *
   * <p>Calling this can be an expensive process as the codec registry will be initialized.
   */
  ObjectCodecs getObjectCodecs();

  /** Returns the {@link FingerprintValueService} implementation. */
  FingerprintValueService getFingerprintValueService();
}
