package com.adafruit.bluefruit.le.connect.app;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.adafruit.bluefruit.le.connect.R;
import com.adafruit.bluefruit.le.connect.app.settings.ConnectedSettingsActivity;
import com.adafruit.bluefruit.le.connect.app.settings.MqttUartSettingsActivity;
import com.adafruit.bluefruit.le.connect.ble.BleManager;
import com.adafruit.bluefruit.le.connect.mqtt.MqttManager;
import com.adafruit.bluefruit.le.connect.mqtt.MqttSettings;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationListener;


import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

import br.com.goncalves.pugnotification.notification.PugNotification;

public class UartPannicActivity extends UartInterfaceActivity implements BleManager.BleManagerListener, MqttManager.MqttManagerListener, ConnectionCallbacks, OnConnectionFailedListener, LocationListener {
    // Log
    private final static String TAG = UartPannicActivity.class.getSimpleName();

    // Configuration
    private final static boolean kUseColorsForData = true;
    private final static boolean kShowUartControlsInTopBar = true;
    public final static int kDefaultMaxPacketsToPaintAsText = 500;


    // Activity request codes (used for onActivityResult)
    private static final int kActivityRequestCode_ConnectedSettingsActivity = 0;
    private static final int kActivityRequestCode_MqttSettingsActivity = 1;

    // Constants
    private final static String kPreferences = "UartActivity_prefs";
    private final static String kPreferences_eol = "eol";
    private final static String kPreferences_echo = "echo";
    private final static String kPreferences_asciiMode = "ascii";
    private final static String kPreferences_timestampDisplayMode = "timestampdisplaymode";

    // Colors
    private int mTxColor;
    private int mRxColor;
    private int mInfoColor = Color.parseColor("#F21625");

    // UI
    private EditText mBufferTextView;
    private ListView mBufferListView;
    private EditText mSendEditText;
    private MenuItem mMqttMenuItem;
    private Handler mMqttMenuItemAnimationHandler;
    private TextView mSentBytesTextView;
    private TextView mReceivedBytesTextView;

    // UI TextBuffer (refreshing the text buffer is managed with a timer because a lot of changes an arrive really fast and could stall the main thread)
    private Handler mUIRefreshTimerHandler = new Handler();
    private boolean isUITimerRunning = false;

    // Data
    private boolean mShowDataInHexFormat;
    private boolean mIsTimestampDisplayMode;
    private boolean mIsEchoEnabled;
    private boolean mIsEolEnabled;

    private volatile SpannableStringBuilder mTextSpanBuffer;
    private volatile ArrayList<UartDataChunk> mDataBuffer;
    private volatile int mSentBytes;
    private volatile int mReceivedBytes;

    private MqttManager mMqttManager;

    private int maxPacketsToPaintAsText;

    //custom
    private LinearLayout circleShape;
    private TextView pannicText;
    private Boolean successConected = false;
    private Boolean onPannic = false;
    private Context mContext;

    private GoogleApiClient mGoogleApiClient;

