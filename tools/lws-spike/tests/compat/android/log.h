// SPDX-License-Identifier: GPL-3.0-or-later
// Host-only public Android logging shim for the separate production reference.
// Routing, TLS, persistence and policy source are compiled without
// modification.
#ifndef SPIKE_ANDROID_LOG_SHIM_H
#define SPIKE_ANDROID_LOG_SHIM_H
enum {
  ANDROID_LOG_UNKNOWN,
  ANDROID_LOG_DEFAULT,
  ANDROID_LOG_VERBOSE,
  ANDROID_LOG_DEBUG,
  ANDROID_LOG_INFO,
  ANDROID_LOG_WARN,
  ANDROID_LOG_ERROR,
  ANDROID_LOG_FATAL,
  ANDROID_LOG_SILENT
};
// The public Android log ABI requires variadic arguments. This host-only shim
// does no va_arg traversal or formatting; changing the signature changes the
// unmodified production translation unit's API.
static inline int __android_log_print(
    int priority, const char *tag, // NOSONAR - public Android variadic ABI
    const char *format, ...) {     // NOSONAR - public Android variadic ABI
  (void)priority;
  (void)tag;
  (void)format;
  return 0;
}
#endif
