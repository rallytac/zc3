package com.rallytac.zc3;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity
{
    static {
        System.loadLibrary("zc3");
    }

    public native void nativeStartAudio(int sessionId);
    public native void nativeStopAudio();

    private final int NET_PORT = 43000;
    private final int SAMPLE_RATE = 16000;

    private final String SP_REMOTE_ADDRESS = "remoteAddress";

    private static final int CAMERA_REQUEST = 1888;
    private AudioRecord rec;
    private AudioTrack spk;
    private AcousticEchoCanceler aec = null;
    private AudioManager audioManager = null;
    private MyRecordingThread recordingThread = null;
    private MyPlayoutThread playoutThread = null;
    private ArrayList<short[]> outboundBuffers = new ArrayList<>();
    private ArrayList<short[]> inboundBuffers = new ArrayList<>();
    private MySenderThread senderThread = null;
    private MyReceiverThread receiverThread = null;
    private boolean micOn = false;
    private boolean netOn = false;
    private boolean audioOn = false;
    //private MulticastSocket socket = null;
    private DatagramSocket socket = null;
    private boolean aecOn = false;
    private String remoteAddr;
    private SharedPreferences sp = null;
    private SharedPreferences.Editor spEd = null;
    private Timer uiUpdateTimer = null;

    private long netPacketsIn = 0;
    private long netPacketsOut = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d("Main", machineInfo());

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        checkForPermissions();
    }

    private void checkForPermissions()
    {
        String[] required = new String[]{
                "android.permission.CAMERA",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.RECORD_AUDIO",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.MODIFY_AUDIO_SETTINGS",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.CHANGE_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE",
                "android.permission.CHANGE_WIFI_MULTICAST_STATE",
                "android.permission.CHANGE_WIFI_STATE",
                "android.permission.INTERNET"
        };

        List<String> askFor = null;

        for(String s : required)
        {
            int permission = ActivityCompat.checkSelfPermission(this, s);
            if(permission != PackageManager.PERMISSION_GRANTED)
            {
                if(askFor == null)
                {
                    askFor = new ArrayList<>();
                }
                askFor.add(s);
            }
        }

        if(askFor != null)
        {
            String[] requested = new String[askFor.size()];
            int x = 0;

            for(String s : askFor)
            {
                requested[x] = s;
                x++;
            }

            ActivityCompat.requestPermissions(this, requested, 1);
        }
        else
        {
            doInit();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allAllowed = true;

        for (int grantResult : grantResults)
        {
            if (grantResult != PackageManager.PERMISSION_GRANTED)
            {
                allAllowed = false;
                break;
            }
        }

        if(!allAllowed)
        {
            finish();
        }
        else
        {
            doInit();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void doInit()
    {
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        spEd = sp.edit();

        String ip = getIpAddressOfNic("wlan0");
        setTitle("zc3 @ " + ip);

        ((EditText)findViewById(R.id.etRemote)).setText(sp.getString(SP_REMOTE_ADDRESS, ""));

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);

        startUiUpdateTimer();
    }

    public void onClickNetworkingSwitch(View view)
    {
        netOn = ((Switch)view).isChecked();
        if(netOn)
        {
            startNetworking();
        }
        else
        {
            stopNetworking();
        }
    }

    public void onClickAudioSwitch(View view)
    {
        audioOn = ((Switch)view).isChecked();
        if(audioOn)
        {
            startAudio();
        }
        else
        {
            stopAudio();
        }
    }

    public void onClickMicSwitch(View view)
    {
        micOn = ((Switch)view).isChecked();
    }

    public void onClickAecSwitch(View view)
    {
        aecOn = ((Switch)view).isChecked();
    }

    private class MyRecordingThread extends Thread
    {
        final String TAG = MyRecordingThread.class.getSimpleName();

        private boolean running = true;
        private AudioRecord recorder = null;
        private AcousticEchoCanceler echoCanceller = null;
        private int minBufferSizeIn = 0;

        MyRecordingThread(AudioRecord recorder, AcousticEchoCanceler echoCanceller, int minBufferSizeIn)
        {
            this.recorder = recorder;
            this.echoCanceller = echoCanceller;
            this.minBufferSizeIn = minBufferSizeIn;
        }

        public void close()
        {
            running = false;
            try {
                join();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void run()
        {
            short[] audioData = new short[minBufferSizeIn];
            recorder.startRecording();

            while( running )
            {
                try {
                    int numRead = recorder.read(audioData, 0, minBufferSizeIn);
                    //Log.d(TAG, "MyRecordingThread read " + numRead + " samples");
                    if(micOn && numRead > 0) {
                        short[] queuedBuffer = new short[numRead];
                        System.arraycopy(audioData, 0, queuedBuffer, 0, numRead);
                        synchronized (outboundBuffers) {
                            outboundBuffers.add(queuedBuffer);
                            if(outboundBuffers.size() > 5) {
                                Log.d(TAG, "MyRecordingThread removed a buffer");
                                outboundBuffers.remove(0);
                            }
                        }

                        senderThread.wakeUp();
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MyPlayoutThread extends Thread
    {
        final String TAG = MyPlayoutThread.class.getSimpleName();

        private boolean running = true;
        private AudioTrack track = null;
        private AcousticEchoCanceler echoCanceller = null;
        private int minBufferSizeIn = 0;
        private Object sig = new Object();

        MyPlayoutThread(AudioTrack track, AcousticEchoCanceler echoCanceller, int minBufferSizeIn)
        {
            this.track = track;
            this.echoCanceller = echoCanceller;
            this.minBufferSizeIn = minBufferSizeIn;
        }

        public void wakeUp()
        {
            synchronized (sig) {
                sig.notifyAll();
            }
        }

        private void waitForWakeup() {
            try {
                synchronized (sig) {
                    sig.wait();
                }
            }
            catch (Exception e) {
            }
        }

        public void close()
        {
            running = false;
            wakeUp();

            try {
                join();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void run()
        {
            track.play();

            while( running )
            {
                waitForWakeup();
                if(!running) {
                    break;
                }

                short[] buf = null;

                while( running ) {
                    try {
                        synchronized (inboundBuffers) {
                            if (inboundBuffers.size() > 0) {
                                buf = inboundBuffers.remove(0);
                            } else {
                                buf = null;
                            }
                        }

                        if (buf != null) {
                            int numWritten = track.write(buf, 0, buf.length);
                            //Log.d(TAG, "wrote " + numWritten + " samples of " + buf.length);
                        } else {
                            //Log.d(TAG, "MyPlayoutThread has no buffers");
                            break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    byte [] ShortToByte_ByteBuffer_Method(short [] input)
    {
        int index;
        int iterations = input.length;

        ByteBuffer bb = ByteBuffer.allocate(input.length * 2);

        for(index = 0; index != iterations; ++index)
        {
            bb.putShort(input[index]);
        }

        return bb.array();
    }

    private class MySenderThread extends Thread
    {
        final String TAG = MySenderThread.class.getSimpleName();

        private boolean running = true;
        private Object sig = new Object();

        MySenderThread()
        {
        }

        public void wakeUp()
        {
            synchronized (sig) {
                sig.notifyAll();
            }
        }

        private void waitForWakeup() {
            try {
                synchronized (sig) {
                    sig.wait();
                }
            }
            catch (Exception e) {
            }
        }

        public void close()
        {
            running = false;
            wakeUp();

            try {
                join();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void run()
        {
            try
            {
                InetAddress dstAddr = InetAddress.getByName(remoteAddr);

                while( running )
                {
                    waitForWakeup();
                    if(!running) {
                        break;
                    }

                    short[] buf = null;

                    while( running ) {
                        try {
                            synchronized (outboundBuffers) {
                                if (outboundBuffers.size() > 0) {
                                    buf = outboundBuffers.remove(0);
                                } else {
                                    buf = null;
                                }
                            }

                            if (buf != null) {
                                byte[] bytebuf = new byte[buf.length * 2];
                                ByteBuffer.wrap(bytebuf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf);

                                int toGo = bytebuf.length;
                                int toSend = 0;
                                int sent = 0;

                                while( toGo > 0)
                                {
                                    if(toGo < 128) {
                                        toSend = toGo;
                                    }
                                    else
                                    {
                                        toSend = 128;
                                    }

                                    toGo -= toSend;

                                    DatagramPacket p = new DatagramPacket(bytebuf, sent, toSend, dstAddr, NET_PORT);
                                    socket.send(p);
                                    //Log.d(TAG, "MySenderThread wrote " + bytebuf.length + " bytes");

                                    sent += toSend;

                                    netPacketsOut++;
                                }
                            } else {
                                //Log.d(TAG, "MyPlayoutThread has no buffers");
                                break;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

        }
    }

    private class MyReceiverThread extends Thread
    {
        final String TAG = MyReceiverThread.class.getSimpleName();

        private boolean running = true;
        private Object sig = new Object();

        MyReceiverThread()
        {
        }

        public void wakeUp()
        {
            synchronized (sig) {
                sig.notifyAll();
            }
        }

        private void waitForWakeup() {
            try {
                synchronized (sig) {
                    sig.wait();
                }
            }
            catch (Exception e) {
            }
        }

        public void close()
        {
            running = false;

            try {
                socket.close();
            }
            catch (Exception e) {
                e.printStackTrace();
            }

            wakeUp();

            try {
                join();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void run()
        {
            byte[] buf = new byte[1024];

            try
            {
                while( running )
                {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    if(!running)
                    {
                        break;
                    }

                    //Log.d(TAG, "received " + p.getLength());

                    short[] shorts = new short[p.getLength()/2];
                    ByteBuffer.wrap(p.getData(), 0, p.getLength()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);

                    netPacketsIn++;

                    synchronized (inboundBuffers) {
                        inboundBuffers.add(shorts);
                    }

                    playoutThread.wakeUp();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

        }
    }

    @SuppressLint("MissingPermission")
    private void startAudio()
    {
        int minBufferSizeIn = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSizeIn);

        int recSessionId = rec.getAudioSessionId();

        if(aecOn)
        {
            if(AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(recSessionId);
            }
            else {
                Log.e("Main", "no aec available");
            }
        }
        else
        {
            Log.w("Main", "no aec being used");
        }

        spk = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                //.setBufferSizeInBytes(minBufferSizeIn)
                .setSessionId(recSessionId)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        inboundBuffers.clear();
        outboundBuffers.clear();

        if(aec != null)
        {
            aec.setEnabled(true);
        }

        playoutThread = new MyPlayoutThread(spk, aec, minBufferSizeIn);
        playoutThread.start();

        recordingThread = new MyRecordingThread(rec, aec, minBufferSizeIn);
        recordingThread.start();
    }

    private void stopAudio()
    {
        if(recordingThread != null) recordingThread.close();
        if(playoutThread != null) playoutThread.close();

        if(aec != null ) aec.release();
        if(rec != null ) rec.release();
        if(spk != null ) spk.release();
    }

    private void startNetworking()
    {
        stopNetworking();

        netPacketsIn = 0;
        netPacketsOut = 0;
        updateUi();

        remoteAddr = ((EditText)findViewById(R.id.etRemote)).getText().toString().trim();
        spEd.putString(SP_REMOTE_ADDRESS, remoteAddr);
        spEd.apply();

        try {
            if(InetAddress.getByName(remoteAddr).isMulticastAddress()) {
                socket = new MulticastSocket(NET_PORT);
                ((MulticastSocket)socket).setLoopbackMode(true);
                ((MulticastSocket)socket).joinGroup(InetAddress.getByName(remoteAddr));
            }
            else {
                socket = new DatagramSocket(NET_PORT);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

        receiverThread = new MyReceiverThread();
        receiverThread.start();

        senderThread = new MySenderThread();
        senderThread.start();
    }

    private void stopNetworking()
    {
        if(receiverThread != null) receiverThread.close();
        if(senderThread != null) senderThread.close();

        receiverThread = null;
        senderThread = null;
    }

    private String machineInfo()
    {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        int densityDpi = (int) (dm.density * 160f);
        //double x = Math.pow(mWidthPixels / dm.xdpi, 2);
        //double y = Math.pow(mHeightPixels / dm.ydpi, 2);
        //int screenInches = Math.sqrt(x + y);
        //int rounded = df2.format(screenInches);


        StringBuilder sb = new StringBuilder();

        sb.append(String.format(getString(R.string.mi_sys_id), Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)));
        sb.append(String.format(getString(R.string.mi_sys_sizing_type), getString(R.string.ui_sizing_type)));

        sb.append(getString(R.string.mi_hdr_device));
        sb.append(String.format(getString(R.string.mi_device_manufacturer), Build.MANUFACTURER));
        sb.append(String.format(getString(R.string.mi_device_id), Build.ID));
        sb.append(String.format(getString(R.string.mi_device_brand), Build.BRAND));
        sb.append(String.format(getString(R.string.mi_device_model), Build.MODEL));
        sb.append(String.format(getString(R.string.mi_device_board), Build.BOARD));
        sb.append(String.format(getString(R.string.mi_device_hardware), Build.HARDWARE));
        sb.append(String.format(getString(R.string.mi_device_serial), Build.SERIAL));
        sb.append(String.format(getString(R.string.mi_device_bootloader), Build.BOOTLOADER));
        sb.append(String.format(getString(R.string.mi_device_user), Build.USER));
        sb.append(String.format(getString(R.string.mi_device_host), Build.HOST));
        sb.append(String.format(getString(R.string.mi_device_build_time), Long.toString(Build.TIME)));
        sb.append(String.format(getString(R.string.mi_device_version_release), Build.VERSION.RELEASE));
        sb.append(String.format(getString(R.string.mi_device_sdk_int), Build.VERSION.SDK_INT));

        return sb.toString();
    }

    private String getIpAddressOfNic(String nic)
    {
        String rc = null;

        try
        {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            while( rc == null && interfaces.hasMoreElements() )
            {
                NetworkInterface networkInterface = interfaces.nextElement();

                String nm = networkInterface.getName();
                Log.d("Main", "nm='" + nm + "'");

                if(nm.compareTo(nic) == 0)
                {
                    //String dn = networkInterface.getDisplayName();

                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();

                    if(inetAddresses.hasMoreElements())
                    {
                        while (rc == null && inetAddresses.hasMoreElements())
                        {
                            InetAddress ina = inetAddresses.nextElement();

                            if (ina instanceof Inet4Address)
                            {
                                String sAddress = ina.toString();
                                if (sAddress != null && sAddress.length() > 1)
                                {
                                    sAddress = sAddress.substring(1);
                                }

                                rc = sAddress;
                                break;
                            }
                        }
                    }
                }
            }
        }
        catch(Exception e)
        {
            rc = null;
        }

        if(rc == null)
        {
            rc = "";
        }

        return rc;
    }

    private void updateUi()
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Switch sw;

                sw = findViewById(R.id.swNet);
                sw.setText("Net:" + netPacketsIn + "/" + netPacketsOut);
            }
        });
    }

    private void startUiUpdateTimer()
    {
        if(uiUpdateTimer == null) {
            uiUpdateTimer = new Timer();
            uiUpdateTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    updateUi();
                }
            }, 0, 1000);
        }
    }

    private void stopUiUpdateTimer()
    {
        if(uiUpdateTimer != null) {
            uiUpdateTimer.cancel();
            uiUpdateTimer = null;
        }
    }
}
