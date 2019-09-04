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

import android.app.Activity;
import android.os.Handler;

/**
 * {@link PWRProvider}'s provide an interface to a wireless connectivity module (Bluetooth, ANT+ etc)
 * and any heart rate devices than can be connected through them
 *
 * Instances of {@link PWRProvider's} can be created through the {@link PWRManager} class
 *
 * @author jonas
 */

public interface PWRProvider {

    /**
     * An interface through which the client of the {@link PWRProvider}
     * is notified of changes to the state of the {@link PWRProvider}
     */
    interface PWRClient {
        void onOpenResult(boolean ok);

        void onScanResult(PWRDeviceRef device);

        void onConnectResult(boolean connectOK);

        void onDisconnectResult(boolean disconnectOK);

        void onCloseResult(boolean closeOK);

        void log(PWRProvider src, String msg);
    }

    /**
     * @return A human readable name for the {@link PWRProvider}
     */
    String getName();

    /**
     * @return An internal name for a specific {@link PWRProvider} instantiation
     */
    String getProviderName(); // For internal usage


    /**
     * @return true if the wireless module is enabled,
     *          false otherwise
     */
    boolean isEnabled();

    /**
     * Presents the user if the settings screen to enable the provider's protocol. When this is done,
     * 'activity' will have {@link Activity#onActivityResult(int, int, android.content.Intent)} called
     *
     * @param activity The {@link Activity} currently being displayed to the user
     * @param requestCode An arbitrary code that will be given to
     *                  {@link Activity#onActivityResult(int, int, android.content.Intent)}
     * @return true if the intent was sent
     */
    boolean startEnableIntent(Activity activity, int requestCode);

    /**
     * Initialises the wireless module, allowing device scanning/connection
     *
     * @param handler The Handler in which to run the hrClient
     * @param hrClient The object that will be notified when operations have finished
     */
    void open(Handler handler, PWRClient pwrClient);

    /**
     * Closes the wireless module
     */
    void close();

    /**
     * A bonding device is a wireless module that requires devices to be
     * paired before showing up on the scan (e.g. Bluetooth)
     *
     * @return true if the wireless module is a bonding device,
     *          false otherwise
     */
    boolean isBondingDevice();

    /**
     * @return true if this {@link PWRProvider} is currently scanning for available devices,
     *          false otherwise
     */
    boolean isScanning();

    /**
     * @return true if this {@link PWRProvider} is connected to a power rate device,
     *          false otherwise
     */
    boolean isConnected();

    /**
     * @return true if this {@link HRProvider} is currently connecting to a power rate device,
     *          false otherwise
     */
    boolean isConnecting();

    /**
     * Starts scanning for available power rate devices. Results will be passed to the PWRClient
     * supplied in {@link #open(android.os.Handler, org.runnerup.pwr.PWRProvider.PWRClient)}
     */
    void startScan();

    /**
     * Stops scanning for available power devices. When done, the {@link PWRClient} passed
     * in {@link #open(android.os.Handler, org.runnerup.hr.PWRProvider.PWRClient)} will be notified
     */
    void stopScan();

    /**
     * Connects to a power monitor device
     * @param ref An object representing a heart rate device. Client code can get
     *            available device information through startScan()
     */
    void connect(PWRDeviceRef ref);

    /**
     * Disconnects from a power monitor device
     */
    void disconnect();

    /**
     * @return the most recent power value supplied by the connected device.
     *          legacy wording from HR module, not relevant here, delete in time: 0 indicates that no device has been connected (or the user is in a very bad way)
     */
    int getPWRValue();

    /**
     * @return the unix time of the last received power value
     */
    long getPWRValueTimestamp();

    /**
     * Get the time for the sensor, comparable with other sources as getTime()
     * differs for system vs GPS time
     *
     * @return the elapsed time sinc boot in nano sec for last received value
     */
    long getPWRValueElapsedRealtime();

    /**
     * @return the most recent power data supplied by the device. If no device has
     *          been connected, this will be null
     */
    PWRData getPWRData();

    /**
     * @return The battery level, in percents, of the power monitor device or 0 if
     *          no device has been connected or the device doesn't supply battery information
     */
    int getBatteryLevel();
}
