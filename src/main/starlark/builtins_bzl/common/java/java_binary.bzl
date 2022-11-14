# Copyright 2022 The Bazel Authors. All rights reserved.
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

""" Implementation of java_binary for bazel """

load(":common/java/java_common.bzl", "BASIC_JAVA_LIBRARY_WITH_PROGUARD_IMPLICIT_ATTRS", "basic_java_library")
load(":common/java/java_util.bzl", "create_single_jar", "shell_quote")
load(":common/java/java_helper.bzl", helper = "util")
load(":common/java/java_semantics.bzl", "semantics")
load(":common/rule_util.bzl", "merge_attrs")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/semantics.bzl", cc_semantics = "semantics")

CcInfo = _builtins.toplevel.CcInfo
CcLauncherInfo = _builtins.internal.cc_internal.launcher_provider
JavaInfo = _builtins.toplevel.JavaInfo
JavaPluginInfo = _builtins.toplevel.JavaPluginInfo
ProtoInfo = _builtins.toplevel.ProtoInfo
java_common = _builtins.toplevel.java_common

InternalDeployJarInfo = provider(
    "Provider for passing info to deploy jar rule",
    fields = [
        "java_attrs",
        "launcher_info",
        "shared_archive",
        "main_class",
        "coverage_main_class",
        "strip_as_default",
        "hermetic",
        "add_exports",
        "add_opens",
    ],
)

JavaRuntimeClasspathInfo = provider(
    "Provider for the runtime classpath contributions of a Java binary.",
    fields = ["runtime_classpath"],
)

