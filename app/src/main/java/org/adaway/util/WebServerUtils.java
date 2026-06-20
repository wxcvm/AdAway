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

import androidx.annotation.StringRes;

import org.adaway.R;
import org.adaway.helper.PreferenceHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.net.ssl.SSLHandshakeException;
import javax.security.cert.CertificateException;
import javax.security.cert.X509Certificate;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

import static org.adaway.model.root.ShellUtils.isBundledExecutableRunning;
import static org.adaway.model.root.ShellUtils.killBundledExecutable;
import static org.adaway.model.root.ShellUtils.runBundledExecutable;
import static org.adaway.model.root.ShellUtils.runBundledExecutableSync;

/**
 * This class is an utility class to control web server execution.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class WebServerUtils {
    public static final String TEST_URL = "https://localhost/internal-test";
    private static final String WEB_SERVER_EXECUTABLE = "webserver";
    private static final String LOCALHOST_CERTIFICATE = "localhost-2410.crt";
    private static final String LOCALHOST_CERTIFICATE_KEY = "localhost-2410.key";

    /**
     * Start the web server in new thread with RootTools
     *
     * @param context The application context.
     */
    public static void startWebServer(Context context) {
        Timber.d("Starting web server…");

        Path resourcePath = getResourcePath(context);
        ensureResources(context, resourcePath);

        String parameters = "--resources " + resourcePath.toAbsolutePath() +
                (PreferenceHelper.getWebServerIcon(context) ? " --icon" : "") +
                " > /dev/null 2>&1";
        runBundledExecutable(context, WEB_SERVER_EXECUTABLE, parameters);
    }

    /**
     * Stop the web server.
     */
    public static void stopWebServer() {
        killBundledExecutable(WEB_SERVER_EXECUTABLE);
    }

    /**
     * Checks if web server is running
     *
     * @return <code>true</code> if webs server is running, <code>false</code> otherwise.
     */
    public static boolean isWebServerRunning() {
        return isBundledExecutableRunning(WEB_SERVER_EXECUTABLE);
    }

    /**
     * Get the web server state description.
     *
     * @return The web server state description.
     */
    @StringRes
    public static int getWebServerState() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(TEST_URL)
                .build();
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
     * Prompt user to install web server certificate.
     * <p>
     * The certificate is generated locally for this install/device the first
     * time it is needed (see {@link #ensureResources}), rather than being a
     * fixed certificate bundled with the app: a certificate shared by every
     * install would mean its private key is effectively public (it ships
     * inside the APK), letting anyone impersonate any site to any device
     * that trusts it.
     *
     * @param context The application context.
     */
    public static void installCertificate(Context context) {
        Path resourcePath = getResourcePath(context);
        ensureResources(context, resourcePath);
        Path certFile = resourcePath.resolve(LOCALHOST_CERTIFICATE);
        try {
            byte[] bytes = Files.readAllBytes(certFile);
            X509Certificate x509 = X509Certificate.getInstance(bytes);
            Intent intent = KeyChain.createInstallIntent();
            intent.putExtra(KeyChain.EXTRA_CERTIFICATE, x509.getEncoded());
            intent.putExtra(KeyChain.EXTRA_NAME, "AdAway");
            context.startActivity(intent);
        } catch (IOException e) {
            Timber.w(e, "Failed to read certificate.");
        } catch (CertificateException e) {
            Timber.w(e, "Failed to parse certificate.");
        }
    }

    public static void copyCertificate(ContextThemeWrapper wrapper, Uri uri) {
        Path resourcePath = getResourcePath(wrapper);
        ensureResources(wrapper, resourcePath);
        Path certFile = resourcePath.resolve(LOCALHOST_CERTIFICATE);
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
     * Makes sure the web server's resource directory exists, has the static
     * assets (icon, test page), and has a certificate/key pair - generating
     * a fresh one on this device if it doesn't already have one.
     */
    private static void ensureResources(Context context, Path target) {
        AssetManager assetManager = context.getAssets();
        try {
            if (!Files.isDirectory(target)) {
                Files.createDirectories(target);
            }
            inflateResource(assetManager, "icon.svg", target);
            inflateResource(assetManager, "test.html", target);
        } catch (IOException e) {
            Timber.w(e, "Failed to inflate web server resources.");
        }
        ensureCertificateGenerated(context, target);
    }

    /**
     * Generates this device's web server certificate/key pair if it doesn't
     * already exist. Idempotent: once generated, the same pair is reused for
     * the lifetime of the app install, so the certificate the user installs
     * in their system trust store keeps matching what the server actually
     * presents.
     */
    private static void ensureCertificateGenerated(Context context, Path resourcePath) {
        Path certFile = resourcePath.resolve(LOCALHOST_CERTIFICATE);
        Path keyFile = resourcePath.resolve(LOCALHOST_CERTIFICATE_KEY);
        if (Files.isRegularFile(certFile) && Files.isRegularFile(keyFile)) {
            return;
        }
        Timber.d("Generating web server certificate…");
        String parameters = "--gen-cert " + resourcePath.toAbsolutePath();
        boolean success = runBundledExecutableSync(context, WEB_SERVER_EXECUTABLE, parameters);
        if (!success || !Files.isRegularFile(certFile) || !Files.isRegularFile(keyFile)) {
            Timber.w("Failed to generate web server certificate.");
        }
    }

    private static void inflateResource(AssetManager assetManager, String resource, Path target) throws IOException {
        Path targetFile = target.resolve(resource);
        if (!Files.isRegularFile(targetFile)) {
            Files.copy(assetManager.open(resource), targetFile);
        }
    }
}
