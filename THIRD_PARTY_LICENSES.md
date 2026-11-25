# Licenses

AdAway is licensed under the GPLv3+.  
The file [LICENSE.md](LICENSE.md) includes the full license text.

## AdAway Application

AdAway is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

AdAway is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  
See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with AdAway.  
If not, see <https://www.gnu.org/licenses/>.

## Third-Party Libraries

* Android Jetpack  
  https://developer.android.com/jetpack/  
  Apache License v2

* DNS66  
  https://github.com/julian-klode/dns66  
  GPLv3

* dnsjava  
  https://github.com/dnsjava/dnsjava  
  BSD-3-Clause License

* Guava  
  https://github.com/google/guava  
  Apache License v2

* libsu  
  https://github.com/topjohnwu/libsu  
  Apache License v2

* Material Components for Android  
  https://github.com/material-components/material-components-android  
  Apache License v2

* Mongoose Webserver (vendored source; see `webserver/jni/mongoose/LICENSE` and `mongoose.h`)  
  https://github.com/cesanta/mongoose  
  GPL-2.0-only OR commercial license; **GPLv3 compatibility review pending**.

* OkHttp  
  https://github.com/square/okhttp  
  Apache License v2

* OpenSSL 3.5.9 (official source build)  
  https://github.com/openssl/openssl/tree/openssl-3.5.9  
  Apache License 2.0 (see [OpenSSL 3.5.9 LICENSE.txt](https://github.com/openssl/openssl/blob/openssl-3.5.9/LICENSE.txt)); the OpenSSL/SSLeay license shown in older AdAway notices does **not** describe this 3.x build.

* Pcap4J  
  https://github.com/kaitoy/pcap4j  
  MIT License

* Sentry Java  
  https://github.com/getsentry/sentry-java  
  BSD 3-Clause License

* SLF4J  
  https://www.slf4j.org/  
  MIT License

* Tcpdump/Libpcap  
  https://www.tcpdump.org/  
  BSD 3-Clause License

## Images

* icon.svg, banner.svg  
  AdAway by Dominik Schürmann  
  New version by Alin Ţoţea-Radu  
  GPLv3

* Menu Icons  
  Original Android Icons

## License compatibility review (unresolved)

The bundled `webserver/jni/mongoose/mongoose.h` declares `GPL-2.0-only OR commercial`, while the AdAway application uses GPL-3.0-or-later. `webserver/jni/Android.mk` compiles `mongoose/mongoose.c` with `webserver.c` into one executable. GPL-2.0-only and GPLv3 are generally incompatible for a combined derivative work unless additional permission or a qualifying exception applies. **This notice is not a grant of extra rights and does not resolve that issue.** Confirm licensing with the rights holders or use a compatible replacement before distributing affected binaries.

This document is a third-party attribution summary, not a substitute for the full license and notice texts required by each component. Verify the final release dependency inventory and accompanying notices against actual packaged artifacts.
