
package org.runnerup.pwr;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;


public class MockPWRProvider implements PWRProvider {

    private PWRClient pwrClient = null;
    private Handler pwrClientHandler = null;
    public static final String NAME = "MockPWR";

    public MockPWRProvider(Context ctx) {
    }

    @Override
    public String getName() {
        return "MockPWR";
    }

    @Override
    public String getProviderName() {
        return "MockPWR";
    }

    @Override
    public void open(Handler handler, PWRClient pwrClient) {
        this.pwrClient = pwrClient;
        this.pwrClientHandler = handler;
        pwrClient.onOpenResult(true);
    }

    @Override
    public void close() {
    }

    private boolean mIsScanning = false;

    @Override
    public boolean isScanning() {
        return mIsScanning;
    }

    private final Runnable fakeScanResult = new Runnable() {
        int count = 0;

        @Override
        public void run() {
            if (mIsScanning) {
                String dev = "00:43:A8:23:11:"
                        + String.format("%02X", System.currentTimeMillis() % 256);
                pwrClient.onScanResult(PWRDeviceRef.create(NAME, getName(), dev));
                if (++count < 3) {
                    pwrClientHandler.postDelayed(fakeScanResult, 3000);
                    return;
                }
            }
            count = 0;
        }
    };

    @Override
    public void startScan() {
        mIsScanning = true;
        pwrClientHandler.postDelayed(fakeScanResult, 3000);
    }

    @Override
    public void stopScan() {
        mIsScanning = false;
    }

    private boolean mIsConnecting = false;
    private boolean mIsConnected = false;

    @Override
    public boolean isConnected() {
        return mIsConnected;
    }

    @Override
    public boolean isConnecting() {
        return mIsConnecting;
    }

    @Override
    public void connect(PWRDeviceRef ref) {
        if (mIsConnected)
            return;

        if (mIsConnecting)
            return;

        mIsConnecting = true;
        pwrClientHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mIsConnecting) {
                    mIsConnected = true;
                    mIsConnecting = false;
                    pwrClient.onConnectResult(true);
                    pwrClientHandler.postDelayed(pwrUpdate, 750);
                }
            }
        }, 3000);
    }

    private final Runnable pwrUpdate = new Runnable() {
        @Override
        public void run() {
            pwrValue = (int) (150 + 40 * Math.random());
            pwrTimestamp = System.currentTimeMillis();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                pwrElapsedRealtime = SystemClock.elapsedRealtimeNanos();
            } else {
                final int NANO_IN_MILLI = 1000000;
                pwrElapsedRealtime = SystemClock.elapsedRealtime() * NANO_IN_MILLI;
            }
            if (mIsConnected) {
                pwrClientHandler.postDelayed(pwrUpdate, 750);
            }
        }
    };

    @Override
    public void disconnect() {
        mIsConnecting = false;
        mIsConnected = false;
    }

    private int pwrValue = 0;
    private long pwrTimestamp = 0;
    private long pwrElapsedRealtime = 0;

    @Override
    public int getPWRValue() {
        return pwrValue;
    }

    @Override
    public long getPWRValueTimestamp() {
        return pwrTimestamp;
    }

    @Override
    public long getPWRValueElapsedRealtime() {
        return this.pwrElapsedRealtime;
    }

    @Override
    public PWRData getPWRData() {
        if (pwrValue <= 0) {
            return null;
        }

        return new PWRData().setPower(pwrValue).setTimestampEstimate(pwrTimestamp);
    }

    @Override
    public int getBatteryLevel() {
        return (int) (100 * Math.random());
    }

    @Override
    public boolean isBondingDevice() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean startEnableIntent(Activity activity, int requestCode) {
        return false;
    }
}
