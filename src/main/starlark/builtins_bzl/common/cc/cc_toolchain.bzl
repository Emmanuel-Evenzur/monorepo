# Copyright 2023 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Starlark implementation of cc_toolchain rule."""

load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_toolchain_provider_helper.bzl", "get_cc_toolchain_provider")
load(":common/cc/fdo_prefetch_hints.bzl", "FdoPrefetchHintsInfo")
load(":common/cc/fdo_profile.bzl", "FdoProfileInfo")
load(":common/cc/memprof_profile.bzl", "MemProfProfileInfo")
load(":common/cc/propeller_optimize.bzl", "PropellerOptimizeInfo")
load(":common/cc/semantics.bzl", "semantics")

cc_internal = _builtins.internal.cc_internal
ToolchainInfo = _builtins.toplevel.platform_common.ToolchainInfo
TemplateVariableInfo = _builtins.toplevel.platform_common.TemplateVariableInfo
apple_common = _builtins.toplevel.apple_common
PackageSpecificationInfo = _builtins.toplevel.PackageSpecificationInfo
CcToolchainConfigInfo = _builtins.toplevel.CcToolchainConfigInfo

def _files(ctx, attr_name):
    attr = getattr(ctx.attr, attr_name, None)
    if attr != None and DefaultInfo in attr:
        return attr[DefaultInfo].files
    return depset()

def _provider(attr, provider):
    if attr != None and provider in attr:
        return attr[provider]
    return None

def _latebound_libc(ctx, attr_name, implicit_attr_name):
    if getattr(ctx.attr, implicit_attr_name, None) == None:
        return attr_name
    return implicit_attr_name

def _full_inputs_for_link(ctx, linker_files, libc, is_apple_toolchain):
    if not is_apple_toolchain:
        return depset(
            [ctx.file._interface_library_builder, ctx.file._link_dynamic_library_tool],
            transitive = [linker_files, libc],
        )
    return depset(transitive = [linker_files, libc])

def _label(ctx, attr_name):
    if getattr(ctx.attr, attr_name, None) != None:
        return getattr(ctx.attr, attr_name).label
    return None

def _package_specification_provider(ctx, allowlist_name):
    possible_attr_names = ["_whitelist_" + allowlist_name, "_allowlist_" + allowlist_name]
    for attr_name in possible_attr_names:
        if hasattr(ctx.attr, attr_name):
            package_specification_provider = getattr(ctx.attr, attr_name)[PackageSpecificationInfo]
            if package_specification_provider != None:
                return package_specification_provider
    fail("Allowlist argument for " + allowlist_name + " not found")

def _single_file(ctx, attr_name):
    files = getattr(ctx.files, attr_name, [])
    if len(files) > 1:
        fail(ctx.label.name + " expected a single artifact", attr = attr_name)
    if len(files) == 1:
        return files[0]
    return None

