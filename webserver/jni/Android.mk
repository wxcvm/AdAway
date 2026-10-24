
LOCAL_PATH := $(call my-dir)
OPENSSL_BUILT_DIR := $(LOCAL_PATH)/../build/official-openssl/$(TARGET_ARCH_ABI)


# Prebuilt official OpenSSL 3.5 LTS archives, built from the pinned source
# checkout by webserver/scripts/build-official-openssl.sh.
include $(CLEAR_VARS)
LOCAL_MODULE := official_openssl_crypto
LOCAL_SRC_FILES := $(OPENSSL_BUILT_DIR)/lib/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(OPENSSL_BUILT_DIR)/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := official_openssl_ssl
LOCAL_SRC_FILES := $(OPENSSL_BUILT_DIR)/lib/libssl.a
LOCAL_EXPORT_C_INCLUDES := $(OPENSSL_BUILT_DIR)/include
include $(PREBUILT_STATIC_LIBRARY)

#
# Build webserver executable
#

include $(CLEAR_VARS)

LOCAL_CFLAGS	       := -D MG_ENABLE_IPV6 -DMG_IPV6_V6ONLY=1 -DMG_TLS=MG_TLS_OPENSSL -std=c99 -O2 -W -Wall -pthread -pipe $(COPT)
LOCAL_MODULE           := webserver
LOCAL_SRC_FILES        := webserver.c mongoose/mongoose.c
LOCAL_STATIC_LIBRARIES := official_openssl_ssl official_openssl_crypto
LOCAL_LDLIBS           := -llog -ldl

include $(BUILD_EXECUTABLE)

# Build the missing ABI of the exact graphics-path:1.0.1 JNI contract. Other
# ABIs retain the native library from Google's AAR, preventing duplicate names.
ifeq ($(TARGET_ARCH_ABI),riscv64)
GRAPHICS_PATH_SRC := $(LOCAL_PATH)/../build/official-graphics-path/source/src/main/cpp
include $(CLEAR_VARS)
LOCAL_MODULE := androidx.graphics.path
LOCAL_SRC_FILES := $(GRAPHICS_PATH_SRC)/Conic.cpp $(GRAPHICS_PATH_SRC)/PathIterator.cpp $(GRAPHICS_PATH_SRC)/pathway.cpp
LOCAL_C_INCLUDES := $(GRAPHICS_PATH_SRC)
# Match the official 1.0.1 flags and exported JNI surface; sources are unmodified.
LOCAL_CPPFLAGS := -std=c++17 -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables -fno-rtti -ffast-math -ffp-contract=fast -fvisibility-inlines-hidden -fvisibility=hidden -fomit-frame-pointer -ffunction-sections -fdata-sections -nostdlib++
LOCAL_LDFLAGS := -Wl,--version-script=$(GRAPHICS_PATH_SRC)/libandroidx.graphics.path.map -Wl,--hash-style=both -Wl,--gc-sections -Wl,-Bsymbolic-functions -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
endif

#
# The executable is packaged by the variant's generated jniLibs source.
# Never build a same-named placeholder: it can silently reach a release APK.
#


# OpenSSL is statically linked; no outdated Prefab OpenSSL runtime dependency.
