load(
    "//tools/vendor/google3/aswb/third_party/intellij/bazel/plugin:build-visibility.bzl",
    "PLUGIN_PACKAGES_VISIBILITY",
)
load(
    "//tools/vendor/google3/aswb/third_party/intellij/plugin/build_defs:build_defs.bzl",
    "intellij_plugin_library",
    "optional_plugin_xml",
)

licenses(["notice"])

java_library(
    name = "plugin_dev",
    srcs = glob(["src/**/*.java"]),
    deps = [
        "//tools/adt/idea/aswb/base",
        "//tools/adt/idea/aswb/proto:proto_deps",
        "//tools/vendor/google/aswb/plugin_api",
        "//tools/vendor/google/aswb/plugin_api:devkit",
        "//tools/vendor/google/aswb/plugin_api:jsr305",
        "//tools/vendor/google3/aswb/third_party/intellij/bazel/plugin/java",
        "//tools/vendor/google3/aswb/third_party/intellij/bazel/plugin/shared",
        "//tools/vendor/google3/aswb/third_party/intellij/bazel/plugin/shared:artifact",
        "//tools/vendor/google3/aswb/third_party/intellij/plugin/common/experiments",
        "//tools/vendor/google3/aswb/third_party/intellij/plugin/common/util:transactions",
    ],
)

optional_plugin_xml(
    name = "optional_xml",
    module = "DevKit",
    plugin_xml = "src/META-INF/blaze-plugin-dev.xml",
)

intellij_plugin_library(
    name = "plugin_library",
    optional_plugin_xmls = [":optional_xml"],
    visibility = PLUGIN_PACKAGES_VISIBILITY,
    deps = [":plugin_dev"],
)

intellij_plugin_library(
    name = "empty",
)
