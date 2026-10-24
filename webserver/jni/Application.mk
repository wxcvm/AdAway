APP_ABI ?= arm64-v8a
APP_STL := c++_shared
ifeq ($(APP_ABI),riscv64)
# Only the supplemental Graphics Path JNI target uses C++. Avoid introducing a
# dependency on the NDK's prebuilt 4 KiB RISC-V libc++_shared.so.
APP_STL := c++_static
endif
APP_LDFLAGS += -Wl,-z,max-page-size=16384
