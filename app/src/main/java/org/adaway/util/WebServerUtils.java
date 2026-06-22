/*
 * Copyright (C) 2011-2012 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * This file is part of AdAway.
 *
 * AdAway is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AdAway is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AdAway.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.adaway.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.security.KeyChain;
import android.view.ContextThemeWrapper;
import android.widget.Toast;

import androidx.annotation.StringRes;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLHandshakeException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

import static org.adaway.model.root.ShellUtils.isBundledExecutableRunning;
import static org.adaway.model.root.ShellUtils.killBundledExecutable;
import static org.adaway.model.root.ShellUtils.runBundledExecutable;

/**
 * This class is an utility class to control web server execution.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class WebServerUtils {
    public static final String TEST_URL = "https://localhost/internal-test";
    private static final String WEB_SERVER_EXECUTABLE = "webserver";
    private static final String LOCALHOST_CERTIFICATE = "localhost-2410.crt";

    /**
     * Start the web server.
     * <p>
     * The native server binary checks for its TLS certificate on startup and
     * generates a fresh per-device self-signed CA certificate if one is not
     * present, so no certificate generation step is needed here — and
     * crucially, no blocking root-shell call is made on the UI thread.
     *
     * @param context The application context.
     */
    public static void startWebServer(Context context) {
        Timber.d("Starting web server…");
        Path resourcePath = getResourcePath(context);
        ensureStaticResources(context, resourcePath);
        String parameters = "--resources " + resourcePath.toAbsolutePath() + " > /dev/null 2>&1";
        runBundledExecutable(context, WEB_SERVER_EXECUTABLE, parameters);
    }

    /** Stop the web server. */
    public static void stopWebServer() {
        killBundledExecutable(WEB_SERVER_EXECUTABLE);
    }

    /**
     * @return {@code true} if the web server process is currently running.
     */
    public static boolean isWebServerRunning() {
        return isBundledExecutableRunning(WEB_SERVER_EXECUTABLE);
    }

    /**
     * Get the web server state description resource id.
     *
     * @return A string resource describing the current web server state.
     */
    @StringRes
    public static int getWebServerState() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(TEST_URL).build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful() ?
                    R.string.pref_webserver_state_running_and_installed :
                    R.string.pref_webserver_state_not_running;
        } catch (SSLHandshakeException e) {
            return R.string.pref_webserver_state_running_not_installed;
        } catch (ConnectException e) {
            return R.string.pref_webserver_state_not_running;
        } catch (IOException e) {
            Timber.w(e, "Failed to test web server.");
            return R.string.pref_webserver_state_not_running;
        }
    }

    /**
     * Prompt the user to install the web server's self-signed CA certificate.
     * <p>
     * The certificate is generated per-device by the native server binary on
     * its first start (never bundled in the APK), so it will only be present
     * on disk after the web server has been enabled and run at least once.
     * If the certificate file is not yet present, a Toast guides the user to
     * enable the web server first.
     *
     * @param context The application context.
     */
    public static void installCertificate(Context context) {
        Path certFile = getResourcePath(context).resolve(LOCALHOST_CERTIFICATE);
        if (!Files.isRegularFile(certFile)) {
            Toast.makeText(context,
                    R.string.pref_webserver_certificate_enable_first,
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            // CertificateFactory handles PEM-encoded input directly (unlike
            // the deprecated javax.security.cert.X509Certificate.getInstance()
            // which requires raw DER bytes and breaks silently on PEM input,
            // causing Android to report "private key required").
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert;
            try (InputStream is = Files.newInputStream(certFile)) {
                cert = (X509Certificate) cf.generateCertificate(is);
            }
            Intent intent = KeyChain.createInstallIntent();
            intent.putExtra(KeyChain.EXTRA_CERTIFICATE, cert.getEncoded());
            intent.putExtra(KeyChain.EXTRA_NAME, "AdAway");
            context.startActivity(intent);
        } catch (IOException e) {
            Timber.w(e, "Failed to read certificate.");
        } catch (CertificateException e) {
            Timber.w(e, "Failed to parse or encode certificate.");
        }
    }

    public static void copyCertificate(ContextThemeWrapper wrapper, Uri uri) {
        Path certFile = getResourcePath(wrapper).resolve(LOCALHOST_CERTIFICATE);
        if (!Files.isRegularFile(certFile)) {
            Toast.makeText(wrapper,
                    R.string.pref_webserver_certificate_enable_first,
                    Toast.LENGTH_LONG).show();
            return;
        }
        ContentResolver contentResolver = wrapper.getContentResolver();
        try (OutputStream outputStream = contentResolver.openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IOException("Failed to open " + uri);
            }
            Files.copy(certFile, outputStream);
        } catch (IOException e) {
            Timber.w(e, "Failed to copy certificate.");
        }
    }

    private static Path getResourcePath(Context context) {
        return context.getFilesDir().toPath().resolve(WEB_SERVER_EXECUTABLE);
    }

    /**
     * Inflates only the static assets (icon, test page) into the resource
     * directory. Certificate generation is handled by the native server binary
     * itself on first start, so no blocking shell call is needed here.
     */
    private static void ensureStaticResources(Context context, Path target) {
        AssetManager assetManager = context.getAssets();
        try {
            if (!Files.isDirectory(target)) {
                Files.createDirectories(target);
            }
            inflateResource(assetManager, "test.html", target);
            for (int i = 0; i < 7; i++) {
                inflateResource(assetManager, String.format("img_%02d.webp", i), target);
            }
        } catch (IOException e) {
            Timber.w(e, "Failed to inflate web server resources.");
        }
    }

    private static void inflateResource(AssetManager assetManager, String resource, Path target)
            throws IOException {
        Path targetFile = target.resolve(resource);
        if (!Files.isRegularFile(targetFile)) {
            Files.copy(assetManager.open(resource), targetFile);
        }
    }
}
