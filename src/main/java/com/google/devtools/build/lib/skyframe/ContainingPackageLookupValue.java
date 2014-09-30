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

import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/**
 * A value that represents the result of looking for the existence of a package that owns a
 * specific directory path. Compare with {@link PackageLookupValue}, which deals with existence of
 * a specific package.
 */
abstract class ContainingPackageLookupValue implements SkyValue {
  /** Returns whether there is a containing package. */
  abstract boolean hasContainingPackage();

  /** If there is a containing package, returns its name. */
  abstract PathFragment getContainingPackageName();

  /** If there is a containing package, returns its package root */
  abstract Path getContainingPackageRoot();

  static SkyKey key(PathFragment directory) {
    return new SkyKey(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, directory);
  }

  static ContainingPackageLookupValue noContainingPackage() {
    return NoContainingPackage.INSTANCE;
  }

  static ContainingPackageLookupValue withContainingPackage(PathFragment pkgName, Path root) {
    return new ContainingPackage(pkgName, root);
  }

  private static class NoContainingPackage extends ContainingPackageLookupValue {
    private static final NoContainingPackage INSTANCE = new NoContainingPackage();

    @Override
    public boolean hasContainingPackage() {
      return false;
    }

    @Override
    public PathFragment getContainingPackageName() {
      throw new IllegalStateException();
    }

    @Override
    public Path getContainingPackageRoot() {
      throw new IllegalStateException();
    }
  }

  private static class ContainingPackage extends ContainingPackageLookupValue {
    private final PathFragment containingPackage;
    private final Path containingPackageRoot;

    private ContainingPackage(PathFragment containingPackage, Path containingPackageRoot) {
      this.containingPackage = containingPackage;
      this.containingPackageRoot = containingPackageRoot;
    }

    @Override
    public boolean hasContainingPackage() {
      return true;
    }

    @Override
    public PathFragment getContainingPackageName() {
      return containingPackage;
    }

    @Override
    public Path getContainingPackageRoot() {
      return containingPackageRoot;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ContainingPackage)) {
        return false;
      }
      ContainingPackage other = (ContainingPackage) obj;
      return containingPackage.equals(other.containingPackage)
          && containingPackageRoot.equals(other.containingPackageRoot);
    }

    @Override
    public int hashCode() {
      return containingPackage.hashCode();
    }
  }
}
