// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.ResourceSetOrBuilder;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CoreOptions.OutputPathsMode;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Action that represents a linking step. */
@ThreadCompatible
public final class CppLinkAction extends SpawnAction {
  /**
   * An abstraction for creating intermediate and output artifacts for C++ linking.
   *
   * <p>This is unfortunately necessary, because most of the time, these artifacts are well-behaved
   * ones sitting under a package directory, but nativedeps link actions can be shared. In order to
   * avoid creating every artifact here with {@code getShareableArtifact()}, we abstract the
   * artifact creation away.
   */
  public interface LinkArtifactFactory {
    /** Create an artifact at the specified root-relative path in the bin directory. */
    Artifact create(
        ActionConstructionContext actionConstructionContext,
        RepositoryName repositoryName,
        BuildConfigurationValue configuration,
        PathFragment rootRelativePath);

    /** Create a tree artifact at the specified root-relative path in the bin directory. */
    SpecialArtifact createTreeArtifact(
        ActionConstructionContext actionConstructionContext,
        RepositoryName repositoryName,
        BuildConfigurationValue configuration,
        PathFragment rootRelativePath);
  }

  /**
   * An implementation of {@link LinkArtifactFactory} that can only create artifacts in the package
   * directory.
   */
  public static final LinkArtifactFactory DEFAULT_ARTIFACT_FACTORY =
      new LinkArtifactFactory() {
        @Override
        public Artifact create(
            ActionConstructionContext actionConstructionContext,
            RepositoryName repositoryName,
            BuildConfigurationValue configuration,
            PathFragment rootRelativePath) {
          return actionConstructionContext.getDerivedArtifact(
              rootRelativePath, configuration.getBinDirectory(repositoryName));
        }

        @Override
        public SpecialArtifact createTreeArtifact(
            ActionConstructionContext actionConstructionContext,
            RepositoryName repositoryName,
            BuildConfigurationValue configuration,
            PathFragment rootRelativePath) {
          return actionConstructionContext.getTreeArtifact(
              rootRelativePath, configuration.getBinDirectory(repositoryName));
        }
      };

  /**
   * An implementation of {@link LinkArtifactFactory} that can create artifacts anywhere.
   *
   * <p>Necessary when the LTO backend actions of libraries should be shareable, and thus cannot be
   * under the package directory.
   *
   * <p>Necessary because the actions of nativedeps libraries should be shareable, and thus cannot
   * be under the package directory.
   */
  public static final LinkArtifactFactory SHAREABLE_LINK_ARTIFACT_FACTORY =
      new LinkArtifactFactory() {
        @Override
        public Artifact create(
            ActionConstructionContext actionConstructionContext,
            RepositoryName repositoryName,
            BuildConfigurationValue configuration,
            PathFragment rootRelativePath) {
          return actionConstructionContext.getShareableArtifact(
              rootRelativePath, configuration.getBinDirectory(repositoryName));
        }

        @Override
        public SpecialArtifact createTreeArtifact(
            ActionConstructionContext actionConstructionContext,
            RepositoryName repositoryName,
            BuildConfigurationValue configuration,
            PathFragment rootRelativePath) {
          return actionConstructionContext
              .getAnalysisEnvironment()
              .getTreeArtifact(rootRelativePath, configuration.getBinDirectory(repositoryName));
        }
      };

  private static final String LINK_GUID = "58ec78bd-1176-4e36-8143-439f656b181d";

  private static final LinkResourceSetBuilder resourceSetBuilder = new LinkResourceSetBuilder();
  private final ImmutableMap<String, String> toolchainEnv;
  private final LinkCommandLine linkCommandLine;

  /**
   * Use {@link CppLinkActionBuilder} to create instances of this class. Also see there for the
   * documentation of all parameters.
   *
   * <p>This constructor is intentionally private and is only to be called from {@link
   * CppLinkActionBuilder#build()}.
   */
  CppLinkAction(
      ActionOwner owner,
      String mnemonic,
      NestedSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs,
      boolean isLtoIndexing,
      LinkCommandLine linkCommandLine,
      ActionEnvironment env,
      ImmutableMap<String, String> toolchainEnv,
      ImmutableMap<String, String> executionRequirements)
      throws RuleErrorException {
    super(
        owner,
        /* tools= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        inputs,
        outputs,
        /* resourceSetOrBuilder= */ resourceSetBuilder,
        /* commandLines= */ linkCommandLine.getCommandLines(),
        /* env= */ env,
        /* executionInfo= */ executionRequirements,
        /* progressMessage= */ (isLtoIndexing ? "LTO indexing %{output}" : "Linking %{output}"),
        /* mnemonic= */ getMnemonic(mnemonic, isLtoIndexing),
        /* outputPathsMode= */ OutputPathsMode.OFF);

    this.linkCommandLine = linkCommandLine;
    this.toolchainEnv = toolchainEnv;
  }

  @Override
  public ImmutableMap<String, String> getEffectiveEnvironment(Map<String, String> clientEnv) {
    LinkedHashMap<String, String> result =
        Maps.newLinkedHashMapWithExpectedSize(getEnvironment().estimatedSize());
    getEnvironment().resolve(result, clientEnv);

    result.putAll(toolchainEnv);

    if (!getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_DARWIN)) {
      // This prevents gcc from writing the unpredictable (and often irrelevant)
      // value of getcwd() into the debug info.
      result.put("PWD", "/proc/self/cwd");
    }
    return ImmutableMap.copyOf(result);
  }

  @VisibleForTesting
  public LinkCommandLine getLinkCommandLineForTesting() {
    return linkCommandLine;
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp)
      throws CommandLineExpansionException, InterruptedException {
    fp.addString(LINK_GUID);
    super.computeKey(actionKeyContext, artifactExpander, fp);
    fp.addStringMap(toolchainEnv);
  }

  static String getMnemonic(String mnemonic, boolean isLtoIndexing) {
    if (mnemonic == null) {
      return isLtoIndexing ? "CppLTOIndexing" : "CppLink";
    }
    return mnemonic;
  }

  /** Estimates resource consumption when this action is executed locally. */
  @VisibleForTesting
  static class LinkResourceSetBuilder implements ResourceSetOrBuilder {
    @Override
    public ResourceSet buildResourceSet(OS os, int inputsCount) throws ExecException {

      final ResourceSet resourceSet;
      switch (os) {
        case DARWIN:
          resourceSet =
              ResourceSet.createWithRamCpu(/* memoryMb= */ 15 + 0.05 * inputsCount, /* cpu= */ 1);
          break;
        case LINUX:
          resourceSet =
              ResourceSet.createWithRamCpu(
                  /* memoryMb= */ Math.max(50, -100 + 0.1 * inputsCount), /* cpu= */ 1);
          break;
        default:
          resourceSet =
              ResourceSet.createWithRamCpu(/* memoryMb= */ 1500 + inputsCount, /* cpu= */ 1);
          break;
      }
      return resourceSet;
    }
  }
}
