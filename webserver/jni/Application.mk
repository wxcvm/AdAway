APP_ABI ?= arm64-v8a
APP_STL := c++_shared
APP_LDFLAGS += -Wl,-z,max-page-size=16384