# TODO(hvd): inline implicit deps, toolchains
def basic_java_binary(
        ctx,
        deps,
        resources,
        main_class,
        coverage_main_class,
        coverage_config,
        launcher_info,
        executable,
        feature_config,
        strip_as_default,
        extension_registry_provider = None):
    """Creates actions for compiling and linting java sources, coverage support, and sources jar (_deploy-src.jar).

    Args:
        ctx: (RuleContext) The rule context
        deps: (list[Target]) The list of other targets to be linked in
        resources: (list[File]) The list of data files to be included in the class jar
        main_class: (String) FQN of the java main class
        coverage_main_class: (String) FQN of the actual main class if coverage is enabled
        coverage_config: (Struct|None) If coverage is enabled, a struct with fields (runner, manifest, env, support_files), None otherwise
        launcher_info: (Struct) Structure with fields (launcher, unstripped_launcher, runfiles, runtime_jars, jvm_flags, classpath_resources)
        executable: (File) The executable output of the rule
        feature_config: (FeatureConfiguration) The result of cc_common.configure_features()
        strip_as_default: (bool) Whether this target outputs a stripped launcher and deploy jar
        extension_registry_provider: (GeneratedExtensionRegistryProvider) internal param, do not use

    Returns:
        Tuple(
            dict[str, Provider],    // providers
            Struct(                 // default info
                files_to_build: depset(File),
                runfiles: Runfiles,
                executable: File
            ),
            list[String]            // jvm flags
          )

    """
    toolchain = semantics.find_java_toolchain(ctx)
    java_runtime_toolchain = semantics.find_java_runtime_toolchain(ctx)
    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)

    # TODO(hvd): add docstring once signature is finalized
    if not ctx.attr.create_executable and ctx.attr.launcher:
        fail("launcher specified but create_executable is false")
    if not ctx.attr.use_launcher and ctx.attr.launcher:
        fail("launcher specified but use_launcher is false")

    if not ctx.attr.srcs and ctx.attr.deps:
        fail("deps not allowed without srcs; move to runtime_deps?")

    module_flags = [dep[JavaInfo].module_flags_info for dep in deps if JavaInfo in dep]
    add_exports = depset(ctx.attr.add_exports, transitive = [m.add_exports for m in module_flags])
    add_opens = depset(ctx.attr.add_opens, transitive = [m.add_opens for m in module_flags])

    classpath_resources = []
    classpath_resources.extend(launcher_info.classpath_resources)
    if hasattr(ctx.files, "classpath_resources"):
        classpath_resources.extend(ctx.files.classpath_resources)

    target, common_info = basic_java_library(
        ctx,
        srcs = ctx.files.srcs,
        deps = ctx.attr.deps,
        # TODO(b/213551463): There seems to be duplication of deps.
        runtime_deps = ctx.attr.runtime_deps + deps,
        plugins = ctx.attr.plugins,
        resources = resources,
        resource_jars = ([toolchain.timezone_data()] if toolchain.timezone_data() else []),
        classpath_resources = classpath_resources,
        javacopts = ctx.attr.javacopts,
        neverlink = ctx.attr.neverlink,
        enable_compile_jar_action = False,
        coverage_config = coverage_config,
        add_exports = ctx.attr.add_exports,
        add_opens = ctx.attr.add_opens,
    )
    java_info = target["JavaInfo"]
    if extension_registry_provider:
        java_info = java_common.merge(
            [
                java_info,
                JavaInfo(
                    output_jar = extension_registry_provider.class_jar,
                    compile_jar = None,
                    source_jar = extension_registry_provider.src_jar,
                ),
            ],
        )

    jvm_flags = []

    jvm_flags.extend(launcher_info.jvm_flags)

    native_libs_dirs = java_common.collect_native_deps_dirs(deps)
    if native_libs_dirs:
        prefix = "${JAVA_RUNFILES}/" + ctx.workspace_name + "/"
        jvm_flags.append("-Djava.library.path=%s" % (
            ":".join([prefix + d for d in native_libs_dirs])
        ))

    jvm_flags.extend(ctx.fragments.java.default_jvm_opts)
    jvm_flags.extend([ctx.expand_make_variables(
        "jvm_flags",
        ctx.expand_location(flag, ctx.attr.data, short_paths = True),
        {},
    ) for flag in ctx.attr.jvm_flags])

    # TODO(cushon): make string formatting lazier once extend_template support is added
    # https://github.com/bazelbuild/proposals#:~:text=2022%2D04%2D25,Starlark
    jvm_flags.extend(["--add-exports=%s=ALL-UNNAMED" % x for x in add_exports.to_list()])
    jvm_flags.extend(["--add-opens=%s=ALL-UNNAMED" % x for x in add_opens.to_list()])

    files_to_build = []

    java_attrs = _collect_attrs(ctx, java_info, classpath_resources)

    if executable:
        files_to_build.append(executable)

    output_groups = common_info.output_groups

    if coverage_config:
        _generate_coverage_manifest(ctx, coverage_config.manifest, java_attrs.runtime_classpath)
        files_to_build.append(coverage_config.manifest)

    shared_archive = _create_shared_archive(ctx, java_runtime_toolchain, java_attrs)

    if extension_registry_provider:
        files_to_build.append(extension_registry_provider.class_jar)
        output_groups["_direct_source_jars"] = (
            output_groups["_direct_source_jars"] + [extension_registry_provider.src_jar]
        )
        output_groups["_source_jars"] = depset(
            direct = [extension_registry_provider.src_jar],
            transitive = [output_groups["_source_jars"]],
        )

    one_version_output = _create_one_version_check(ctx, java_info.transitive_runtime_jars)
    validation_outputs = [one_version_output] if one_version_output else []

    _create_deploy_sources_jar(ctx, output_groups["_source_jars"])

    files = depset(files_to_build + common_info.files_to_build)

    transitive_runfiles_artifacts = depset(transitive = [
        files,
        java_attrs.runtime_classpath,
        depset(transitive = launcher_info.runfiles),
        java_runtime_toolchain.files,
    ])

    # Add symlinks to the C++ runtime libraries under a path that can be built
    # into the Java binary without having to embed the crosstool, gcc, and grte
    # version information contained within the libraries' package paths.
    runfiles_symlinks = {}

    # TODO(hvd): do we need this in bazel? if yes, fix abs path check on windows
    if not java_runtime_toolchain.java_home.startswith("/"):
        runfiles_symlinks = {
            ("_cpp_runtimes/%s" % lib.basename): lib
            for lib in cc_toolchain.dynamic_runtime_lib(
                feature_configuration = feature_config,
            ).to_list()
        }

    runfiles = ctx.runfiles(
        transitive_files = transitive_runfiles_artifacts,
        collect_default = True,
        symlinks = runfiles_symlinks,
    )

    if launcher_info.launcher:
        default_launcher = helper.filter_launcher_for_target(ctx)
        default_launcher_artifact = helper.launcher_artifact_for_target(ctx)
        default_launcher_runfiles = default_launcher[DefaultInfo].default_runfiles
        if default_launcher_artifact == launcher_info.launcher:
            runfiles = runfiles.merge(default_launcher_runfiles)
        else:
            # N.B. The "default launcher" referred to here is the launcher target specified through
            # an attribute or flag. We wish to retain the runfiles of the default launcher, *except*
            # for the original cc_binary artifact, because we've swapped it out with our custom
            # launcher. Hence, instead of calling builder.addTarget(), or adding an odd method
            # to Runfiles.Builder, we "unravel" the call and manually add things to the builder.
            # Because the NestedSet representing each target's launcher runfiles is re-built here,
            # we may see increased memory consumption for representing the target's runfiles.
            runfiles = runfiles.merge(
                ctx.runfiles(
                    files = [launcher_info.launcher],
                    transitive_files = depset([
                        file
                        for file in default_launcher_runfiles.files.to_list()
                        if file != default_launcher_artifact
                    ]),
                    symlinks = default_launcher_runfiles.symlinks,
                    root_symlinks = default_launcher_runfiles.root_symlinks,
                ),
            )

    runfiles = runfiles.merge_all([
        dep[DefaultInfo].default_runfiles
        for dep in ctx.attr.runtime_deps
        if DefaultInfo in dep
    ])

    if validation_outputs:
        output_groups["_validation"] = validation_outputs

    _filter_validation_output_group(ctx, output_groups)

    java_binary_info = java_common.to_java_binary_info(java_info)

    default_info = struct(
        files = files,
        runfiles = runfiles,
        executable = executable,
    )

    return {
        "OutputGroupInfo": OutputGroupInfo(**output_groups),
        "JavaInfo": java_binary_info,
        "InstrumentedFilesInfo": target["InstrumentedFilesInfo"],
        "JavaRuntimeClasspathInfo": JavaRuntimeClasspathInfo(runtime_classpath = java_info.transitive_runtime_jars),
        "InternalDeployJarInfo": InternalDeployJarInfo(
            java_attrs = java_attrs,
            launcher_info = struct(
                runtime_jars = launcher_info.runtime_jars,
                launcher = launcher_info.launcher,
                unstripped_launcher = launcher_info.unstripped_launcher,
            ),
            shared_archive = shared_archive,
            main_class = main_class,
            coverage_main_class = coverage_main_class,
            strip_as_default = strip_as_default,
            hermetic = hasattr(ctx.attr, "hermetic") and ctx.attr.hermetic,
            add_exports = add_exports,
            add_opens = add_opens,
        ),
    }, default_info, jvm_flags

