package com.gmail.luyunfan44.eskateboardcontroller;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

// TODO properly stop BLE scan
// TODO only close ble when flag_stop is true

public class MainActivity extends AppCompatActivity {

    private final static String TAG = BluetoothLeService.class.getSimpleName();

    // Constants
    private final int REQUEST_ENABLE_BT = 1;
    private final int LOCATION_PERMISSION_REQUEST_CODE = 2;
    private final long SCAN_PERIOD = 20000; // 10s
    private final int NEUTRAL_SPEED = 10;
    private final int MAX_SPEED = 100;
    private final int MIN_SPEED = 0;

    // Attributes
    // flags
    private boolean flag_BLEReady = false;
    private boolean flag_BLEConnected = false;
    private boolean flag_Stop = true;
    private boolean flag_shortPress = true;

    // Ble
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice mSkateboardBleDevice = null;
    private Handler mHandler;
    private BluetoothLeService mBLEService;
    private BluetoothGattCharacteristic mBLEChartacteristic;

    // UI
    private ProgressBar mProgressBar;
    private Toolbar mToolbar;
    private SeekBar mSeekbar;
    private TextView mTextView_SpeedText;
    private TextView mTextView_SpeedVal;
    private TextView mTextView_Status;
    private Toast mToast;
    private Button mButton_Stop;
    private LinearLayout mLinearLayout;

    // Temp
    private int mSpeedTemp_VOL;
    private int mSpeedTemp_LL = MIN_SPEED;

    /*
     *  Activity related
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initializes Bluetooth adapter.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        // Handler
        mHandler = new Handler();

        // Toast
        mToast = Toast.makeText(this  , "" , Toast.LENGTH_SHORT );

        // Toolbar
        mToolbar = (Toolbar) findViewById(R.id.my_toolbar);
        setSupportActionBar(mToolbar);

        // ProgressBar
        mProgressBar = (ProgressBar) findViewById(R.id.my_progressBar);
        mProgressBar.setVisibility(View.INVISIBLE);

        // TextView
        mTextView_SpeedVal = (TextView) findViewById(R.id.textView_SpeedValue);
        mTextView_SpeedVal.setVisibility(View.INVISIBLE);
        mTextView_SpeedText = (TextView) findViewById(R.id.textView_SpeedText);
        mTextView_SpeedText.setVisibility(View.INVISIBLE);
        mTextView_Status = (TextView) findViewById(R.id.textView_Status);
        mTextView_Status.setText(getString(R.string.status_disconnected));

        // Seekbar
        mSeekbar = (SeekBar) findViewById(R.id.my_seekBar);
        SetSeekbarOnChangeListener();
        mSeekbar.setEnabled(false);
        mSeekbar.setVisibility(View.INVISIBLE);

        // Button
        mButton_Stop = (Button) findViewById(R.id.button_Stop);
        mButton_Stop.setEnabled(false);
        mButton_Stop.setVisibility(View.INVISIBLE);
        setButtonOnClickListener();

        // LinearLayout
        mLinearLayout = (LinearLayout) findViewById(R.id.linearLayout);
        SetLinearLayoutOnClickListener();

        // Permission at runtine
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                &&
                ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            askForLocationPermissions();
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        Log.i(TAG, "onCreate called");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.i(TAG, "onStart called");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // first send brake command.
        if (sendSpeedCmd(0)) {
            // UI
            setParam_Stopped();
        }

        Log.i(TAG, "onPause called");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "onStop called");
    }

    @Override
    protected void onDestroy() {
        // Unbind from the service
        if (flag_BLEReady) {
            unbindService(mConnection); // this will invoke service.close() as well.
            LocalBroadcastManager.getInstance(this).unregisterReceiver(mGattUpdateReceiver);
            setParam_BLEDisconnected();
        }
        Log.i(TAG, "onDestroy called");
        super.onDestroy();

    }
    /*
     * Methods:
     */

    /*
     * Bind service callbacks
     */

