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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.preference.PreferenceManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides instance of {@link PWRProvider}
 *
 * @author jonas
 */

public class PWRManager {

        static PWRProvider createProviderByReflection(Context ctx, boolean experimental) {
            try {
                Class<?> classDefinition = Class.forName(Lib);
                Constructor<?> cons = classDefinition.getConstructor(Context.class);
                PWRProvider ap = (HRProvider) cons.newInstance(ctx);
                if (!ap.isEnabled()) {
                    return null;
                }
                return ap;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Creates an {@link PWRProvider}. This will be wrapped in a {@link RetryingPWRProviderProxy}.
     * *
     * @param src The type of {@link PWRProvider} to create.
     * @return A new instance of an {@link PWRProvider} or null if
     *   A) 'src' is not a valid {@link PWRProvider} type
     *   B) the device does not support an {@link PWRProvider} of type 'src'
     */
    public static PWRProvider getPWRProvider(Context ctx, String src) {
        PWRProvider provider = getPWRProviderImpl(ctx, src);
        if (provider != null) {
            return new RetryingPWRProviderProxy(provider);
        }
        return null;
    }

    
    private static PWRProvider getPWRProviderImpl(Context ctx, String src) {
        System.err.println("getPWRProvider(" + src + ")");
        if (src.contentEquals(AndroidBLEPWRProvider.NAME)) {
            if (!AndroidBLEPWRProvider.checkLibrary(ctx))
                return null;
            return new AndroidBLEPWRProvider(ctx);
        }
       

        if (src.contentEquals(MockPWRProvider.NAME)) {
            return new MockPWRProvider(ctx);
        }

        return null;
    }

    /**
     * Returns a list of {@link PWRProvider}'s that are available on this device.
     * 
     * It is recommended to use this list only for selecting a valid {@link PWRProvider}.
     * For connecting to the device, use the instance returned by {@link #getPWRProvider(android.content.Context, String)}
     * 
     * @return A list of all {@link PWRProvider}'s that are available on this device.
     */
    public static List<PWRProvider> getPWRProviderList(Context ctx) {
        Resources res = ctx.getResources();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        boolean experimental = prefs
                .getBoolean(res.getString(R.string.pref_bt_experimental), false);
        boolean mock = prefs.getBoolean(res.getString(R.string.pref_bt_mock), false);

        List<PWRProvider> providers = new ArrayList<>();
        if (AndroidBLEPWRProvider.checkLibrary(ctx)) {
            providers.add(new AndroidBLEPWRProvider(ctx));
        }

        if (mock) {
            providers.add(new MockHRProvider(ctx));
        }

        return providers;
    }
}