def _collect_attrs(ctx, java_info, classpath_resources):
    deploy_env_jars = depset(transitive = [
        dep[JavaRuntimeClasspathInfo].runtime_classpath
        for dep in ctx.attr.deploy_env
    ])
    runtime_classpath_for_archive = java_common.get_runtime_classpath_for_archive(java_info.transitive_runtime_jars, deploy_env_jars)
    runtime_jars = depset([ctx.outputs.classjar])

    resources = [p for p in ctx.files.srcs if p.extension == "properties"]
    transitive_resources = []
    for r in ctx.attr.resources:
        transitive_resources.append(
            r[ProtoInfo].transitive_sources if ProtoInfo in r else r.files,
        )

    resource_names = dict()
    for r in classpath_resources:
        if r.basename in resource_names:
            fail("entries must have different file names (duplicate: %s)" % r.basename)
        resource_names[r.basename] = None

    return struct(
        runtime_jars = runtime_jars,
        runtime_classpath_for_archive = runtime_classpath_for_archive,
        classpath_resources = depset(classpath_resources),
        runtime_classpath = depset(transitive = [runtime_jars, java_info.transitive_runtime_jars]),
        resources = depset(resources, transitive = transitive_resources),
    )

def _generate_coverage_manifest(ctx, output, runtime_classpath):
    ctx.actions.write(
        output = output,
        content = "\n".join([file.short_path for file in runtime_classpath.to_list()]),
    )

