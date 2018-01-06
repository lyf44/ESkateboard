package com.gmail.luyunfan44.eskateboardcontroller;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import java.util.UUID;

/**
 * Created by luyun on 22/10/2017.
 */

public class BluetoothLeService extends Service {

    private final static String TAG = BluetoothLeService.class.getSimpleName();
    // Service related
    private final IBinder mBinder = new LocalBinder();

    // BLE related
    private BluetoothManager mBluetoothManager;
    private BluetoothDevice mSkateboardBleDevice;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED = "GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED = "GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED = "SERVICES_DISCOVERED";
    public final static String ACTION_DATA_SENT = "DATA_SENT";
    public final static String ACTION_DATA_SEND_FAIL = "DATA_SEND_FAIL";
    /*
     *  Method
     */

    // Service related:

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            // Return this instance of BluetoothLeService so clients can call public methods
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // A client is binding to the service with bindService()
        Log.i(TAG, "returning binder");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        closeBLE();
        return super.onUnbind(intent);
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initializeBLEDevice(BluetoothDevice device) {
        if (device == null) {
            Log.e(TAG, "device is null");
            return false;
        }
        mSkateboardBleDevice = device;
        return true;
    }

    public boolean connectBLE() {

        // Previously connected device.  Try to reconnect.
        if (mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                return true;
            } else {
                return false;
            }
        }

        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = mSkateboardBleDevice.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        // mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    public void closeBLE() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.disconnect();
        mBluetoothGatt.close();
        mBluetoothGatt = null;
        mSkateboardBleDevice = null;
    }

    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mSkateboardBleDevice == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothDevice not initialized");
            return false;
        }
        mBluetoothGatt.writeCharacteristic(characteristic);
        return true;
    }

    public BluetoothGattCharacteristic getCharacteristic(String serviceUuid, String characteristicUuid) {
        // Input Validity Check
        if (mSkateboardBleDevice == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothDevice not initialized");
            return null;
        }
        BluetoothGattService bluetoothGattService = mBluetoothGatt.getService(UUID.fromString(serviceUuid));
        if (bluetoothGattService == null) {
            return null;
        } else {
            return bluetoothGattService.getCharacteristic(UUID.fromString(characteristicUuid));
        }
    }

    /*
     * Callbacks
     */

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:");
                mBluetoothGatt.discoverServices();
                broadcastUpdate(ACTION_GATT_CONNECTED);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(ACTION_GATT_DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicWrite (BluetoothGatt gatt,
                                           BluetoothGattCharacteristic characteristic,
                                           int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "sent characteristic" + characteristic.getValue());
                broadcastUpdate(ACTION_DATA_SENT);
            } else {
                broadcastUpdate(ACTION_DATA_SEND_FAIL);
            }
        }

    };

    // BroadCastUpdate
    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

}
