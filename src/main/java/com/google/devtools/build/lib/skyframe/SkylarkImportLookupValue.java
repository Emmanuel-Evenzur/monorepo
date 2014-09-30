// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.syntax.SkylarkEnvironment;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/**
 * A value that represents a Skylark import lookup result.
 */
public class SkylarkImportLookupValue implements SkyValue {

  private final SkylarkEnvironment importedEnvironment;

  public SkylarkImportLookupValue(SkylarkEnvironment importedEnvironment) {
    this.importedEnvironment = Preconditions.checkNotNull(importedEnvironment);
  }

  /**
   * Returns the imported SkylarkEnvironment.
   */
  public SkylarkEnvironment getImportedEnvironment() {
    return importedEnvironment;
  }

  static SkyKey key(PathFragment fileToImport) {
    return new SkyKey(SkyFunctions.SKYLARK_IMPORTS_LOOKUP, fileToImport);
  }
}