def _create_shared_archive(ctx, runtime, java_attrs):
    classlist = ctx.file.classlist if hasattr(ctx.file, "classlist") else None
    if not classlist:
        return None
    jsa = ctx.actions.declare_file("%s.jsa" % ctx.label.name)
    merged = ctx.actions.declare_file(jsa.dirname + "/" + helper.strip_extension(jsa) + "-merged.jar")
    create_single_jar(
        ctx,
        merged,
        java_attrs.runtime_jars,
        java_attrs.runtime_classpath_for_archive,
    )

    args = ctx.actions.args()
    args.add("-Xshare:dump")
    args.add(jsa, format = "-XX:SharedArchiveFile=%s")
    args.add(classlist, format = "-XX:SharedClassListFile=%s")

    input_files = [classlist, merged]

    config_file = ctx.file.cds_config_file if hasattr(ctx.file, "cds_config_file") else None
    if config_file:
        args.add(config_file, format = "-XX:SharedArchiveConfigFile=%s")
        input_files.append(config_file)

    args.add("-cp", merged)

    if hasattr(ctx.attr, "jvm_flags_for_cds_image_creation") and ctx.attr.jvm_flags_for_cds_image_creation:
        args.add_all([
            ctx.expand_location(flag, ctx.attr.data)
            for flag in ctx.attr.jvm_flags_for_cds_image_creation
        ])
        input_files.extend(ctx.files.data)

    ctx.actions.run(
        mnemonic = "JavaJSA",
        progress_message = "Dumping Java Shared Archive %s" % jsa.short_path,
        executable = runtime.java_executable_exec_path,
        inputs = depset(input_files, transitive = [runtime.files]),
        outputs = [jsa],
        arguments = [args],
    )
    return jsa

def _create_one_version_check(ctx, inputs):
    one_version_level = ctx.fragments.java.one_version_enforcement_level
    if one_version_level == "OFF":
        return None
    tool = helper.check_and_get_one_version_attribute(ctx, "one_version_tool")
    allowlist = helper.check_and_get_one_version_attribute(ctx, "one_version_allowlist")
    if not tool or not allowlist:  # On Mac oneversion tool is not available
        return None

    output = ctx.actions.declare_file("%s-one-version.txt" % ctx.label.name)

    args = ctx.actions.args()
    args.set_param_file_format("shell").use_param_file("@%s", use_always = True)

    args.add("--output", output)
    args.add("--whitelist", allowlist)
    if one_version_level == "WARNING":
        args.add("--succeed_on_found_violations")
    args.add_all(
        "--inputs",
        inputs,
        map_each = helper.jar_and_target_arg_mapper,
    )

    ctx.actions.run(
        mnemonic = "JavaOneVersion",
        progress_message = "Checking for one-version violations in %{label}",
        executable = tool,
        inputs = depset([allowlist], transitive = [inputs]),
        tools = [tool],
        outputs = [output],
        arguments = [args],
    )

    return output

