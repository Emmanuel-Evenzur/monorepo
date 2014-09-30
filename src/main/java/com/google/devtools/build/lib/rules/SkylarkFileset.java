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
package com.google.devtools.build.lib.rules;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkBuiltin;
import com.google.devtools.build.lib.syntax.SkylarkBuiltin.Param;
import com.google.devtools.build.lib.syntax.SkylarkFunction;
import com.google.devtools.build.lib.syntax.SkylarkFunction.SimpleSkylarkFunction;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkModule;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.Map;

/**
 * A wrapper class for NestedSet of Artifacts in Skylark to ensure type safety.
 */
@SkylarkModule(name = "files", namespace = true,
    doc = "A helper class to extract path from files.")
public final class SkylarkFileset {

  @SkylarkBuiltin(name = "join_exec_paths",
      objectType = SkylarkFileset.class, returnType = String.class,
      doc = "Returns the joint execution paths of these files using the delimiter.",
      mandatoryParams = {
      @Param(name = "delimiter", type = String.class,
          doc = "The delimiter string to join the files on."),
      @Param(name = "files", type = SkylarkList.class,
          doc = "The list of files to join.")})
  private static SkylarkFunction joinExecPaths = new SimpleSkylarkFunction("join_exec_paths") {

    @Override
    protected Object call(Map<String, Object> params, Location loc)
        throws EvalException, ConversionException {
      return Artifact.joinExecPaths(
          (String) params.get("delimiter"),
          castList(params.get("files"), Artifact.class, "files")); 
    }
  };

  @SkylarkBuiltin(name = "work_dir",
      objectType = SkylarkFileset.class, returnType = String.class,
      doc = "Returns a working directory path for the file using suffix for the directory name.",
      mandatoryParams = {
      @Param(name = "root", type = Root.class, doc = "The root of the work dir."),
      @Param(name = "file", type = Artifact.class,
          doc = "The file whose name is used to create the work dir."),
      @Param(name = "suffix", type = String.class,
          doc = "The suffix to be used instead of the extension of the file.")})
  private static SkylarkFunction workDir = new SimpleSkylarkFunction("work_dir") {

    @Override
    protected Object call(Map<String, Object> params, Location loc)
        throws EvalException, ConversionException {
      PathFragment path = ((Artifact) params.get("file")).getRootRelativePath();
      String basename = FileSystemUtils.removeExtension(path.getBaseName())
          + (String) params.get("suffix");
      path = path.replaceName(basename);
      return ((Root) params.get("root")).getExecPath().getRelative(path).toString(); 
    }
  };
}
