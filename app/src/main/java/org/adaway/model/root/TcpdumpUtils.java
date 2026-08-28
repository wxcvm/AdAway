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

package org.adaway.model.root;

import android.content.Context;

import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.topjohnwu.superuser.ShellUtils.escapedString;
import static java.util.Collections.emptyList;
import static org.adaway.model.root.ShellUtils.isBundledExecutableRunning;
import static org.adaway.model.root.ShellUtils.killBundledExecutable;
import static org.adaway.model.root.ShellUtils.mergeAllLines;
import static org.adaway.model.root.ShellUtils.runBundledExecutable;

import timber.log.Timber;

class TcpdumpUtils {
    private static final String TCPDUMP_EXECUTABLE = "tcpdump";
    private static final String TCPDUMP_LOG = "dns_log.txt";
    private static final String TCPDUMP_HOSTNAME_REGEX = "(?:A\\?|AAAA\\?)\\s(\\S+)\\.\\s";
    private static final Pattern TCPDUMP_HOSTNAME_PATTERN = Pattern.compile(TCPDUMP_HOSTNAME_REGEX);

    /**
     * Private constructor.
     */
    private TcpdumpUtils() {

    }

    /**
     * Checks if tcpdump is running
     *
     * @return true if tcpdump is running
     */
    static boolean isTcpdumpRunning() {
        return isBundledExecutableRunning(TCPDUMP_EXECUTABLE);
    }

    /**
     * Start tcpdump tool.
     *
     * @param context The application context.
     * @return returns true if starting worked
     */
    static boolean startTcpdump(Context context) {
        Timber.d("Starting tcpdump...");
        checkSystemTcpdump();

        File file = getLogFile(context);
        try {
            // Create log file before using it with tcpdump if not exists
            if (!file.exists() && !file.createNewFile()) {
                return false;
            }
        } catch (IOException e) {
            Timber.e(e, "Problem while getting cache directory!");
            return false;
        }

        // "-i any": listen on any network interface
        // "-p": disable promiscuous mode (doesn't work anyway)
        // "-l": Make stdout line buffered. Useful if you want to see the data while
        // capturing it.
        // "-v": verbose
        // "-t": don't print a timestamp
        // "-s 0": capture first 512 bit of packet to get DNS content
        String parameters = "-i any -p -l -v -t -s 512 'udp dst port 53' >> " + file + " 2>&1";

        return runBundledExecutable(context, TCPDUMP_EXECUTABLE, parameters);
    }

    /**
     * Stop tcpdump.
     */
    static void stopTcpdump() {
        killBundledExecutable(TCPDUMP_EXECUTABLE);
    }

    /**
     * Check if tcpdump binary in bundled in the system.
     */
    static void checkSystemTcpdump() {
        try {
            Shell.Result result = Shell.cmd("tcpdump --version").exec();
            int exitCode = result.getCode();
            String output = mergeAllLines(result.getOut());
            String msg = "Tcpdump " + (
                            exitCode == 0 ?
                                    "present" :
                                    "missing (" + exitCode + ")"
                    ) + "\n" + output;
            Timber.i(msg);
        } catch (Exception exception) {
            Timber.w(exception, "Failed to check system tcpdump binary.");
        }
    }

    /**
     * Get the tcpdump log file.
     *
     * @param context The application context.
     * @return The tcpdump log file.
     */
    static File getLogFile(Context context) {
        return new File(context.getCacheDir(), TCPDUMP_LOG);
    }

    /**
     * Get the tcpdump log content.
     *
     * @param context The application context.
     * @return The tcpdump log file content.
     */
    static List<String> getLogs(Context context) {
        File logFile = getLogFile(context);
        // Check if the log file exists
        if (!logFile.exists()) {
            return emptyList();
        }
        /*
         * BUG FIX: the log file is written by tcpdump, which runs as root
         * (see runBundledExecutable). On a standard Android data partition
         * the app process cannot read a root-owned file inside its own
         * cache directory (dir perms are 700), so Files.lines() used to
         * fail with Permission denied and the DNS log page was always
         * empty. Read it through the same root shell instead.
         */
        Shell.Result result = Shell.cmd("cat " + logFile.getAbsolutePath()).exec();
        if (!result.isSuccess()) {
            Timber.e("Failed to read tcpdump log as root (exit code %d).", result.getCode());
            return emptyList();
        }
        return result.getOut().stream()
                .map(TcpdumpUtils::getTcpdumpHostname)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Delete log file of tcpdump.
     *
     * @param context The application context.
     */
    static boolean clearLogFile(Context context) {
        // Get the log file
        File file = getLogFile(context);
        // Check if file exists
        if (!file.exists()) {
            return true;
        }
        /*
         * BUG FIX: the DNS log file is written by tcpdump running as root
         * (see runBundledExecutable). On the same devices where getLogs()
         * had to switch to reading via a root shell (root-owned file inside
         * a 700-perm app cache dir is not writable by the app process), a
         * plain FileOutputStream here was silently failing — so "Clear log"
         * never actually cleared anything while still reporting success.
         * Truncate it through the same root shell instead, keeping
         * getLogs()/clearLogFile() symmetric.
         */
        Shell.Result result = Shell.cmd(": > " + escapedString(file.getAbsolutePath())).exec();
        if (!result.isSuccess()) {
            Timber.e("Failed to clear tcpdump log via root shell (exit code %d): %s",
                    result.getCode(), mergeAllLines(result.getErr()));
            return false;
        }
        // Return successfully clear the log file
        return true;
    }

    /**
     * Gets hostname out of tcpdump log line.
     *
     * @param input One line from dns log.
     * @return A hostname or {code null} if no DNS query in the input.
     */
    private static String getTcpdumpHostname(String input) {
        Matcher tcpdumpHostnameMatcher = TCPDUMP_HOSTNAME_PATTERN.matcher(input);
        if (tcpdumpHostnameMatcher.find()) {
            return tcpdumpHostnameMatcher.group(1);
        } else {
            Timber.d("Does not find: %s.", input);
            return null;
        }
    }
}