def _create_deploy_sources_jar(ctx, sources):
    create_single_jar(
        ctx,
        ctx.outputs.deploysrcjar,
        sources,
    )

def _filter_validation_output_group(ctx, output_group):
    to_exclude = depset(transitive = [
        dep[OutputGroupInfo]._validation
        for dep in ctx.attr.deploy_env
        if OutputGroupInfo in dep and hasattr(dep[OutputGroupInfo], "_validation")
    ])
    if to_exclude:
        transitive_validations = depset(transitive = [
            _get_validations_from_attr(ctx, attr_name)
            for attr_name in dir(ctx.attr)
            # we also exclude implicit, cfg=host/exec and tool attributes
            if not attr_name.startswith("_") and
               attr_name not in [
                   "deploy_env",
                   "applicable_licenses",
                   "plugins",
                   "translations",
                   # special ignored attributes
                   "compatible_with",
                   "restricted_to",
                   "exec_compatible_with",
                   "target_compatible_with",
               ]
        ])
        if not ctx.attr.create_executable:
            excluded_set = {x: None for x in to_exclude.to_list()}
            transitive_validations = [
                x
                for x in transitive_validations.to_list()
                if x not in excluded_set
            ]
        output_group["_validation_transitive"] = transitive_validations

def _get_validations_from_attr(ctx, attr_name):
    attr = getattr(ctx.attr, attr_name)
    if type(attr) == "list":
        return depset(transitive = [_get_validations_from_target(t) for t in attr])
    else:
        return _get_validations_from_target(attr)

def _get_validations_from_target(target):
    if (
        type(target) == "Target" and
        OutputGroupInfo in target and
        hasattr(target[OutputGroupInfo], "_validation")
    ):
        return target[OutputGroupInfo]._validation
    else:
        return depset()

def _check_and_get_main_class(ctx):
    create_executable = ctx.attr.create_executable
    main_class = _get_main_class(ctx)

    if not create_executable and main_class:
        fail("main class must not be specified when executable is not created")
    if create_executable and not main_class:
        if not ctx.attr.srcs:
            fail("need at least one of 'main_class' or Java source files")
        main_class = helper.primary_class(ctx)
        if main_class == None:
            fail("main_class was not provided and cannot be inferred: " +
                 "source path doesn't include a known root (java, javatests, src, testsrc)")

    return _get_main_class(ctx)

def _get_main_class(ctx):
    if not ctx.attr.create_executable:
        return None

    main_class = ctx.attr.main_class
    if not main_class and ctx.attr.use_testrunner:
        main_class = "com.google.testing.junit.runner.BazelTestRunner"

    if main_class == "":
        main_class = helper.primary_class(ctx)
    return main_class

def _get_launcher_info(ctx):
    launcher = helper.launcher_artifact_for_target(ctx)
    return struct(
        launcher = launcher,
        unstripped_launcher = launcher,
        runfiles = [],
        runtime_jars = [],
        jvm_flags = [],
        classpath_resources = [],
    )

def _get_executable(ctx):
    if not ctx.attr.create_executable:
        return None
    executable_name = ctx.label.name
    if helper.is_windows(ctx):
        executable_name = executable_name + ".exe"

    return ctx.actions.declare_file(executable_name)