def _attributes(ctx, is_apple):
    grep_includes = None
    if not semantics.is_bazel:
        grep_includes = _single_file(ctx, "_grep_includes")

    latebound_libc = _latebound_libc(ctx, "libc_top", "_libc_top")
    latebound_target_libc = _latebound_libc(ctx, "libc_top", "_target_libc_top")

    all_files = _files(ctx, "all_files")
    return struct(
        supports_param_files = ctx.attr.supports_param_files,
        runtime_solib_dir_base = "_solib__" + cc_internal.escape_label(label = ctx.label),
        fdo_prefetch_provider = _provider(ctx.attr._fdo_prefetch_hints, FdoPrefetchHintsInfo),
        propeller_optimize_provider = _provider(ctx.attr._propeller_optimize, PropellerOptimizeInfo),
        mem_prof_profile_provider = _provider(ctx.attr._memprof_profile, MemProfProfileInfo),
        cc_toolchain_config_info = _provider(ctx.attr.toolchain_config, CcToolchainConfigInfo),
        fdo_optimize_artifacts = ctx.files._fdo_optimize,
        licenses_provider = cc_internal.licenses(ctx = ctx),
        static_runtime_lib = ctx.attr.static_runtime_lib,
        dynamic_runtime_lib = ctx.attr.dynamic_runtime_lib,
        supports_header_parsing = ctx.attr.supports_header_parsing,
        all_files = all_files,
        compiler_files = _files(ctx, "compiler_files"),
        strip_files = _files(ctx, "strip_files"),
        objcopy_files = _files(ctx, "objcopy_files"),
        fdo_optimize_label = _label(ctx, "_fdo_optimize"),
        link_dynamic_library_tool = ctx.file._link_dynamic_library_tool,
        grep_includes = grep_includes,
        module_map = ctx.attr.module_map,
        as_files = _files(ctx, "as_files"),
        ar_files = _files(ctx, "ar_files"),
        dwp_files = _files(ctx, "dwp_files"),
        fdo_optimize_provider = _provider(ctx.attr._fdo_optimize, FdoProfileInfo),
        module_map_artifact = _single_file(ctx, "module_map"),
        all_files_including_libc = depset(transitive = [_files(ctx, "all_files"), _files(ctx, latebound_libc)]),
        fdo_profile_provider = _provider(ctx.attr._fdo_profile, FdoProfileInfo),
        cs_fdo_profile_provider = _provider(ctx.attr._csfdo_profile, FdoProfileInfo),
        x_fdo_profile_provider = _provider(ctx.attr._xfdo_profile, FdoProfileInfo),
        zipper = ctx.file._zipper,
        linker_files = _full_inputs_for_link(
            ctx,
            _files(ctx, "linker_files"),
            _files(ctx, latebound_libc),
            is_apple,
        ),
        cc_toolchain_label = ctx.label,
        coverage_files = _files(ctx, "coverage_files") or all_files,
        compiler_files_without_includes = _files(ctx, "compiler_files_without_includes"),
        libc = _files(ctx, latebound_libc),
        target_libc = _files(ctx, latebound_target_libc),
        libc_top_label = _label(ctx, latebound_libc),
        target_libc_top_label = _label(ctx, latebound_target_libc),
        if_so_builder = ctx.file._interface_library_builder,
        allowlist_for_layering_check = _package_specification_provider(ctx, "disabling_parse_headers_and_layering_check_allowed"),
        build_info_files = _provider(ctx.attr._build_info_translator, OutputGroupInfo),
    )

def _cc_toolchain_impl(ctx):
    xcode_config_info = None
    if hasattr(ctx.attr, "_xcode_config"):
        xcode_config_info = ctx.attr._xcode_config[apple_common.XcodeVersionConfig]
    attributes = _attributes(ctx, hasattr(ctx.attr, "_xcode_config"))
    providers = []
    if attributes.licenses_provider != None:
        providers.append(attributes.licenses_provider)

    cc_toolchain = get_cc_toolchain_provider(ctx, attributes, xcode_config_info)
    if cc_toolchain == None:
        fail("This should never happen")
    template_variable_info = TemplateVariableInfo(
        cc_toolchain._additional_make_variables | cc_helper.get_toolchain_global_make_variables(cc_toolchain),
    )
    toolchain = ToolchainInfo(
        cc = cc_toolchain,
        # Add a clear signal that this is a CcToolchainProvider, since just "cc" is
        # generic enough to possibly be re-used.
        cc_provider_in_toolchain = True,
    )
    providers.append(cc_toolchain)
    providers.append(toolchain)
    providers.append(template_variable_info)
    providers.append(DefaultInfo(files = cc_toolchain._all_files_including_libc))
    return providers

