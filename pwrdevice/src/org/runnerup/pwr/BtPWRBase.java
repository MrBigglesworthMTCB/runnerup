    
/*
 * Copyright (C) 2014 jonas.oreland@gmail.com
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

import android.os.Handler;
import android.os.Looper;

import java.util.UUID;


abstract class BtPWRBase implements PWRProvider {
    static final UUID PWR_SERVICE = UUID
            .fromString("00001818-0000-1000-8000-00805f9b34fb");
    static final UUID BATTERY_SERVICE = UUID
            .fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID FIRMWARE_REVISON_UUID = UUID
            .fromString("00002a26-0000-1000-8000-00805f9b34fb");
    static final UUID DIS_UUID = UUID
            .fromString("0000180a-0000-1000-8000-00805f9b34fb");
    static final UUID PWR_MEASUREMENT_CHARAC = UUID
            .fromString("00002A63-0000-1000-8000-00805f9b34fb");
    static final UUID BATTERY_LEVEL_CHARAC = UUID
            .fromString("00002A19-0000-1000-8000-00805f9b34fb");
    static final UUID CCC = UUID
            .fromString("00002a00-0000-1000-8000-00805f9b34fb");

    PWRProvider.PWRClient pwrClient;
    Handler pwrClientHandler;

    void log(final String msg) {
        if (pwrClient != null) {
            if(Looper.myLooper() == Looper.getMainLooper()) {
                pwrClient.log(this, msg);
            } else {
                pwrClientHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (pwrClient != null)
                            pwrClient.log(BtPWRBase.this, msg);
                    }
                });
            }
        }
        else
            System.err.println(msg);
    }
}