def _create_stub(ctx, java_attrs, java_runtime_toolchain, launcher, executable, jvm_flags, main_class, coverage_main_class):
    java_executable = helper.get_java_executable(ctx, java_runtime_toolchain, launcher)
    workspace_name = ctx.workspace_name
    workspace_prefix = workspace_name + ("/" if workspace_name else "")
    runfiles_enabled = helper.runfiles_enabled(ctx)
    coverage_enabled = ctx.configuration.coverage_enabled

    test_support = ctx.attr._test_support if ctx.attr.create_executable and ctx.attr.use_testrunner else None
    test_support_jars = test_support[JavaInfo].transitive_runtime_jars if test_support else depset()
    classpath = depset(
        transitive = [
            java_attrs.runtime_classpath,
            test_support_jars if ctx.fragments.java.enforce_explicit_java_test_deps else depset(),
        ],
    )

    if helper.is_windows(ctx):
        jvm_flags_for_launcher = []
        for flag in jvm_flags:
            jvm_flags_for_launcher.extend(ctx.tokenize(flag))
        return _create_windows_exe_launcher(ctx, java_executable, classpath, main_class, jvm_flags_for_launcher, executable)

    if runfiles_enabled:
        prefix = "" if helper.is_absolute_path(ctx, java_executable) else "${JAVA_RUNFILES}/"
        java_bin = "JAVABIN=${JAVABIN:-" + prefix + java_executable + "}"
    else:
        java_bin = "JAVABIN=${JAVABIN:-$(rlocation " + java_executable + ")}"

    td = ctx.actions.template_dict()
    td.add_joined()

    ctx.actions.expand_template(
        template = ctx.attr.java_stub_template,
        output = executable,
        substitutions = {
            "%runfiles_manifest_only%": "" if runfiles_enabled else "1",
            "%workspace_prefix%": workspace_prefix,
            "%javabin%": java_bin,
            "%needs_runfiles%": "0" if helper.is_absolute_path(ctx, java_runtime_toolchain.java_executable_exec_path) else "1",
            "%set_jacoco_metadata%": "",
            "%set_jacoco_main_class%": "export JACOCO_MAIN_CLASS=" + coverage_main_class if coverage_enabled else "",
            "%set_jacoco_java_runfiles_root%": "export JACOCO_JAVA_RUNFILES_ROOT=${JAVA_RUNFILES}/" + workspace_prefix if coverage_enabled else "",
            "%java_start_class%": shell_quote(main_class),
            "%jvm_flags%": " ".join(jvm_flags),
        },
        computed_substitutions = td,
        is_executable = True,
    )
    return executable

def _create_windows_exe_launcher(ctx, java_executable, classpath, main_class, jvm_flags_for_launcher, executable):
    #TODO(hvd): implement LauncherFileWriteAction
    return executable

BASIC_JAVA_BINARY_ATTRIBUTES = merge_attrs(
    BASIC_JAVA_LIBRARY_WITH_PROGUARD_IMPLICIT_ATTRS,
    {
        "srcs": attr.label_list(
            allow_files = [".java", ".srcjar", ".properties"] + semantics.EXTRA_SRCS_TYPES,
            flags = ["DIRECT_COMPILE_TIME_INPUT", "ORDER_INDEPENDENT"],
        ),
        "deps": attr.label_list(
            allow_files = [".jar"],
            allow_rules = semantics.ALLOWED_RULES_IN_DEPS + semantics.ALLOWED_RULES_IN_DEPS_WITH_WARNING,
            providers = [
                [CcInfo],
                [JavaInfo],
            ],
            flags = ["SKIP_ANALYSIS_TIME_FILETYPE_CHECK"],
        ),
        "resources": attr.label_list(
            allow_files = True,
            flags = ["SKIP_CONSTRAINTS_OVERRIDE", "ORDER_INDEPENDENT"],
        ),
        "runtime_deps": attr.label_list(
            allow_files = [".jar"],
            allow_rules = semantics.ALLOWED_RULES_IN_DEPS,
            providers = [[CcInfo], [JavaInfo]],
            flags = ["SKIP_ANALYSIS_TIME_FILETYPE_CHECK"],
        ),
        "data": attr.label_list(
            allow_files = True,
            flags = ["SKIP_CONSTRAINTS_OVERRIDE"],
        ),
        "plugins": attr.label_list(
            providers = [JavaPluginInfo],
            allow_files = True,
            cfg = "exec",
        ),
        "deploy_env": attr.label_list(
            allow_rules = ["java_binary"],
            allow_files = False,
        ),
        "launcher": attr.label(
            allow_files = False,
            providers = [CcLauncherInfo],
        ),
        "neverlink": attr.bool(),
        "javacopts": attr.string_list(),
        "add_exports": attr.string_list(),
        "add_opens": attr.string_list(),
        "main_class": attr.string(),
        "jvm_flags": attr.string_list(),
        "deploy_manifest_lines": attr.string_list(),
        "create_executable": attr.bool(default = True),
        "stamp": attr.int(default = -1, values = [-1, 0, 1]),
        "use_testrunner": attr.bool(default = False),
        "use_launcher": attr.bool(default = True),
        "env": attr.string_dict(),
        "_stub_template": attr.label(
            default = semantics.JAVA_STUB_TEMPLATE_LABEL,
            allow_single_file = True,
        ),
        "_cc_toolchain": attr.label(default = "@" + cc_semantics.get_repo() + "//tools/cpp:current_cc_toolchain"),
        "_grep_includes": cc_semantics.get_grep_includes(),
        "_java_toolchain_type": attr.label(default = semantics.JAVA_TOOLCHAIN_TYPE),
        "_java_runtime_toolchain_type": attr.label(default = semantics.JAVA_RUNTIME_TOOLCHAIN_TYPE),
    },
)

