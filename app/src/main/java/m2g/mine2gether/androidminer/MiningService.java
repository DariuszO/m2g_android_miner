/*
 *  Monero Miner App (c) 2018 Uwe Post
 *  based on the XMRig Monero Miner https://github.com/xmrig/xmrig
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 * /
 */
// Copyright (c) 2019, Mine2Gether.com
//
// Please see the included LICENSE file for more information.

package m2g.mine2gether.androidminer;

import static android.os.PowerManager.PARTIAL_WAKE_LOCK;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class MiningService extends Service {

    private static final String LOG_TAG = "MiningSvc";
    private final static String[] SUPPORTED_ARCHITECTURES = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"};
    Boolean mMiningServiceState = false;
    private Process process;
    private String configTemplate;
    private String privatePath;
    private OutputReaderThread outputHandler;
    private InputReaderThread inputHandler;
    private ProcessMonitor procMon;
    private PowerManager pm;
    private PowerManager.WakeLock wl;
    private int accepted = 0;
    private String speed = "0";
    private String lastAssetPath;
    private String lastOutput = "";
    private String assetExtension = "";
    private MiningServiceStateListener listener = null;

    private static String createCpuConfig(int cores, int threads, int intensity) {

        String cpuConfig = "";

        for (int i = 0; i < cores; i++) {
            for (int j = 0; j < threads; j++) {
                if (cpuConfig.equals("") == false) {
                    cpuConfig += ",";
                }
                cpuConfig += "[" + Integer.toString(intensity) + "," + Integer.toString(i) + "]";
            }
        }

        return "[" + cpuConfig + "]";
    }

    public static String getIpByHost(String hostName) {
        try {
            Log.i(LOG_TAG, hostName);
            return InetAddress.getByName(hostName).getHostAddress();
        } catch (UnknownHostException e) {
            Log.i(LOG_TAG, e.toString());
            return hostName;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        privatePath = getFilesDir().getAbsolutePath();
        Tools.deleteDirectoryContents(new File(privatePath));
    }

    public void setMiningServiceStateListener(MiningServiceStateListener listener) {
        if (this.listener != null) this.listener = null;
        this.listener = listener;
    }

    private void raiseMiningServiceStateChange(Boolean state) {
        mMiningServiceState = state;
        if (listener != null) listener.onStateChange(state);
    }

    private void raiseMiningServiceStatusChange(String status, String speed, Integer accepted) {
        if (listener != null) listener.onStatusChange(status, speed, accepted);
    }

    public Boolean getMiningServiceState() {
        return mMiningServiceState;
    }

    private void copyMinerFiles() {

        String abi = Tools.getABI();
        String assetPath = "";
        String libraryPath = "";
        String configPath = "";

        Log.i(LOG_TAG, "MINING SERVICE ABI: " + abi);

        assetExtension = PreferenceHelper.getName("assetExtension");

        if (Arrays.asList(SUPPORTED_ARCHITECTURES).contains(abi)) {
            assetPath = assetExtension + "/" + abi;
            libraryPath = "lib" + "/" + abi;
            configPath = assetExtension + "/config.json";
        } else {
            Log.i(LOG_TAG, "NO ASSET PATH");
        }

        Log.i(LOG_TAG, "ASSET PATH: " + assetPath);
        Log.i(LOG_TAG, "LAST ASSET PATH: " + lastAssetPath);
        Log.i(LOG_TAG, "ABI: " + abi);

        if (assetPath.equals(lastAssetPath) == false) {
            Tools.deleteDirectoryContents(new File(privatePath));
            Tools.copyDirectoryContents(this, libraryPath, privatePath);
            Tools.copyDirectoryContents(this, assetPath, privatePath);
            configTemplate = Tools.loadConfigTemplate(this, configPath);
            Tools.logDirectoryFiles(new File(privatePath));
            lastAssetPath = assetPath;
        }
    }

    public MiningConfig newConfig(String username, String pool, String pass, int cores, int threads, int intensity, String algo, String assetExtension) {

        MiningConfig config = new MiningConfig();

        config.username = username;
        config.pool = pool;
        config.cores = cores;
        config.threads = threads;
        config.intensity = intensity;
        config.pass = pass;
        config.algo = algo;
        config.assetExtension = assetExtension;

        config.legacyThreads = threads * cores;
        config.legacyIntensity = intensity;

        String[] poolParts = pool.split(":");
        config.poolHost = poolParts[0];
        config.poolPort = "";
        if (poolParts.length > 1) {
            config.poolPort = poolParts[1];
        }
        config.cpuConfig = createCpuConfig(cores, threads, intensity);

        return config;
    }

    @Override
    public void onDestroy() {
        stopMining();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MiningServiceBinder();
    }

    public void stopMining() {
        if (outputHandler != null) {
            outputHandler.interrupt();
            outputHandler = null;
        }

        if (inputHandler != null) {
            inputHandler.interrupt();
            inputHandler = null;
        }

        if (process != null) {
            process.destroy();
            process = null;
        }
    }

    public void startMining(MiningConfig config) {
        stopMining();
        new startMiningAsync().execute(config);
    }

    public void startMiningProcess(MiningConfig config) {

        Log.i(LOG_TAG, "starting...");

        if (process != null) {
            process.destroy();
            process = null;
        }

        if (wl != null) {
            if (wl.isHeld()) {
                wl.release(); //Wakelock
            }
            wl = null;
        }

        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PARTIAL_WAKE_LOCK, "app:sleeplock");
        wl.acquire();

        try {
            Tools.writeConfig(configTemplate, config, privatePath);

            String[] args = {"./" + assetExtension};

            ProcessBuilder pb = new ProcessBuilder(args);

            pb.directory(new File(privatePath));

            pb.environment().put("LD_LIBRARY_PATH", privatePath);

            pb.redirectErrorStream();

            accepted = 0;
            speed = "0";
            lastOutput = "";

            process = pb.start();

            outputHandler = new MiningService.OutputReaderThread(process.getInputStream(), PreferenceHelper.getName("miner"));
            outputHandler.start();

            inputHandler = new MiningService.InputReaderThread(process.getOutputStream());
            inputHandler.start();

            if (procMon != null) {
                procMon.interrupt();
                procMon = null;
            }
            procMon = new ProcessMonitor(process);
            procMon.start();

        } catch (Exception e) {
            Log.e(LOG_TAG, "exception:", e);
            Toast.makeText(this, e.getLocalizedMessage(), Toast.LENGTH_SHORT).show();
            process = null;
        }
    }

    public String getSpeed() {
        return speed;
    }

    public int getAccepted() {
        return accepted;
    }

    public String getOutput() {
        if (outputHandler != null && outputHandler.getOutput() != null) {
            lastOutput = outputHandler.getOutput().toString();
            return lastOutput;
        } else {
            return lastOutput;
        }
    }

    public void sendInput(String s) {
        if (inputHandler != null) {
            inputHandler.sendInput(s);
        }
    }

    public interface MiningServiceStateListener {
        public void onStateChange(Boolean state);

        public void onStatusChange(String status, String speed, Integer accepted);
    }

    public static class MiningConfig {
        String username, pool, pass, algo, assetExtension, cpuConfig, poolHost, poolPort;
        int cores, threads, intensity, legacyThreads, legacyIntensity;
    }

    public class MiningServiceBinder extends Binder {
        public MiningService getService() {
            return MiningService.this;
        }
    }

    class startMiningAsync extends AsyncTask<MiningConfig, Void, String> {

        private Exception exception;
        private MiningConfig config;

        protected String getPoolHost(String pool) {

            String[] hostParts = pool.split(":");

            if (hostParts.length == 2) {
                return getIpByHost(hostParts[0]) + ":" + hostParts[1];
            } else if (hostParts.length == 1) {
                return getIpByHost(hostParts[0]);
            } else {
                return pool;
            }
        }

        protected String doInBackground(MiningConfig... config) {

            try {
                this.config = config[0];
                this.config.pool = getPoolHost(this.config.pool);
                return "success";
            } catch (Exception e) {
                this.exception = e;
                return null;
            } finally {

            }
        }

        protected void onPostExecute(String result) {
            copyMinerFiles();
            startMiningProcess(this.config);
        }
    }

    private class ProcessMonitor extends Thread {

        Process proc;

        ProcessMonitor(Process proc) {
            this.proc = proc;
        }

        public void run() {
            try {

                raiseMiningServiceStateChange(true);
                if (proc != null) {
                    proc.waitFor();
                    Log.i(LOG_TAG, "process exit: " + proc.exitValue());
                }
                raiseMiningServiceStateChange(false);

            } catch (Exception e) {
                // assume problem with process and not running
                raiseMiningServiceStateChange(false);
                Log.e(LOG_TAG, "exception:", e);
            }
        }
    }

    private class OutputReaderThread extends Thread {

        private InputStream inputStream;
        private StringBuilder output = new StringBuilder();
        private BufferedReader reader;
        private String miner;

        OutputReaderThread(InputStream inputStream, String miner) {

            this.inputStream = inputStream;
            this.miner = miner;
        }

        private void processLogLine(String line) {
            output.append(line + System.lineSeparator());

            String lineCompare = line.toLowerCase();

            if (miner.equals(Config.miner_xmrig) || miner.equals(Config.miner_ninjarig) || miner.equals(Config.miner_xmrig_upx)) {

                if (lineCompare.contains("accepted")) {
                    accepted++;
                } else if (lineCompare.contains("speed")) {
                    String[] split = TextUtils.split(line, " ");
                    speed = split[5];
                    if (speed.equals("n/a")) {
                        speed = split[4];
                    }
                }

            } else if (miner.equals(Config.miner_violetminer)) {

                if (lineCompare.contains("share accepted")) {
                    accepted++;
                } else if (lineCompare.toLowerCase().contains("hashrate:")) {
                    String[] split = TextUtils.split(line, " ");
                    speed = split[2];
                }
            }

            if (output.length() > Config.logMaxLength)
                output.delete(0, output.indexOf(System.lineSeparator(), Config.logPruneLength) + 1);

            raiseMiningServiceStatusChange(line, speed, accepted);

        }

        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                while ((line = reader.readLine()) != null) {

                    Log.i(LOG_TAG, "miner: " + line);

                    processLogLine(line);

                    if (currentThread().isInterrupted()) return;
                }

            } catch (IOException e) {
                Log.w(LOG_TAG, "exception", e);
            }
        }

        public StringBuilder getOutput() {
            return output;
        }

    }

    private class InputReaderThread extends Thread {

        private OutputStream outputStream;
        private BufferedWriter writer;

        InputReaderThread(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        public void run() {
            try {
                writer = new BufferedWriter(new OutputStreamWriter(outputStream));

                while (true) {

                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {

                    }

                    if (currentThread().isInterrupted()) return;
                }

            } catch (Exception e) {
                Log.w(LOG_TAG, "exception", e);
            }
        }

        public void sendInput(String s) {

            try {
                writer.write(s);
                writer.flush();
            } catch (Exception e) {
                Log.w(LOG_TAG, "exception", e);
            }

        }

    }
}
