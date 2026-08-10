# Windows port of compile-hevtun.sh — same two artifacts, same layout, ndk-build.cmd
# instead of the bash driver, which the Windows NDK does not ship.
#
# Requires $env:NDK_HOME. Stages the results under <repo>/libs/<abi>/, which is what the
# CI then copies into V2rayNG/app/libs so Gradle's jniLibs.srcDirs("libs") packages them.
$ErrorActionPreference = 'Stop'

$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $env:NDK_HOME -or -not (Test-Path $env:NDK_HOME)) {
    throw "Android NDK: NDK_HOME not found. please set env `$NDK_HOME"
}
$ndkBuild = Join-Path $env:NDK_HOME 'ndk-build.cmd'

$abis = @('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64')
$abiArg = $abis -join ' '

# The hev tree publishes its public headers as git symlinks into src/. Without
# core.symlinks (which needs Developer Mode or admin on Windows) git checks each one out
# as a text file containing the target path, and the compiler reads that path as C. Copy
# the real file over each one; idempotent, so re-running this script is harmless.
function Expand-GitSymlinks {
    param([string]$Repo)
    Push-Location $Repo
    try {
        $entries = & git ls-files -s
        foreach ($entry in $entries) {
            if ($entry -notmatch '^120000\s+\S+\s+\d+\s+(.+)$') { continue }
            $file = $Matches[1]
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { continue }
            $target = (Get-Content -LiteralPath $file -Raw).Trim()
            if ($target -notmatch '^[^\r\n]+$') { continue }
            $resolved = Join-Path (Split-Path -Parent $file) $target
            if (Test-Path -LiteralPath $resolved -PathType Leaf) {
                Copy-Item -LiteralPath $resolved -Destination $file -Force
            }
        }
    } finally {
        Pop-Location
    }
}

foreach ($repo in @(
    "$dir\hev-socks5-tunnel",
    "$dir\hev-socks5-tunnel\src\core",
    "$dir\hev-socks5-tunnel\third-part\hev-task-system",
    "$dir\hev-socks5-tunnel\third-part\lwip",
    "$dir\hev-socks5-tunnel\third-part\yaml"
)) {
    if (Test-Path $repo) { Expand-GitSymlinks -Repo $repo }
}

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("hevtun-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path "$tmp\jni" | Out-Null

try {
    # A junction stands in for the shell script's symlink: ndk-build must see the sources
    # under jni/ but the repo checkout must not be moved.
    New-Item -ItemType Junction -Path "$tmp\jni\hev-socks5-tunnel" -Target "$dir\hev-socks5-tunnel" | Out-Null

    # NDK_PROJECT_PATH and APP_BUILD_SCRIPT below are relative, so the working directory
    # is part of the build inputs, not a convenience.
    Push-Location $tmp

    # 1) JNI shared library (libhev-socks5-tunnel.so) - loaded in-process by
    #    com.v2ray.ang.service.TProxyService for the VpnService hev tun mode.
    'include $(call all-subdir-makefiles)' | Set-Content -Path "$tmp\jni\Android.mk" -Encoding ascii

    & $ndkBuild `
        NDK_PROJECT_PATH=. `
        APP_BUILD_SCRIPT=jni/Android.mk `
        "APP_ABI=$abiArg" `
        APP_PLATFORM=android-24 `
        "NDK_LIBS_OUT=$tmp\libs" `
        "NDK_OUT=$tmp\obj" `
        "APP_CFLAGS=-O3 -DPKGNAME=com/v2ray/ang/service" `
        "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
    if ($LASTEXITCODE -ne 0) { throw "ndk-build (jni library) failed with $LASTEXITCODE" }

    # 2) Standalone executable (libhevsockstun.so) - run as a separate root process by
    #    com.v2ray.ang.core.root for the Root run mode. Same hev source, no
    #    -DENABLE_LIBRARY so hev-main.c's main() is built, and BUILD_EXECUTABLE instead
    #    of a shared library. It creates its own tun and reads a YAML config.
    @'
TOP_PATH := $(call my-dir)/hev-socks5-tunnel

ifeq ($(filter $(modules-get-list),yaml),)
    include $(TOP_PATH)/third-part/yaml/Android.mk
endif
ifeq ($(filter $(modules-get-list),lwip),)
    include $(TOP_PATH)/third-part/lwip/Android.mk
endif
ifeq ($(filter $(modules-get-list),hev-task-system),)
    include $(TOP_PATH)/third-part/hev-task-system/Android.mk
endif

LOCAL_PATH := $(TOP_PATH)
SRCDIR := $(LOCAL_PATH)/src

include $(CLEAR_VARS)
include $(LOCAL_PATH)/build.mk
LOCAL_MODULE    := hevsockstun
LOCAL_SRC_FILES := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/src/misc \
	$(LOCAL_PATH)/src/core/include \
	$(LOCAL_PATH)/third-part/yaml/include \
	$(LOCAL_PATH)/third-part/lwip/src/include \
	$(LOCAL_PATH)/third-part/lwip/src/ports/include \
	$(LOCAL_PATH)/third-part/hev-task-system/include
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED
LOCAL_CFLAGS += $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += -Wl,-z,max-page-size=16384
LOCAL_LDFLAGS += -Wl,-z,common-page-size=16384
include $(BUILD_EXECUTABLE)
'@ | Set-Content -Path "$tmp\jni\exec.mk" -Encoding ascii

    & $ndkBuild `
        NDK_PROJECT_PATH=. `
        APP_BUILD_SCRIPT=jni/exec.mk `
        "APP_ABI=$abiArg" `
        APP_PLATFORM=android-24 `
        "NDK_LIBS_OUT=$tmp\libs-exec" `
        "NDK_OUT=$tmp\obj-exec" `
        "APP_CFLAGS=-O3" `
        "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"
    if ($LASTEXITCODE -ne 0) { throw "ndk-build (executable) failed with $LASTEXITCODE" }

    # Stage both artifacts under libs/<abi>/. The executable is renamed to lib*.so so the
    # APK installer extracts it into nativeLibraryDir as an executable file (filename
    # distinct from the JNI library above).
    New-Item -ItemType Directory -Force -Path "$dir\libs" | Out-Null
    Copy-Item "$tmp\libs\*" "$dir\libs\" -Recurse -Force
    foreach ($abi in $abis) {
        Copy-Item "$tmp\libs-exec\$abi\hevsockstun" "$dir\libs\$abi\libhevsockstun.so" -Force
    }

    Get-ChildItem "$dir\libs" -Recurse -Filter *.so | ForEach-Object {
        "{0,-14} {1,-28} {2,8:N0} KB" -f $_.Directory.Name, $_.Name, ($_.Length / 1KB)
    }
} finally {
    Pop-Location -ErrorAction SilentlyContinue
    if (Test-Path "$tmp\jni\hev-socks5-tunnel") {
        # Remove the junction before the tree, or Remove-Item walks into the real sources.
        [System.IO.Directory]::Delete("$tmp\jni\hev-socks5-tunnel")
    }
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
