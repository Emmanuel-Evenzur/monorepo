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
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.skyframe.FileStateValue.Type;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A value that corresponds to a file (or directory or symlink or non-existent file), fully
 * accounting for symlinks (e.g. proper dependencies on ancestor symlinks so as to be incrementally
 * correct). Anything in Skyframe that cares about the fully resolved path of a file (e.g. anything
 * that cares about the contents of a file) should have a dependency on the corresponding
 * {@link FileValue}.
 *
 * <p>
 * Note that the existence of a file value does not imply that the file exists on the filesystem.
 * File values for missing files will be created on purpose in order to facilitate incremental
 * builds in the case those files have reappeared.
 *
 * <p>
 * This class contains the relevant metadata for a file, although not the contents. Note that
 * since a FileValue doesn't store its corresponding SkyKey, it's possible for the FileValues for
 * two different paths to be the same.
 */
@Immutable
@ThreadSafe
public abstract class FileValue implements SkyValue {

  boolean exists() {
    return realFileStateValue().getType() != Type.NONEXISTENT;
  }

  boolean isSymlink() {
    return false;
  }

  /**
   * Returns true if this value corresponds to a file or symlink to an existing file. If so, its
   * parent directory is guaranteed to exist.
   */
  public boolean isFile() {
    return realFileStateValue().getType() == Type.FILE;
  }

  /**
   * Returns true if the file is a directory or a symlink to an existing directory. If so, its
   * parent directory is guaranteed to exist.
   */
  boolean isDirectory() {
    return realFileStateValue().getType() == Type.DIRECTORY;
  }

  /**
   * Returns the real rooted path of the file, taking ancestor symlinks into account. For example,
   * the rooted path ['root']/['a/b'] is really ['root']/['c/b'] if 'a' is a symlink to 'b'. Note
   * that ancestor symlinks outside the root boundary are not taken into consideration.
   */
  abstract RootedPath realRootedPath();

  abstract FileStateValue realFileStateValue();

  long getSize() {
    Preconditions.checkState(isFile(), this);
    return realFileStateValue().getSize();
  }

  @Nullable
  byte[] getDigest() {
    Preconditions.checkState(isFile(), this);
    return realFileStateValue().getDigest();
  }

  /**
   * Returns a key for building a file value for the given root-relative path.
   */
  @ThreadSafe
  public static SkyKey key(RootedPath rootedPath) {
    return new SkyKey(SkyFunctions.FILE, rootedPath);
  }

  @ThreadSafe
  public static SkyKey key(Artifact artifact) {
    Path root = artifact.getRoot().getPath();
    return key(RootedPath.toRootedPath(root, artifact.getPath()));
  }

  /**
   * Only intended to be used by {@link FileFunction}. Should not be used for symlink cycles.
   */
  static FileValue value(RootedPath rootedPath, FileStateValue fileStateValue,
      RootedPath realRootedPath, FileStateValue realFileStateValue) {
    if (rootedPath.equals(realRootedPath)) {
      Preconditions.checkState(fileStateValue.getType() != FileStateValue.Type.SYMLINK,
          "rootedPath: %s, fileStateValue: %s, realRootedPath: %s, realFileStateValue: %s",
          rootedPath, fileStateValue, realRootedPath, realFileStateValue);
      return new RegularFileValue(rootedPath, fileStateValue);
    } else {
      if (fileStateValue.getType() == FileStateValue.Type.SYMLINK) {
        return new SymlinkFileValue(realRootedPath, realFileStateValue);
      } else {
        return new DifferentRealPathFileValue(realRootedPath, realFileStateValue);
      }
    }
  }

  /**
   * Implementation of {@link FileValue} for files whose fully resolved path is the same as the
   * requested path. For example, this is the case for the path "foo/bar/baz" if neither 'foo' nor
   * 'foo/bar' nor 'foo/bar/baz' are symlinks.
   */
  private static final class RegularFileValue extends FileValue {

    private final RootedPath rootedPath;
    private final FileStateValue fileStateValue;

    private RegularFileValue(RootedPath rootedPath, FileStateValue fileState) {
      this.rootedPath = rootedPath;
      this.fileStateValue = fileState;
    }

    @Override
    RootedPath realRootedPath() {
      return rootedPath;
    }

    @Override
    FileStateValue realFileStateValue() {
      return fileStateValue;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (obj.getClass() != RegularFileValue.class) {
        return false;
      }
      RegularFileValue other = (RegularFileValue) obj;
      return rootedPath.equals(other.rootedPath) && fileStateValue.equals(other.fileStateValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(rootedPath, fileStateValue);
    }

    @Override
    public String toString() {
      return rootedPath + ", " + fileStateValue;
    }
  }

  /**
   * Base class for {@link FileValue}s for files whose fully resolved path is different than the
   * requested path. For example, this is the case for the path "foo/bar/baz" if at least one of
   * 'foo', 'foo/bar', or 'foo/bar/baz' is a symlink.
   */
  private static class DifferentRealPathFileValue extends FileValue {

    protected final RootedPath realRootedPath;
    protected final FileStateValue realFileStateValue;

    private DifferentRealPathFileValue(RootedPath realRootedPath,
        FileStateValue realFileStateValue) {
      this.realRootedPath = realRootedPath;
      this.realFileStateValue = realFileStateValue;
    }

    @Override
    RootedPath realRootedPath() {
      return realRootedPath;
    }

    @Override
    FileStateValue realFileStateValue() {
      return realFileStateValue;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (obj.getClass() != DifferentRealPathFileValue.class) {
        return false;
      }
      DifferentRealPathFileValue other = (DifferentRealPathFileValue) obj;
      return realRootedPath.equals(other.realRootedPath)
          && realFileStateValue.equals(other.realFileStateValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(realRootedPath, realFileStateValue);
    }

    @Override
    public String toString() {
      return realRootedPath + ", " + realFileStateValue + " (symlink ancestor)";
    }
  }

  /** Implementation of {@link FileValue} for files that are symlinks. */
  private static final class SymlinkFileValue extends DifferentRealPathFileValue {

    private SymlinkFileValue(RootedPath realRootedPath, FileStateValue realFileState) {
      super(realRootedPath, realFileState);
    }

    @Override
    boolean isSymlink() {
      return true;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null) {
        return false;
      }
      if (obj.getClass() != SymlinkFileValue.class) {
        return false;
      }
      SymlinkFileValue other = (SymlinkFileValue) obj;
      return realRootedPath.equals(other.realRootedPath)
          && realFileStateValue.equals(other.realFileStateValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(realRootedPath, realFileStateValue, Boolean.TRUE);
    }

    @Override
    public String toString() {
      return "symlink (realpath=" + realRootedPath + "), " + realFileStateValue;
    }
  }
}
