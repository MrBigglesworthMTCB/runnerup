/*
 * Copyright (C) 2013 jonas.oreland@gmail.com
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.runnerup.pwr;

import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Connects to a Bluetooth Low Energy module for Android versions >= 4.3
 *
 * @author jonas
 */

//This class must only be used with 4.3 or later, calling checkLibrary()
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)

public class AndroidBLEPWRProvider extends BtPWRBase implements PWRProvider {

    public static boolean checkLibrary(Context ctx) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2)
            return false;

        return ctx.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE);
    }

    static final String NAME = "AndroidBLE";
    private static final String DISPLAY_NAME = "Bluetooth SMART (BLE)";

    private static final UUID[] SCAN_UUIDS = {
        PWR_SERVICE
    };
    private final static boolean AVOID_SCAN_WITH_UUID;
    private final static boolean CONNECT_IN_OWN_THREAD_FROM_ON_LE_SCAN;

    static {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            // 4.3
            AVOID_SCAN_WITH_UUID = true;
            CONNECT_IN_OWN_THREAD_FROM_ON_LE_SCAN = true;
        } else {
            AVOID_SCAN_WITH_UUID = false;
            CONNECT_IN_OWN_THREAD_FROM_ON_LE_SCAN = false;
        }
    }

    private final Context context;
    private BluetoothAdapter btAdapter = null;
    private BluetoothGatt btGatt = null;
    private BluetoothDevice btDevice = null;
    private int pwrValue = 0;
    private long pwrTimestamp = 0;
    private long pwrElapsedRealtime = 0;
    private int batteryLevel = -1;
    private boolean hasBatteryService = false;

    private long mPrevPwrTimestampNotZero = 0;

    private boolean mIsScanning = false;
    private boolean mIsConnected = false;
    private boolean mIsConnecting = false;
    private boolean mIsDisconnecting = false;

    public AndroidBLEPWRProvider(Context ctx) {
        context = ctx;
    }

    @Override
    public String getName() {
        return DISPLAY_NAME;
    }

    @Override
    public String getProviderName() {
        return NAME;
    }
    
    @Override
    public void open(Handler handler, PWRClient pwrClient) {
        this.pwrClient = pwrClient;
        this.pwrClientHandler = handler;

        if (btAdapter == null) {
            btAdapter = BluetoothAdapter.getDefaultAdapter();
        }

        pwrClient.onOpenResult(btAdapter != null);
    }

    @Override
    public void close() {
        stopScan();
        disconnect();

        if (btGatt != null) {
            btGatt.close();
            btGatt = null;
        }

        btAdapter = null;
        btDevice = null;
        pwrClient = null;
        pwrClientHandler = null;
    }

    private final BluetoothGattCallback btGattCallbacks = new BluetoothGattCallback() {

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic arg0) {
            try {
                if (!checkBtGattOnlyLogError(gatt)) {
                    return;
                }

                if (!arg0.getUuid().equals(POWER_MEASUREMENT_CHARAC)) {
                    log("onCharacteristicChanged(" + arg0
                            + ") != POWER ??");
                    return;
                }

                int length = arg0.getValue().length;
                if (length == 0) {
                    log("onCharacteristicChanged length = 0");
                    return;
                }

                int val;
                if (isPowerInUINT16(arg0.getValue()[0])) {
                    val = arg0.getIntValue(
                            BluetoothGattCharacteristic.FORMAT_UINT16, 1);
                } else {
                    val = arg0.getIntValue(
                            BluetoothGattCharacteristic.FORMAT_UINT8, 1);
                }

                pwrTimestamp = System.currentTimeMillis();
                pwrElapsedRealtime = SystemClock.elapsedRealtimeNanos();

                if (val == 0) {
                    // Some HR straps (low quality?) report 0 when it cannot read HR but still have connection
                    // (especially first value, so it never connects)
                    // Previously this was considered as an indication that the strap was disconnected
                    // This keeps this behavior, reporting old value until timeout.
                    // Discussion in PR #477
                    final long mMaxPwrTimestampNotZero = 60 * 1000;
                    if (mPrevPwrTimestampNotZero > 0 &&
                            pwrTimestamp - mPrevPwrTimestampNotZero > mMaxPwrTimestampNotZero) {
                        if (mIsConnecting) {
                            reportConnectFailed("got pwrValue = 0 => reportConnectFailed");
                            return;
                        }
                        log("got pwrValue == 0 => disconnecting");
                        reportDisconnected();
                        return;
                    }
                } else {
                    pwrValue = val;
                    mPrevPwrTimestampNotZero = pwrTimestamp;
                }

                if (mIsConnecting) {
                    reportConnected(true);
                }
            } catch (Exception e) {
                log("onCharacteristicChanged => " + e);
                if (mIsConnecting)
                    reportConnectFailed("Exception in onCharacteristicChanged: " + e);
                else if (mIsConnected)
                    reportDisconnected();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic arg0, int status) {
            try {
                log("onCharacteristicRead(): " + gatt + ", char: "
                        + arg0.getUuid() + ", status: " + status);

                if (!checkBtGatt(gatt))
                    return;

                UUID charUuid = arg0.getUuid();
                if (charUuid.equals(FIRMWARE_REVISON_UUID)) {
                    log("firmware => startHR()");
                    // triggered from DummyReadForSecLevelCheck
                    startHR();
                } else if (charUuid.equals(BATTERY_LEVEL_CHARAC)) {
                    log("batterylevel: " + arg0);
                    batteryLevel = arg0.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0);
                    log("Battery level: " + batteryLevel);

                    log(" => startPWR()");
                    // triggered from DummyReadForSecLevelCheck
                    startPWR();

                } else {
                    log("Unknown characteristic received: " + charUuid);
                }
            } catch (Exception e) {
                log("onCharacteristicRead => " + e);
                reportConnectFailed("Exception in onCharacteristicRead: " + e);
            }
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                            int newState) {

            try {
                log("onConnectionStateChange: " + gatt
                        + ", status: " + status + ", newState: " + newState);
                log("STATUS_SUCCESS:" + BluetoothGatt.GATT_SUCCESS);
                log("STATE_CONNECTED: "
                        + BluetoothProfile.STATE_CONNECTED
                        + ", STATE_DISCONNECTED: "
                        + BluetoothProfile.STATE_DISCONNECTED);

                if (!checkBtGatt(gatt)) {
                    return;
                }

                if (mIsConnecting) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        boolean res = btGatt.discoverServices();
                        log("discoverServices() => " + res);
                        return;
                    } else {
                        boolean res = btGatt.connect();
                        log("reconnect while connecting => btGatt.connect() => "
                                        + res);
                        return;
                    }
                }

                if (mIsDisconnecting) {
                    log("mIsDisconnecting => notify");
                    synchronized (this) {
                        btGatt.close();
                        btGatt = null;
                        this.notifyAll();
                        return;
                    }
                }

                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    reportDisconnected();
                    return;
                }
                log("onConnectionStateChange => Already connected?");
            } catch (Exception e) {
                log("onConnectionStateChange => " + e);
                reportConnectFailed("Exception in onConnectionStateChange: " + e);
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt,
                                     BluetoothGattDescriptor arg0, int status) {

            BluetoothGattCharacteristic mHRMcharac = arg0.getCharacteristic();
            if (!enableNotification(true, mHRMcharac)) {
                reportConnectFailed("Failed to enable notification in onDescriptorRead");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            log("onServicesDiscoverd(): " + gatt + ", status: "
                    + status);

            if (!checkBtGatt(gatt))
                return;

            List<BluetoothGattService> list = btGatt.getServices();
            for (BluetoothGattService s : list) {
                log("Found service: " + s.getType() + ", "
                        + s.getInstanceId() + ", " + s.getUuid());
                for (BluetoothGattCharacteristic a : s.getCharacteristics()) {
                    log("  char: " + a.getUuid());
                }
                for (BluetoothGattService a : s.getIncludedServices()) {
                    log("  serv: " + a.getUuid());
                }

                if (s.getUuid().equals(BATTERY_SERVICE)) {
                    hasBatteryService = true;
                }
            }

            log(" => DummyRead");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                DummyReadForSecLevelCheck(gatt);
                // continue in onCharacteristicRead
            } else {
                DummyReadForSecLevelCheck(gatt);
                // reportConnectFailed("onServicesDiscovered(" + gatt + ", " +
                // status + ")");
            }
        }

        /*
         * from Samsung HRPService.java
         */
        private void DummyReadForSecLevelCheck(BluetoothGatt btGatt) {
            if (btGatt == null)
                return;

            if (hasBatteryService && readBatteryLevel()) {
                return;
            }

            BluetoothGattService disService = btGatt.getService(DIS_UUID);
            if (disService == null) {
                reportConnectFailed("Dis service not found");
                return;
            }
            BluetoothGattCharacteristic firmwareIdCharc = disService
                    .getCharacteristic(FIRMWARE_REVISON_UUID);
            if (firmwareIdCharc == null) {
                reportConnectFailed("firmware revison charateristic not found!");
                return;
            }

            if (!btGatt.readCharacteristic(firmwareIdCharc)) {
                reportConnectFailed("firmware revison reading is failed!");
            }
            // continue in onCharacteristicRead
        }

        private boolean isPowerInUINT16(byte b) {
            return (b & 1) != 0;
        }

        private void startPWR() {
            BluetoothGattService mPWR = btGatt.getService(PWR_SERVICE);
            if (mHRP == null) {
                reportConnectFailed("PWR service not found!");
                return;
            }

            BluetoothGattCharacteristic mPWRcharac = mPWR
                    .getCharacteristic(POWER_MEASUREMENT_CHARAC);
            if (mPWRcharac == null) {
                reportConnectFailed("POWER MEASUREMENT charateristic not found!");
                return;
            }
            BluetoothGattDescriptor mPWRccc = mPWRcharac.getDescriptor(CCC);
            if (mPWRccc == null) {
                reportConnectFailed("CCC for POWER charateristic not found!");
                return;
            }
            if (!btGatt.readDescriptor(mPWRccc)) {
                reportConnectFailed("readDescriptor() is failed");
            }
            // Continue in onDescriptorRead
        }

        private boolean readBatteryLevel() {
            BluetoothGattService mBS = btGatt.getService(BATTERY_SERVICE);
            if (mBS == null) {
                log("Battery service not found.");
                return false;
            }

            BluetoothGattCharacteristic mBLcharac = mBS
                    .getCharacteristic(BATTERY_LEVEL_CHARAC);
            if (mBLcharac == null) {
                reportConnectFailed("BATTERY LEVEL charateristic not found!");
                return false;
            }

            if (!btGatt.readCharacteristic(mBLcharac)) {
                log("readCharacteristic(" + mBLcharac.getUuid() + ") failed");
                return false;
            }
            // continue in onCharacteristicRead
            return true;
        }
    };

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean enableNotification(boolean onoff,
                                       BluetoothGattCharacteristic charac) {
        if (btGatt == null)
            return false;

        if (!btGatt.setCharacteristicNotification(charac, onoff)) {
            log("btGatt.setCharacteristicNotification() failed");
            return false;
        }

        BluetoothGattDescriptor clientConfig = charac.getDescriptor(CCC);
        if (clientConfig == null) {
            log("clientConfig == null");
            return false;
        }

        if (onoff) {
            clientConfig
                    .setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        } else {
            clientConfig
                    .setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        }
        return btGatt.writeDescriptor(clientConfig);
    }

    @Override
    public boolean isScanning() {
        return mIsScanning;
    }

    // Using a derecated API - change in API 21 (Marshmallow)
    private final BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi,
                byte[] scanRecord) {
            if (pwrClient == null)
                return;

            if (pwrClientHandler == null)
                return;

            String address = device.getAddress();
            if (mIsConnecting
                    && address.equals(btDevice.getAddress())) {
                stopScan();

                if (CONNECT_IN_OWN_THREAD_FROM_ON_LE_SCAN) {
                    // Android 4.3
                    log("CONNECT_IN_OWN_THREAD_FROM_ON_LE_SCAN");
                    pwrClientHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            log("before connect");
                            btGatt = btDevice.connectGatt(context, false, btGattCallbacks);
                            if (btGatt == null) {
                                reportConnectFailed("connectGatt returned null");
                            } else {
                                log("connectGatt: " + btGatt);
                            }
                        }
                    });
                } else {
                    btGatt = btDevice.connectGatt(context, false, btGattCallbacks);
                    if (btGatt == null) {
                        reportConnectFailed("connectGatt returned null");
                    } else {
                        log("connectGatt: " + btGatt);
                    }
                }
                return;
            }

            if (mScanDevices.contains(address))
                return;

            mScanDevices.add(address);

                }
            });
        }

    };

    private final HashSet<String> mScanDevices = new HashSet<>();

    @Override
    public void startScan() {
        if (mIsScanning)
            return;

        mIsScanning = true;
        mScanDevices.clear();
        if (AVOID_SCAN_WITH_UUID)
            // Android 4.3
            btAdapter.startLeScan(mLeScanCallback);
        else
            btAdapter.startLeScan(SCAN_UUIDS, mLeScanCallback);
    }

    @Override
    public void stopScan() {
        if (mIsScanning) {
            mIsScanning = false;
            btAdapter.stopLeScan(mLeScanCallback);
        }
    }

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
        stopScan();
        }

        BluetoothDevice dev = btAdapter.getRemoteDevice(ref.deviceAddress);

        if (mIsConnected || mIsConnecting)
            return;

        mIsConnecting = true;
        btDevice = dev;
        if (ref.deviceName == null || dev.getName() == null
                || !dev.getName().contentEquals(ref.deviceName)) {
            /*
             * If device doesn't match name, scan for before connecting
             */
            log("Scan before connect");
            startScan();
            return;
        } else {
            log("Skip scan before connect");
        }

        btGatt = btDevice.connectGatt(context, false, btGattCallbacks);
        if (btGatt == null) {
            reportConnectFailed("connectGatt returned null");
        } else {
            log("connectGatt: " + btGatt);
        }
    }

    private void reportConnected(final boolean b) {
        if (hrClientHandler != null) {
            hrClientHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mIsConnecting && hrClient != null) {
                        mIsConnected = b;
                        mIsConnecting = false;
                        pwrClient.onConnectResult(b);
                    }
                }
            });
        }
    }

    private void reportConnectFailed(String string) {
        log("reportConnectFailed(" + string + ")");
        if (btGatt != null) {
            btGatt.disconnect();
            btGatt.close();
            btGatt = null;
        }
        btDevice = null;
        reportConnected(false);
    }

    @Override
    public void disconnect() {
        if (btGatt == null || btDevice == null) {
            return;
        }

        if (!mIsConnecting && !mIsConnected)
            return;

        if (mIsDisconnecting)
            return;

        boolean isConnected = mIsConnected;
        mIsConnected = false;
        mIsConnecting = false;
        mIsDisconnecting = true;

        do {
            BluetoothGattService mPWR = btGatt.getService(PWR_SERVICE);
            if (mPWR == null) {
                reportDisconnectFailed("PWR service not found!");
                break;
            }

            BluetoothGattCharacteristic mPWRcharac = mPWR
                    .getCharacteristic(POWER_MEASUREMENT_CHARAC);
            if (mPWRcharac == null) {
                reportDisconnectFailed("POWER MEASUREMENT charateristic not found!");
                break;
            }

            if (!enableNotification(false, mPWRcharac)) {
                reportDisconnectFailed("disableNotfication");
                break;
            }
        } while (false);

        btGatt.disconnect();

        if (isConnected) {
            log("close btGatt in onConnectionState");
            // close btGatt in onConnectionState
            synchronized (this) {
                long end = System.currentTimeMillis() + 2000; // wait max 2
                                                              // seconds
                while (btGatt != null && System.currentTimeMillis() < end) {
                    log("waiting for btGatt to become null");
                    try {
                        this.wait(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                BluetoothGatt copy = btGatt;
                if (copy != null) {
                    log("close btGatt in disconnect() after waiting 2 secs");
                    copy.close();
                    btGatt = null;
                }
            }
        } else {
            log("close btGatt here in disconnect()");
            BluetoothGatt copy = btGatt;
            if (copy != null)
                copy.close();
            btGatt = null;
        }

        btDevice = null;
        mIsDisconnecting = false;
        reportDisconnected();
    }

    private void reportDisconnectFailed(String string) {
        log("disconnect failed: " + string);
        hrClient.onDisconnectResult(false);
    }

    private void reportDisconnected() {
        hrClient.onDisconnectResult(true);
    }

    @Override
    public int getPWRValue() {
        return this.hrValue;
    }

    @Override
    public long getPWRValueTimestamp() {
        return this.hrTimestamp;
    }

    @Override
    public long getPWRValueElapsedRealtime() {
        return this.hrElapsedRealtime;
    }

    @Override
    public PWRData getPWRData() {

        if (pwrValue < 0) {
            return null;
        }

        return new PWRData().setPower(pwrValue).setTimestampEstimate(pwrTimestamp);
    }

    @Override
    public boolean isBondingDevice() {
        return true;
    }

    @Override
    public int getBatteryLevel() {
        return this.batteryLevel;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkBtGatt(BluetoothGatt gatt) {
        return checkBtGatt(gatt, false);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkBtGattOnlyLogError(BluetoothGatt gatt) {
        return checkBtGatt(gatt, true);
    }

    private synchronized boolean checkBtGatt(BluetoothGatt gatt, boolean onlyLogError) {
        if (btGatt == null) {
            if (!onlyLogError)
                log("checkBtGatt, btGatt == null => true");
            btGatt = gatt;
            return true;
        }
        if (btGatt == gatt) {
            if (!onlyLogError)
                log("checkBtGatt, btGatt == gatt => true");
            return true;
        }
        log("checkBtGatt, btGatt("+btGatt+") != gatt(" + gatt + ") => false");
        return false;
    }
}
