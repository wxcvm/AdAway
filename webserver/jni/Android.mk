
LOCAL_PATH := $(call my-dir)
OPENSSL_BUILT_DIR := $(LOCAL_PATH)/../build/official-openssl/arm64-v8a


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
# Build stub library in order to declare shared library to include to AAR
#

include $(CLEAR_VARS)

LOCAL_MODULE           := webserver_exec
LOCAL_SRC_FILES        := stub.c

include $(BUILD_SHARED_LIBRARY)


#
# Build webserver executable
#

include $(CLEAR_VARS)

LOCAL_CFLAGS	       := -D MG_ENABLE_IPV6 -DMG_TLS=MG_TLS_OPENSSL -std=c99 -O2 -W -Wall -pthread -pipe $(COPT)
LOCAL_MODULE           := webserver
LOCAL_SRC_FILES        := webserver.c mongoose/mongoose.c
LOCAL_STATIC_LIBRARIES := official_openssl_ssl official_openssl_crypto
LOCAL_LDLIBS           := -llog -ldl

include $(BUILD_EXECUTABLE)

#
# NOTE: Stub library binary is replaced by webserver executable at the end of ndkbuild by gradle build script
#


# OpenSSL is statically linked; no outdated Prefab OpenSSL runtime dependency.