cc_toolchain = rule(
    implementation = _cc_toolchain_impl,
    fragments = ["cpp"],
    attrs = {
        # buildifier: disable=attr-license
        "licenses": attr.license() if hasattr(attr, "license") else attr.string_list(),
        # buildifier: disable=attr-license
        "output_licenses": attr.license() if hasattr(attr, "license") else attr.string_list(),
        "toolchain_identifier": attr.string(default = ""),
        "all_files": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "compiler_files": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "compiler_files_without_includes": attr.label(
            allow_files = True,
        ),
        "strip_files": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "objcopy_files": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "as_files": attr.label(
            allow_files = True,
        ),
        "ar_files": attr.label(
            allow_files = True,
        ),
        "linker_files": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "dwp_files": attr.label(
            allow_files = True,
            mandatory = True,
        ),
        "coverage_files": attr.label(
            allow_files = True,
        ),
        "libc_top": attr.label(
            allow_files = False,
        ),
        "static_runtime_lib": attr.label(
            allow_files = True,
        ),
        "dynamic_runtime_lib": attr.label(
            allow_files = True,
        ),
        "module_map": attr.label(
            allow_files = True,
        ),
        "supports_param_files": attr.bool(
            default = True,
        ),
        "supports_header_parsing": attr.bool(
            default = False,
        ),
        "exec_transition_for_inputs": attr.bool(
            default = False,  # No-op.
        ),
        "toolchain_config": attr.label(
            allow_files = False,
            mandatory = True,
            providers = [CcToolchainConfigInfo],
        ),
        "_libc_top": attr.label(
            default = configuration_field(fragment = "cpp", name = "libc_top"),
        ),
        "_grep_includes": semantics.get_grep_includes(),
        "_interface_library_builder": attr.label(
            default = "@" + semantics.get_repo() + "//tools/cpp:interface_library_builder",
            allow_single_file = True,
            cfg = "exec",
        ),
        "_link_dynamic_library_tool": attr.label(
            default = "@" + semantics.get_repo() + "//tools/cpp:link_dynamic_library",
            allow_single_file = True,
            cfg = "exec",
        ),
        "_cc_toolchain_type": attr.label(default = "@" + semantics.get_repo() + "//tools/cpp:toolchain_type"),
        "_zipper": attr.label(
            default = configuration_field(fragment = "cpp", name = "zipper"),
            allow_single_file = True,
            cfg = "exec",
        ),
        "_target_libc_top": attr.label(
            default = configuration_field(fragment = "cpp", name = "target_libc_top_DO_NOT_USE_ONLY_FOR_CC_TOOLCHAIN"),
        ),
        "_fdo_optimize": attr.label(
            default = configuration_field(fragment = "cpp", name = "fdo_optimize"),
            allow_files = True,
        ),
        "_xfdo_profile": attr.label(
            default = configuration_field(fragment = "cpp", name = "xbinary_fdo"),
            allow_rules = ["fdo_profile"],
            providers = [FdoProfileInfo],
        ),
        "_fdo_profile": attr.label(
            default = configuration_field(fragment = "cpp", name = "fdo_profile"),
            allow_rules = ["fdo_profile"],
            providers = [FdoProfileInfo],
        ),
        "_csfdo_profile": attr.label(
            default = configuration_field(fragment = "cpp", name = "cs_fdo_profile"),
            allow_rules = ["fdo_profile"],
            providers = [FdoProfileInfo],
        ),
        "_fdo_prefetch_hints": attr.label(
            default = configuration_field(fragment = "cpp", name = "fdo_prefetch_hints"),
            allow_rules = ["fdo_prefetch_hints"],
            providers = [FdoPrefetchHintsInfo],
        ),
        "_propeller_optimize": attr.label(
            default = configuration_field(fragment = "cpp", name = "propeller_optimize"),
            allow_rules = ["propeller_optimize"],
            providers = [PropellerOptimizeInfo],
        ),
        "_memprof_profile": attr.label(
            default = configuration_field(fragment = "cpp", name = "memprof_profile"),
            allow_rules = ["memprof_profile"],
            providers = [MemProfProfileInfo],
        ),
        "_whitelist_disabling_parse_headers_and_layering_check_allowed": attr.label(
            default = "@" + semantics.get_repo() + "//tools/build_defs/cc/whitelists/parse_headers_and_layering_check:disabling_parse_headers_and_layering_check_allowed",
            providers = [PackageSpecificationInfo],
        ),
        "_build_info_translator": attr.label(
            default = semantics.BUILD_INFO_TRANLATOR_LABEL,
            providers = [OutputGroupInfo],
        ),
    },
)

def _apple_cc_toolchain_impl(ctx):
    if ctx.attr._xcode_config[apple_common.XcodeVersionConfig].xcode_version() == None:
        fail("Xcode version must be specified to use an Apple CROSSTOOL. If your Xcode version has " +
             "changed recently, verify that \"xcode-select -p\" is correct and then try: " +
             "\"bazel shutdown\" to re-run Xcode configuration")
    return ctx.super()

apple_cc_toolchain = rule(
    implementation = _apple_cc_toolchain_impl,
    parent = cc_toolchain,
    fragments = ["apple"],
    attrs = {
        "_xcode_config": attr.label(
            default = configuration_field(fragment = "apple", name = "xcode_config_label"),
            allow_rules = ["xcode_config"],
            flags = ["SKIP_CONSTRAINTS_OVERRIDE"],
        ),
    },
)