    private Location mCurrentLocation;
    private LocationRequest mLocationRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pannic_uart);

        mBleManager = BleManager.getInstance(this);
        circleShape = (LinearLayout) findViewById(R.id.circle);
        pannicText = (TextView) findViewById(R.id.status);
        mContext = this;
        // Continue
        onServicesDiscovered();
        sendByteConnection();

        buildGoogleApiClient();

        if (mGoogleApiClient != null) {
            Log.d("PANNIC", "run connect");
            mGoogleApiClient.connect();
        } else {
            Log.d("PANNIC", "fallo iniciar");
            Toast.makeText(this, "Not connected...", Toast.LENGTH_SHORT).show();
        }

    }

    protected void pannic(final Boolean status){
        Log.d("PANNIC", "OnPannic");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                GradientDrawable backgroundGradient = (GradientDrawable)circleShape.getBackground();
                if (status) {
                    backgroundGradient.setColors(new int[]{0xFFFF0000, 0x80FF00FF});
                    pannicText.setText("**¡PANICO!**");
                    showNotify();
                    activateTracking();
                } else {
                    backgroundGradient.setColors(new int[]{0xFF03507B, 0xFF52FF44});
                    pannicText.setText("Todo bien...");
                    stopTracking();
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        // Setup listeners
        mBleManager.setBleListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroy() {
        // Disconnect mqtt

        // Retain data
        super.onDestroy();
    }

    public void dismissKeyboard(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public void onClickSend(View view) {
        String data = mSendEditText.getText().toString();
        mSendEditText.setText("");       // Clear editText

        uartSendData(data, false);
    }

    private void sendByteConnection() {
        Log.d("pannic", "connected");
        String data = "1\n";
        sendData(data);
    }

    private void uartSendData(String data, boolean wasReceivedFromMqtt) {
        // MQTT publish to TX
        MqttSettings settings = MqttSettings.getInstance(UartPannicActivity.this);
        if (!wasReceivedFromMqtt) {
            if (settings.isPublishEnabled()) {
                String topic = settings.getPublishTopic(MqttUartSettingsActivity.kPublishFeed_TX);
                final int qos = settings.getPublishQos(MqttUartSettingsActivity.kPublishFeed_TX);
                mMqttManager.publish(topic, data, qos);
            }
        }

        // Add eol
        if (mIsEolEnabled) {
            // Add newline character if checked
            data += "\n";
        }

        // Send to uart
        if (!wasReceivedFromMqtt || settings.getSubscribeBehaviour() == MqttSettings.kSubscribeBehaviour_Transmit) {
            sendData(data);
            mSentBytes += data.length();
        }

        // Add to current buffer
        UartDataChunk dataChunk = new UartDataChunk(System.currentTimeMillis(), UartDataChunk.TRANSFERMODE_TX, data);
        mDataBuffer.add(dataChunk);

        final String formattedData = mShowDataInHexFormat ? asciiToHex(data) : data;
        if (mIsTimestampDisplayMode) {
            final String currentDateTimeString = DateFormat.getTimeInstance().format(new Date(dataChunk.getTimestamp()));
            //mBufferListAdapter.add(new TimestampData("[" + currentDateTimeString + "] TX: " + formattedData, mTxColor));
            //mBufferListView.setSelection(mBufferListAdapter.getCount());
        }

        // Update UI
        //updateUI();
    }


    // region Menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.

        return true;
    }

    private int mMqttMenuItemAnimationFrame = 0;


    private void startConnectedSettings() {
        // Launch connected settings activity
        Intent intent = new Intent(this, ConnectedSettingsActivity.class);
        startActivityForResult(intent, kActivityRequestCode_ConnectedSettingsActivity);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == kActivityRequestCode_ConnectedSettingsActivity && resultCode == RESULT_OK) {
            finish();
        } else if (requestCode == kActivityRequestCode_MqttSettingsActivity && resultCode == RESULT_OK) {

        }
    }

    private void startHelp() {
        // Launch app help activity
        Intent intent = new Intent(this, CommonHelpActivity.class);
        intent.putExtra("title", getString(R.string.uart_help_title));
        intent.putExtra("help", "uart_help.html");
        startActivity(intent);
    }
    // endregion

    // region BleManagerListener
    @Override
    public void onConnected() {

    }

    @Override
    public void onConnecting() {

    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "Disconnected. Back to previous activity");
        finish();
    }

    @Override
    public void onServicesDiscovered() {
        mUartService = mBleManager.getGattService(UUID_SERVICE);

        mBleManager.enableNotification(mUartService, UUID_RX, true);
    }

    @Override
    public synchronized void onDataAvailable(BluetoothGattCharacteristic characteristic) {
        if (characteristic.getService().getUuid().toString().equalsIgnoreCase(UUID_SERVICE)) {
            if (characteristic.getUuid().toString().equalsIgnoreCase(UUID_RX)) {
                final byte[] bytes = characteristic.getValue();
                final String data = new String(bytes, Charset.forName("UTF-8"));
                Log.d("PANNIC", data);
                if (data.toString().startsWith("PANNIC") && !onPannic) {
                    Log.d("PANNIC", "recv!");
                    onPannic = true;
                    pannic(true);
                } else if (data.toString().equalsIgnoreCase("AT+GAPGETCONN") && !successConected) {
                    sendByteConnection();
                } else if (data.toString().equalsIgnoreCase("ATI=4")) {
                    //on success connect.
                    successConected = true;
                }
            }
        }
    }


    @Override
    public void onDataAvailable(BluetoothGattDescriptor descriptor) {

    }

    @Override
    public void onReadRemoteRssi(int rssi) {

    }
    // endregion




    private int mDataBufferLastSize = 0;


    private String asciiToHex(String text) {
        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < text.length(); i++) {
            String charString = String.format("0x%02X", (byte) text.charAt(i));

            stringBuffer.append(charString + " ");
        }
        return stringBuffer.toString();
    }

    @Override
    public void onMqttConnected() {

    }

    @Override
    public void onMqttDisconnected() {

    }

    @Override
    public void onMqttMessageArrived(String topic, MqttMessage mqttMessage) {

    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    public void changePannicStatus(View view) {
        if (onPannic) {
            onPannic = false;
            pannic(false);
        } else {
            onPannic = true;
            pannic(true);
        }
    }

    public void showNotify() {
        Log.d("PANNIC", "NOTIFIIIII");
        PugNotification.with(mContext)
            .load()
            .click(UartPannicActivity.class)
            .vibrate(new long[]{300, 300})
            .smallIcon(R.drawable.pugnotification_ic_launcher)
            .autoCancel(true)
            .largeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.pannic))
            .title("pannic!")
            .message("modo emergencia activado").simple().build();
    }

    //Google play api
    @Override
    public void onConnectionFailed(ConnectionResult arg0) {
        Log.d("PANNIC", "fallo");
        Toast.makeText(this, "Failed to connect...", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onConnected(Bundle arg0) {
        Log.d("PANNIC", "connected");
        mCurrentLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);

        if (mCurrentLocation != null) {
            Log.d("PANNIC", "Latitude: "+ String.valueOf(mCurrentLocation.getLatitude())+"Longitude: "+
                    String.valueOf(mCurrentLocation.getLongitude()));
            //startLocationUpdates();
        } else {
            Log.d("PANNIC", "location empty");
        }

    }

    @Override
    public void onConnectionSuspended(int arg0) {
        Log.d("PANNIC", "suspend");
        Toast.makeText(this, "Connection suspended...", Toast.LENGTH_SHORT).show();

    }

    protected synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
        createLocationRequest();
    }

    //location updates
    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(10000);
        mLocationRequest.setFastestInterval(5000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    protected void startLocationUpdates() {
        LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClient, mLocationRequest, this);
    }



    @Override
    public void onLocationChanged(Location location) {
        mCurrentLocation = location;
        String mLastUpdateTime = DateFormat.getTimeInstance().format(new Date());
        Log.d("PANNIC", mLastUpdateTime);
        Log.d("PANNIC", "Latitude: "+ String.valueOf(mCurrentLocation.getLatitude())+"Longitude: "+
                String.valueOf(mCurrentLocation.getLongitude()));
        Toast.makeText(this, "update location",
                Toast.LENGTH_SHORT).show();
    }

    private void activateTracking(){
        Log.d("PANNIC", "Tracking mode on");
        if (mCurrentLocation != null) {
            Log.d("PANNIC", "Latitude: "+ String.valueOf(mCurrentLocation.getLatitude())+"Longitude: "+
                    String.valueOf(mCurrentLocation.getLongitude()));
            startLocationUpdates();
        }
    }

    private void stopTracking(){
        Log.d("PANNIC", "Stop tracking mode");
        if (mGoogleApiClient != null && mCurrentLocation != null) {
            LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
        }
    }
}
