// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleContext;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.ApplePlatform;
import com.google.devtools.build.lib.rules.apple.ApplePlatform.PlatformType;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.rules.apple.XcodeVersionProperties;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.starlarkbuildapi.objc.AppleCommonApi;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** A class that exposes apple rule implementation internals to Starlark. */
public class AppleStarlarkCommon
    implements AppleCommonApi<
        ConstraintValueInfo,
        StarlarkRuleContext,
        CcInfo,
        XcodeConfigInfo,
        ApplePlatform> {

  @VisibleForTesting
  public static final String BAD_KEY_ERROR =
      "Argument %s not a recognized key, 'strict_include', or 'providers'.";

  @VisibleForTesting
  public static final String BAD_PROVIDERS_ITER_ERROR =
      "Value for argument 'providers' must be a list of ObjcProvider instances, instead found %s.";

  @VisibleForTesting
  public static final String BAD_PROVIDERS_ELEM_ERROR =
      "Value for argument 'providers' must be a list of ObjcProvider instances, instead found "
          + "iterable with %s.";

  @VisibleForTesting
  public static final String NOT_SET_ERROR = "Value for key %s must be a set, instead found %s.";

  @Nullable private StructImpl platformType;
  @Nullable private StructImpl platform;

  public AppleStarlarkCommon() {}

  @Override
  public AppleToolchain getAppleToolchain() {
    return new AppleToolchain();
  }

  @Override
  public StructImpl getPlatformTypeStruct() {
    if (platformType == null) {
      platformType = PlatformType.getStarlarkStruct();
    }
    return platformType;
  }

  @Override
  public StructImpl getPlatformStruct() {
    if (platform == null) {
      platform = ApplePlatform.getStarlarkStruct();
    }
    return platform;
  }

  @Override
  public Provider getXcodeVersionPropertiesConstructor() {
    return XcodeVersionProperties.PROVIDER;
  }

  @Override
  public Provider getXcodeVersionConfigConstructor() {
    return XcodeConfigInfo.PROVIDER;
  }

  @Override
  public Provider getObjcProviderConstructor() {
    return ObjcProvider.STARLARK_CONSTRUCTOR;
  }

  @Override
  public Provider getAppleDynamicFrameworkConstructor() {
    return AppleDynamicFrameworkInfo.STARLARK_CONSTRUCTOR;
  }

  @Override
  public Provider getAppleExecutableBinaryConstructor() {
    return AppleExecutableBinaryInfo.STARLARK_CONSTRUCTOR;
  }

  @Override
  public ImmutableMap<String, String> getAppleHostSystemEnv(XcodeConfigInfo xcodeConfig) {
    return AppleConfiguration.getXcodeVersionEnv(xcodeConfig.getXcodeVersion());
  }

  @Override
  public ImmutableMap<String, String> getTargetAppleEnvironment(
      XcodeConfigInfo xcodeConfigApi, ApplePlatform platformApi) {
    XcodeConfigInfo xcodeConfig = xcodeConfigApi;
    ApplePlatform platform = (ApplePlatform) platformApi;
    return AppleConfiguration.appleTargetPlatformEnv(
        platform, xcodeConfig.getSdkVersionForPlatform(platform));
  }

  @Override
  // This method is registered statically for Starlark, and never called directly.
  public ObjcProvider newObjcProvider(Dict<String, Object> kwargs, StarlarkThread thread)
      throws EvalException {
    ObjcProvider.StarlarkBuilder resultBuilder = new ObjcProvider.StarlarkBuilder();
    for (Map.Entry<String, Object> entry : kwargs.entrySet()) {
      ObjcProvider.Key<?> key = ObjcProvider.getStarlarkKeyForString(entry.getKey());
      if (key != null) {
        resultBuilder.addElementsFromStarlark(key, entry.getValue());
      } else {
        switch (entry.getKey()) {
          case "strict_include":
            resultBuilder.addStrictIncludeFromStarlark(entry.getValue());
            break;
          case "providers":
            resultBuilder.addProvidersFromStarlark(entry.getValue());
            break;
          default:
            throw Starlark.errorf(BAD_KEY_ERROR, entry.getKey());
        }
      }
    }
    return resultBuilder.build();
  }

  @Override
  public AppleDynamicFrameworkInfo newDynamicFrameworkProvider(
      Object dylibBinary,
      CcInfo depsCcInfo,
      Object dynamicFrameworkDirs,
      Object dynamicFrameworkFiles,
      StarlarkThread thread)
      throws EvalException {
    NestedSet<String> frameworkDirs =
        Depset.noneableCast(dynamicFrameworkDirs, String.class, "framework_dirs");
    NestedSet<Artifact> frameworkFiles =
        Depset.noneableCast(dynamicFrameworkFiles, Artifact.class, "framework_files");
    Artifact binary = (dylibBinary != Starlark.NONE) ? (Artifact) dylibBinary : null;
    return new AppleDynamicFrameworkInfo(binary, depsCcInfo, frameworkDirs, frameworkFiles);
  }

  @Override
  public AppleExecutableBinaryInfo newExecutableBinaryProvider(
      Object executableBinary, CcInfo depsCcInfo, StarlarkThread thread) throws EvalException {
    Artifact binary = (executableBinary != Starlark.NONE) ? (Artifact) executableBinary : null;
    return new AppleExecutableBinaryInfo(binary, depsCcInfo);
  }

  @Override
  public DottedVersion dottedVersion(String version) throws EvalException {
    try {
      return DottedVersion.fromString(version);
    } catch (DottedVersion.InvalidDottedVersionException e) {
      throw new EvalException(e.getMessage());
    }
  }
}
