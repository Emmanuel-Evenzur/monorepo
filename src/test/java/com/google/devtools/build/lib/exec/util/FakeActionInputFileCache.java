// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FilesetOutputTree;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** A fake implementation of the {@link InputMetadataProvider} interface. */
public final class FakeActionInputFileCache implements InputMetadataProvider {
  private final Map<ActionInput, FileArtifactValue> inputs = new HashMap<>();
  private final Map<ActionInput, TreeArtifactValue> treeArtifacts = new HashMap<>();
  private final Map<ActionInput, RunfilesArtifactValue> runfilesInputs = new HashMap<>();
  private final Map<Artifact, FilesetOutputTree> filesets = new HashMap<>();
  private final List<RunfilesTree> runfilesTrees = new ArrayList<>();

  public FakeActionInputFileCache() {}

  public void put(ActionInput artifact, FileArtifactValue metadata) {
    inputs.put(artifact, metadata);
  }

  public void putTreeArtifact(ActionInput actionInput, TreeArtifactValue treeArtifactValue) {
    treeArtifacts.put(actionInput, treeArtifactValue);
  }

  public void putRunfilesTree(ActionInput runfilesTreeArtifact, RunfilesTree runfilesTree) {
    RunfilesArtifactValue runfilesArtifactValue =
        new RunfilesArtifactValue(
            runfilesTree,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of());
    runfilesInputs.put(runfilesTreeArtifact, runfilesArtifactValue);
    runfilesTrees.add(runfilesTree);
  }

  public void putFileset(Artifact fileset, FilesetOutputTree filesetOutputTree) {
    filesets.put(fileset, filesetOutputTree);
  }

  @Override
  @Nullable
  public FileArtifactValue getInputMetadataChecked(ActionInput input) throws IOException {
    return inputs.get(input);
  }

  @Nullable
  @Override
  public TreeArtifactValue getTreeMetadata(ActionInput actionInput) {
    return treeArtifacts.get(actionInput);
  }

  @Override
  @Nullable
  public FilesetOutputTree getFileset(ActionInput input) {
    return filesets.get(input);
  }

  @Override
  public ImmutableMap<Artifact, FilesetOutputTree> getFilesets() {
    return ImmutableMap.copyOf(filesets);
  }

  @Override
  @Nullable
  public RunfilesArtifactValue getRunfilesMetadata(ActionInput input) {
    return runfilesInputs.get(input);
  }

  @Override
  public ImmutableList<RunfilesTree> getRunfilesTrees() {
    return ImmutableList.copyOf(runfilesTrees);
  }

  @Override
  @Nullable
  public ActionInput getInput(String execPath) {
    return null;
  }

  public ImmutableMap<ActionInput, TreeArtifactValue> getAllTreeArtifacts() {
    return ImmutableMap.copyOf(treeArtifacts);
  }
}
