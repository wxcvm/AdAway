package org.adaway.model.traffic;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.adaway.util.ListenerSettings;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ListenerSettingsDeviceTest {
    private Process probe(String... arguments) throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        String directory=context.getApplicationInfo().nativeLibraryDir;
        ArrayList<String> command=new ArrayList<>();
        command.add(directory+"/liblistener_probe.so");
        command.add("--check-listen"); command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH",directory);
        return builder.start();
    }
    @Test public void sharedNativeStartupProbeDetectsBothFamiliesWithoutRoot() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        File resource=new File(context.getCacheDir(),"listener-probe-must-not-create");
        assertFalse(resource.exists());
        for (String ip : new String[]{"127.0.0.1","::1"}) {
            try (ServerSocket occupied=new ServerSocket(0,1,InetAddress.getByName(ip));
                 ServerSocket other=new ServerSocket(0,1,InetAddress.getByName(ip))) {
                int http=occupied.getLocalPort(),https=other.getLocalPort();
                other.close();
                Process process=probe("--bind-ip",ip,"--http-port",""+http,"--https-port",""+https);
                assertTrue(process.waitFor(10,TimeUnit.SECONDS));
                String output=new String(com.google.common.io.ByteStreams.toByteArray(process.getInputStream()),StandardCharsets.UTF_8).trim();
                JSONObject report=new JSONObject(output);
                assertEquals(output,3,process.exitValue());
                assertEquals(output,1,report.getInt("http"));
                assertEquals(output,0,report.getInt("https"));
            }
        }
        Process invalid=probe("--bind-ip","localhost");
        assertTrue(invalid.waitFor(10,TimeUnit.SECONDS));
        assertEquals(2,invalid.exitValue());
        assertFalse(resource.exists());
        assertFalse(ListenerSettings.isLiteral("127.0.0.1; touch /tmp/unwanted"));
    }
}