    private void scanLeDevice(final boolean enable) {
        if (enable) {
            // UI
            mTextView_Status.setText(getString(R.string.status_scanning));

            // Stops scanning after a pre-defined scan period.
            // postDelayed-> runnable will run after SCAN_PERIOD has passed.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    mProgressBar.setVisibility(View.INVISIBLE);
                    DisplayToastMsg("Unable to find skateboard BLE device, please check connection");
                }
            }, SCAN_PERIOD);

            // UI change
            mProgressBar.bringToFront();
            mProgressBar.setVisibility(View.VISIBLE);

            DisplayToastMsg("connecting to skateboard ble");
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else {
            mHandler.removeCallbacksAndMessages(null);
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
    }

    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {
                @Override
                public void onLeScan(final BluetoothDevice device, int rssi,
                                     byte[] scanRecord) {
                    String deviceMacAddr = device.getAddress();
                    DisplayToastMsg(deviceMacAddr);
                    // check whether we can find the correct ble device with given MAC address
                    if (deviceMacAddr.equals(Constants.scSkateBoardBLEMacAddr)) {
                        mSkateboardBleDevice = device;
                        DisplayToastMsg("found skateboard ble, connecting");

                        // stop scanning
                        scanLeDevice(false);

                        // Bind to LocalService
                        Log.i(TAG, "bindService called");
                        Intent intent = new Intent(MainActivity.this, BluetoothLeService.class);
                        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
                    }
                }
            };

    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            BluetoothLeService.LocalBinder binder = (BluetoothLeService.LocalBinder) service;
            mBLEService = binder.getService();

            // UI
            DisplayToastMsg("service binded");
            mTextView_Status.setText(getString(R.string.status_connecting));

            if (mSkateboardBleDevice == null) {
                Log.e(TAG, "mSkateboardBleDevice is null");
                return;
            }

            // pass BLE device to the service
            mBLEService.initializeBLEDevice(mSkateboardBleDevice);

            // connect BLE device
            if (!mBLEService.connectBLE()) {
                Log.e(TAG, "service connectBLE failed");
                return;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBLEService = null;
            flag_BLEReady = false;
        }
    };

    /*
     * Receive msg from BluetoothLeService
     */
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                DisplayToastMsg("connected to Skateboard BLE");
                mTextView_Status.setText(getString(R.string.status_connected));

                flag_BLEConnected = true;
                invalidateOptionsMenu();
                // Log.i(TAG, "Invalidate options menu called");

            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                DisplayToastMsg("disconnected from Skateboard BLE");
                setParam_BLEDisconnected();

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // get BLE characteristic
                mBLEChartacteristic = mBLEService.getCharacteristic(Constants.scSkateBoardBLEUUID,
                        Constants.scSkateBoardBLECharUUID);
                if (mBLEChartacteristic == null) {
                    Log.e(TAG, "service getBLE characteristic failed");
                    return;
                }

                //UI
                mProgressBar.setVisibility(View.INVISIBLE);
                mProgressBar.setEnabled(false);
                mTextView_SpeedVal.setVisibility(View.VISIBLE);
                mTextView_SpeedText.setVisibility(View.VISIBLE);
                mSeekbar.setVisibility(View.VISIBLE);
                mButton_Stop.setVisibility(View.VISIBLE);
                mButton_Stop.setEnabled(true);
                mTextView_Status.setText(getString(R.string.status_stopped));

                // finally we are ready to send commands via bluetooth.
                flag_BLEReady = true;
                DisplayToastMsg("BLEReady");
            } else if (BluetoothLeService.ACTION_DATA_SENT.equals(action)) {
                DisplayToastMsg("data sent to Skateboard successfully");
            }  else if (BluetoothLeService.ACTION_DATA_SEND_FAIL.equals(action)) {
                DisplayToastMsg("send data failed!!!!");
            }

        }
    };

    private boolean sendSpeedCmd(int speedVal) {
        if (speedVal < 0 || speedVal > 100) {
            Log.e(TAG, "speedCmd speedVal out of range");
            return false;
        }

        if (flag_BLEReady && !flag_Stop) {
            // BLE send data
            mBLEChartacteristic.setValue(speedVal, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
            mBLEService.writeCharacteristic(mBLEChartacteristic);
            DisplayToastMsg("send speed up command");
            Log.i(TAG, "send speed up command");
            return true;
        } else {
            DisplayToastMsg("BLE not ready or skateboard stopped");
            Log.i(TAG, "BLE not ready or skateboard stopped");
            return false;
        }
    }

    private void setParam_Stopped() {
        // flag
        flag_Stop = true;

        // UI
        mSeekbar.setProgress(MIN_SPEED);
        DisplaySpeed(MIN_SPEED);
        mSeekbar.setEnabled(false);
        mButton_Stop.setText(getString(R.string.button_start));
        mTextView_Status.setText(getString(R.string.status_stopped));
    }

    private void setParam_BLEDisconnected() {
        flag_BLEConnected = false;
        flag_BLEReady = false;
        flag_Stop = true;
        mBLEChartacteristic = null;
        mSkateboardBleDevice = null; // so that the next time we want to connect, we must scan again.

        //UI
        mSeekbar.setProgress(MIN_SPEED);
        DisplaySpeed(MIN_SPEED);
        mSeekbar.setEnabled(false);
        mSeekbar.setVisibility(View.INVISIBLE);
        mButton_Stop.setText(getString(R.string.button_start));
        mButton_Stop.setEnabled(false);
        mButton_Stop.setVisibility(View.INVISIBLE);
        mTextView_SpeedVal.setVisibility(View.INVISIBLE);
        mTextView_SpeedText.setVisibility(View.INVISIBLE);
        mProgressBar.setEnabled(true);
        mProgressBar.setVisibility(View.INVISIBLE);
        invalidateOptionsMenu();
        mTextView_Status.setText(getString(R.string.status_disconnected));
    }

    /*
     *  Other Callbacks
     */

    /**
     *  Volume Key Control Callbacks
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)){
            event.startTracking();
            flag_shortPress = true;

            // reset mSpeedTemp_LL as speed is already changed by vol btn, next press on linearLayout
            // should not set the speed to mSpeedTemp_LL
            mSpeedTemp_LL = MIN_SPEED;

            // NOTE: no super.onKeyDown because we want to disable volume down action
            return true;
        } else if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP)){
            event.startTracking();
            flag_shortPress = true;

            // reset mSpeedTemp_LL as speed is already changed by vol btn, next press on linearLayout
            // should not set the speed to mSpeedTemp_LL
            mSpeedTemp_LL = MIN_SPEED;

            // NOTE: no super.onKeyDown because we want to disable volume down action
            return true;
        }
        // for all other case, do the default
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            // if onKeyLongPress has been handled.
            final int flag = event.getFlags();
            if ((flag & KeyEvent.FLAG_CANCELED) == KeyEvent.FLAG_CANCELED &&
                    (flag & KeyEvent.FLAG_CANCELED_LONG_PRESS) == KeyEvent.FLAG_CANCELED_LONG_PRESS) {
                // Log.i(TAG, "long press handled");
                return true;
            }

            // if short press, decrease speed(progress)
            int curSpeed = mSeekbar.getProgress();
            int newSpeed;
            if (curSpeed > NEUTRAL_SPEED) {
                // if currently skateboard is moving forward, decrease speed by 5
                newSpeed = curSpeed - 5;
                if (newSpeed < NEUTRAL_SPEED) {
                    newSpeed = NEUTRAL_SPEED;
                }
            } else {
                // else currently skateboard is neutral or braking, increase brake strength by 2
                newSpeed = curSpeed - 2;
                if (newSpeed < 0) {
                    newSpeed = 0;
                }
            }

            if (sendSpeedCmd(newSpeed)) {
                // UI
                mSeekbar.setProgress(newSpeed);
                DisplaySpeed(newSpeed);
            }

            // NOTE: no super.onKeyDown because we want to disable volume down action
            return true;
        } else if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            // if onKeyLongPress has been handled, restore the previous speed
            final int flag = event.getFlags();
            if ((flag & KeyEvent.FLAG_CANCELED) == KeyEvent.FLAG_CANCELED &&
                    (flag & KeyEvent.FLAG_CANCELED_LONG_PRESS) == KeyEvent.FLAG_CANCELED_LONG_PRESS) {
                // Log.i(TAG, "long press handled");
                if (sendSpeedCmd(mSpeedTemp_VOL)) {
                    // UI
                    mSeekbar.setProgress(mSpeedTemp_VOL); // restore the previous speed
                    DisplaySpeed(mSpeedTemp_VOL);
                }
                return true;
            }

            int curSpeed = mSeekbar.getProgress();
            int newSpeed;
            if (curSpeed >= NEUTRAL_SPEED) {
                // if currently skateboard is at neutral or moving forward, increase speed by 5
                newSpeed = curSpeed + 5;
                if (newSpeed > MAX_SPEED) {
                    newSpeed = MAX_SPEED;
                }
            } else {
                // else currently skateboard is neutral or braking, decrease brake strength by 2
                newSpeed = curSpeed + 2;
                if (newSpeed > NEUTRAL_SPEED) {
                    newSpeed = NEUTRAL_SPEED;
                }
            }

            // send new speed command
            if (sendSpeedCmd(newSpeed)) {
                // UI
                mSeekbar.setProgress(newSpeed);
                DisplaySpeed(newSpeed);
            }
            // NOTE: no super.onKeyDown because we want to disable volume down action
            return true;
        }
        // for all other case, do the default
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode,KeyEvent event) {
        if (keyCode==KeyEvent.KEYCODE_VOLUME_DOWN) {
            flag_shortPress = false;
            // UI
            int newProgress = 0; // strongest brake
            if (sendSpeedCmd(newProgress)) {
                // UI
                mSeekbar.setProgress(newProgress);
                DisplaySpeed(newProgress);
            }
            // NOTE: no super.onKeyLongPress because we want to disable volume down action
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            flag_shortPress = false;
            // store the previous speed
            mSpeedTemp_VOL = mSeekbar.getProgress();
            if (mSpeedTemp_VOL <= NEUTRAL_SPEED) {
                // dont do anything if current speed is 0
                return true;
            }
            if (mSpeedTemp_VOL > NEUTRAL_SPEED) {
                mSpeedTemp_VOL -= 10; // the speed to be restored after key up is previous speed - 10.
                if (mSpeedTemp_VOL < NEUTRAL_SPEED) {
                    mSpeedTemp_VOL = NEUTRAL_SPEED;
                }
            }
            // Log.i(TAG, "stored current speed" + String.valueOf(mSpeedTemp_VOL) + String.valueOf(flag_shortPress));
            // set to neutral speed
            int newSpeed = NEUTRAL_SPEED;
            if (sendSpeedCmd(newSpeed)) {
                // UI
                mSeekbar.setProgress(newSpeed);
                DisplaySpeed(newSpeed);
            }
            // NOTE: no super.onKeyLongPress because we want to disable volume down action
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mToolbar.inflateMenu(R.menu.appbar_menu);
        mToolbar.setOnMenuItemClickListener(
                new Toolbar.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        return onOptionsItemSelected(item);
                    }
                });
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (flag_BLEConnected) {
            // change menu items
            MenuItem item = menu.findItem(R.id.appbar_connectBle);
            item.setTitle("Disconnect");
        } else {
            // change menu items
            MenuItem item = menu.findItem(R.id.appbar_connectBle);
            item.setTitle("Connect");
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.appbar_connectBle:
                // User chose the "ConnectBle" item
                // if BLE is not connected, connect Bluetooth
                if (!flag_BLEConnected) {
                    scanLeDevice(true);
                } else {
                    // disconnect bluetooth only if flag_stop is true.
                    if (flag_Stop) {
                        mBLEService.closeBLE();
                        unbindService(mConnection); // this will invoke service.close() as well.
                        setParam_BLEDisconnected();
                    } else {
                        DisplayToastMsg("You must stop skateboard first");
                    }
                }
                return true;
            case R.id.appbar_settings:
                return true;

            default:
                // If we got here, the user's action was not recognized.
                // Invoke the superclass to handle it.
                return super.onOptionsItemSelected(item);

        }
    }

    private void SetSeekbarOnChangeListener() {
        mSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                /* TODO disabled as it seems using volume to control is good enough
                if (sendSpeedCmd(i)) {
                    DisplaySpeed(i);
                }
                */
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    // Button
    private void setButtonOnClickListener() {
        mButton_Stop.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                // if currently stopped, click to start
                if (flag_Stop) {
                    // TODO disabled as it seems using volume to control is good enough
                    // mSeekbar.setEnabled(true);
                    flag_Stop = false;

                    // UI
                    mSeekbar.setProgress(NEUTRAL_SPEED);
                    DisplaySpeed(NEUTRAL_SPEED);
                    mButton_Stop.setText(getString(R.string.button_stop));
                    mTextView_Status.setText(getString(R.string.status_running));
                }
                // if currently started, click to stop
                else {
                    // tell the skateboard to stop
                    int speedVal = MIN_SPEED; // strongest brake
                    if (sendSpeedCmd(speedVal)) {
                        // UI
                        setParam_Stopped();
                    }
                }
            }
        });
    }

    private void SetLinearLayoutOnClickListener() {
        mLinearLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int curSpeed = mSeekbar.getProgress();

                if (mSpeedTemp_LL == MIN_SPEED) {
                    // this means that mSpeedTemp_LL is not set.

                    if (curSpeed <= NEUTRAL_SPEED) {
                        return;
                    }

                    int newSpeed = NEUTRAL_SPEED;
                    if (sendSpeedCmd(newSpeed)) {
                        // UI
                        mSeekbar.setProgress(newSpeed);
                        DisplaySpeed(newSpeed);
                    }

                    mSpeedTemp_LL = curSpeed - 10; // mSpeedTemp_LL could not equal to MIN_SPEED from here.
                    if (mSpeedTemp_LL < NEUTRAL_SPEED) {
                        mSpeedTemp_LL = NEUTRAL_SPEED;
                    }
                } else if (mSpeedTemp_LL >= NEUTRAL_SPEED && curSpeed == NEUTRAL_SPEED) {
                    // else if mSpeedTemp_LL is stored, restore to that speed upon click.

                    if (sendSpeedCmd(mSpeedTemp_LL)) {
                        // UI
                        mSeekbar.setProgress(mSpeedTemp_LL);
                        DisplaySpeed(mSpeedTemp_LL);
                    }
                    mSpeedTemp_LL = MIN_SPEED;
                }
            }
        });
    }

    /*
    /* Helper Functions
     */
    private void DisplayToastMsg(String msg) {
        CharSequence t = msg;
        mToast.setText(msg);
        mToast.show();
    }

    private void DisplaySpeed(int progress) {
        mTextView_SpeedVal.setText(String.valueOf(progress - NEUTRAL_SPEED));
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        // add when necesssary
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_SENT);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_SEND_FAIL);
        return intentFilter;
    }

    private void askForLocationPermissions() {

        // Should we show an explanation?
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.ACCESS_FINE_LOCATION)) {

            new android.support.v7.app.AlertDialog.Builder(this)
                    .setTitle("Location permessions needed")
                    .setMessage("you need to allow this permission!")
                    .setPositiveButton("Sure", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            ActivityCompat.requestPermissions(MainActivity.this,
                                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                    LOCATION_PERMISSION_REQUEST_CODE);
                        }
                    })
                    .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
//                                        //Do nothing
                        }
                    })
                    .show();

            // Show an expanation to the user *asynchronously* -- don't block
            // this thread waiting for the user's response! After the user
            // sees the explanation, try again to request the permission.

        } else {

            // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST_CODE);

            // MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION is an
            // app-defined int constant. The callback method gets the
            // result of the request.
        }
    }

    public static boolean isPermissionGranted(@NonNull String[] grantPermissions, @NonNull int[] grantResults,
                                              @NonNull String permission) {
        for (int i = 0; i < grantPermissions.length; i++) {
            if (permission.equals(grantPermissions[i])) {
                return grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case LOCATION_PERMISSION_REQUEST_CODE:
                if (!isPermissionGranted(permissions, grantResults, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    DisplayToastMsg("Can not proceed! i need permission");
                }
                break;
        }
    }
}