def _bazel_java_binary_impl(ctx):
    toolchain = semantics.find_java_toolchain(ctx)
    java_runtime_toolchain = semantics.find_java_runtime_toolchain(ctx)
    cc_toolchain = cc_helper.find_cpp_toolchain(ctx)
    deps = helper.collect_all_targets_as_deps(ctx)

    main_class = _check_and_get_main_class(ctx)
    coverage_main_class = main_class
    coverage_config = helper.get_coverage_config(ctx)
    if coverage_config:
        main_class = coverage_config.main_class

    launcher_info = _get_launcher_info(ctx)

    executable = _get_executable(ctx)

    feature_config = helper.get_feature_config(ctx)
    strip_as_default = helper.should_strip_as_default(ctx, feature_config)

    providers, default_info, jvm_flags = basic_java_binary(
        ctx,
        deps,
        ctx.files.resources,
        main_class,
        coverage_main_class,
        coverage_config,
        launcher_info,
        executable,
        feature_config,
        strip_as_default,
    )

    java_attrs = providers["InternalDeployJarInfo"].java_attrs

    if executable:
        _create_stub(ctx, java_attrs, java_runtime_toolchain, launcher_info.launcher, executable, jvm_flags, main_class, coverage_main_class, coverage_config)
    return providers.values()

def _compute_test_support(use_testrunner):
    return Label("@//tools/jdk:TestRunner") if use_testrunner else None

def make_java_binary(executable, resolve_launcher_flag):
    return rule(
        _bazel_java_binary_impl,
        attrs = merge_attrs(
            BASIC_JAVA_BINARY_ATTRIBUTES,
            {
                "_java_launcher": attr.label(
                    default = configuration_field(
                        fragment = "java",
                        name = "launcher",
                    ) if resolve_launcher_flag else None,
                ),
                "_test_support": attr.label(default = _compute_test_support),
            },
            ({} if executable else {
                "args": attr.string_list(),
                "output_licenses": attr.license() if hasattr(attr, "license") else attr.string_list(),
            }),
        ),
        fragments = ["cpp", "java"],
        provides = [JavaInfo],
        toolchains = [semantics.JAVA_TOOLCHAIN, semantics.JAVA_RUNTIME_TOOLCHAIN] + cc_helper.use_cpp_toolchain(),
        # TODO(hvd): replace with filegroups?
        outputs = {
            "classjar": "%{name}.jar",
            "sourcejar": "%{name}-src.jar",
            "deploysrcjar": "%{name}_deploy-src.jar",
        },
        executable = executable,
        exec_groups = {
            "cpp_link": exec_group(copy_from_rule = True),
        },
    )

java_binary = make_java_binary(executable = True, resolve_launcher_flag = True)
