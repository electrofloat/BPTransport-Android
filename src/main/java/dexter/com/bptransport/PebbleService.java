/*
        BPTransport-Android

        Copyright (C) 2017 DEXTER

        This program is free software: you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation, either version 3 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful,
        but WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
        GNU General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dexter.com.bptransport;

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Created by DEXTER on 2014.10.24..
 */
public class PebbleService extends Service {
    public static final int NEARBY_STOPS_LENGTH = 10;
    public static final int NEARBY_STOPS_DETAILS_LENGTH = 10;

    private final static UUID PEBBLE_APP_UUID = UUID.fromString("3acd6df6-9075-4c9e-b4f2-4c9accfbb8bf");
    private static final String GARMIN_APP_ID = "b89479ac76614c9cab49b417db1f53ca";

    private static final int MESSAGE_TYPE_GET_LANGUAGE                   = 0;
    private static final int MESSAGE_TYPE_GET_LANGUAGE_REPLY             = 1;
    private static final int MESSAGE_TYPE_GET_NEARBY_STOPS               = 2;
    private static final int MESSAGE_TYPE_GET_NEARBY_STOPS_REPLY         = 3;
    private static final int MESSAGE_TYPE_GET_NEARBY_STOPS_DETAILS       = 4;
    private static final int MESSAGE_TYPE_GET_NEARBY_STOPS_DETAILS_REPLY = 5;
    private static final int MESSAGE_TYPE_GET_FAVORITES                  = 6;

    private static final int LANGUAGE_TYPE_HUNGARIAN                     = 0;
    private static final int LANGUAGE_TYPE_ENGLISH                       = 1;

    private int nearby_stops_transaction_id = NEARBY_STOPS_LENGTH;
    private int nearby_stops_details_transaction_id = NEARBY_STOPS_DETAILS_LENGTH + 100;

    public static final Map<Integer, Set<Integer>> stop_id_cache = new HashMap<Integer, Set<Integer>>();
    public static final Map<Long, Stops> stops_cache = new HashMap<Long, Stops>();

    private final IBinder binder = new LocalBinder();
    private MainActivity main_activity = null;
    private NearbyStops[] nearby_stops_on_pebble;
    private NearbyStopsDetails[] nearby_stops_details_on_pebble;

    private AsyncNearbyStops async_nearby_stops = null;
    //private AsyncNearbyStopsDetails async_nearby_stops_details = null;
    private BroadcastReceiver pebble_data_receiver, pebble_ack_receiver, pebble_nack_receiver;
    private Timer timer = null;
    private static final int TIMERTASK_DELAY = 60000; //1 mins
    private final Handler handler = new Handler();

    private boolean garmin_sdk_ready = false;
    private boolean is_garmin_connected = false;
    private IQApp ciq_app;
    private ConnectIQ connect_iq;
    private IQDevice garmin_device;
    //private int garmin_retransmit = 0;
    ConnectIQ.IQSendMessageListener iq_send_message_listener;
    //List<Object> garmin_data;
    private List<List<Object>> messageQueue = Collections.synchronizedList(new ArrayList<List<Object>>());
    private AtomicBoolean deliveryInProgress = new AtomicBoolean(false);
    private int deliveryErrorCount = 0;
    public static final int MAX_DELIVERY_ERROR = 3;

    public static class NearbyStops
    {
        int distance;
        long id;
        String string_id;
        String line_numbers;
        String direction_name;
        String stop_name;
        String distance_string;
        String stop_color_type;
        int direction;
    }

    public static class NearbyStopsDetails
    {
        String line_number;
        String direction_name;
        int start_time;
        int predicted_start_time;
        int stop_id;
        int trip_id;
    }

    public static class Stops
    {
        Location location;
        String name;
        String string_id;
    }

    public PebbleService()
    {

    }

    public class LocalBinder extends Binder {
        PebbleService getService() {
            // Return this instance of LocalService so clients can call public methods
            return PebbleService.this;
        }
    }

    private ConnectIQ.ConnectIQListener ciq_listener = new ConnectIQ.ConnectIQListener() {

        @Override
        public void onInitializeError(ConnectIQ.IQSdkErrorStatus errStatus) {
            garmin_sdk_ready = false;
        }

        @Override
        public void onSdkReady() {
            garmin_sdk_ready = true;
            try {
                List<IQDevice> devices = connect_iq.getKnownDevices();
                if (devices != null && devices.size() > 0) {
                    garmin_device = devices.get(0);
                    connect_iq.registerForAppEvents(garmin_device, ciq_app, new ConnectIQ.IQApplicationEventListener() {

                        @Override
                        public void onMessageReceived(IQDevice device, IQApp app, List<Object> message, ConnectIQ.IQMessageStatus status) {
                            // First inspect the status to make sure this
                            // was a SUCCESS. If not then the status will indicate why there
                            // was an issue receiving the message from the Connect IQ application.
                            stop_updates();
                            if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                                if (message.size() > 0) {
                                    for (Object o : message) {
                                        Log.d(getPackageName(), "Received message: " + o.toString());
                                        int type = (Integer)o;
                                        switch (type) {
                                            case MESSAGE_TYPE_GET_LANGUAGE: // Discover bulbs.
                                                is_garmin_connected = true;
                                                send_get_language_reply_to_garmin();
                                                break;
                                            case MESSAGE_TYPE_GET_NEARBY_STOPS:
                                                //get_nearby_stops();
                                                //start_updates();
                                                handler.post(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        start_updates();
                                                    }
                                                });
                                                break;
                                            case MESSAGE_TYPE_GET_NEARBY_STOPS_DETAILS:
                                                break;
                                            default:
                                                Log.e(getPackageName(), "Received unexpected value/message from Garmin device: " + type);
                                                break;
                                        };
                                    }
                                } else {
                                    Log.d(getPackageName(), "Received an empty message from the application");
                                }
                                // Handle the message.
                            }
                        }
                    });
                }
            } catch (InvalidStateException e) {
                e.printStackTrace();
            } catch (ServiceUnavailableException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSdkShutDown() {
            garmin_sdk_ready = false;
        }

    };

    public void onCreate()
    {
        async_nearby_stops =  new AsyncNearbyStops(this);
        ciq_app = new IQApp(GARMIN_APP_ID);
        connect_iq = ConnectIQ.getInstance(getApplicationContext(), ConnectIQ.IQConnectType.TETHERED);
        //ConnectIQ connect_iq = ConnectIQ.getInstance(getApplicationContext(), ConnectIQ.IQConnectType.WIRELESS);
        connect_iq.initialize(getApplicationContext(), true, ciq_listener);


        PebbleKit.registerPebbleConnectedReceiver(getApplicationContext(), new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(getPackageName(), "Pebble connected!");
            }
        });

        PebbleKit.registerPebbleDisconnectedReceiver(getApplicationContext(), new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(getPackageName(), "Pebble disconnected!");
                stop_updates();
            }
        });

        //async_nearby_stops_details = new AsyncNearbyStopsDetails(this);

        Log.d(getPackageName(), "Created PebbleService;");
        //get_stops();
    }

    public int onStartCommand(Intent intent, int flags, int start_id)
    {
        pebble_data_receiver = PebbleKit.registerReceivedDataHandler(this, new PebbleKit.PebbleDataReceiver(PEBBLE_APP_UUID) {
            @Override
            public void receiveData(final Context context, final int transactionId, final PebbleDictionary data) {
                Log.i(getPackageName(), "Received value = " + data.getUnsignedIntegerAsLong(0) + " for key: 0");
                receiveMessage(data, transactionId);
            }
        });
        pebble_ack_receiver = PebbleKit.registerReceivedAckHandler(getApplicationContext(), new PebbleKit.PebbleAckReceiver(PEBBLE_APP_UUID) {

            @Override
            public void receiveAck(Context context, int transactionId) {
                //Log.i(getLocalClassName(), "Received ack for transaction " + transactionId);
                if (transactionId < 100 - 1) {
                    nearby_stops_transaction_id++;
                    if (nearby_stops_transaction_id >= NEARBY_STOPS_LENGTH)
                        return;
                    send_next_nearby_stop_to_pebble();
                }
                else
                {
                    nearby_stops_details_transaction_id++;
                    if (nearby_stops_details_transaction_id >= NEARBY_STOPS_DETAILS_LENGTH + 100)
                        return;
                    send_next_nearby_stops_detail_to_pebble();
                }
            }

        });

        pebble_nack_receiver = PebbleKit.registerReceivedNackHandler(getApplicationContext(), new PebbleKit.PebbleNackReceiver(PEBBLE_APP_UUID) {

            @Override
            public void receiveNack(Context context, int transactionId) {
                //Log.i(getLocalClassName(), "Received nack for transaction " + transactionId);
                if (transactionId < 100 - 1) {
                    if (nearby_stops_transaction_id >= NEARBY_STOPS_LENGTH ||
                        nearby_stops_transaction_id < 0)
                        return;
                    send_next_nearby_stop_to_pebble();
                }
                else
                {
                    if (nearby_stops_details_transaction_id >= NEARBY_STOPS_DETAILS_LENGTH + 100 ||
                        nearby_stops_details_transaction_id < 100)
                        return;
                    send_next_nearby_stops_detail_to_pebble();
                }
            }

        });

        return START_STICKY;
    }

    private void send_get_language_reply_to_garmin(){
        ArrayList<Object> garmin_data = new ArrayList<Object>();
        garmin_data.add(MESSAGE_TYPE_GET_LANGUAGE_REPLY);
        enqueue_to_garmin(garmin_data);
    }

    private void send_get_language_reply()
    {
        if (PebbleKit.areAppMessagesSupported(getApplicationContext())) {
            PebbleDictionary language = new PebbleDictionary();
            language.addUint8(0, (byte) MESSAGE_TYPE_GET_LANGUAGE_REPLY);
            language.addUint8(1, (byte) LANGUAGE_TYPE_ENGLISH);

            Log.i(getPackageName(), "Dictionary: " + language.toJsonString());
            PebbleKit.sendDataToPebble(getApplicationContext(), PEBBLE_APP_UUID, language);
            Log.i(getPackageName(), "Data sent.");
        }
    }

    private class MyRunnable implements Runnable
    {
        private PebbleService service;
        long id;
        String string_id;

        public MyRunnable(PebbleService service, long id, String string_id)
        {
            this.service = service;
            this.id = id;
            this.string_id =string_id;
        }

        @Override
        public void run() {
            new AsyncNearbyStopsDetails(service).execute(id,string_id);
        }
    }
    public void receiveMessage (PebbleDictionary dictionary, int transactionId) {
        //this.transaction_id = transactionId; // make sure transactionId is set before calling (onStart)
        PebbleKit.sendAckToPebble(getApplicationContext(), transactionId);
        stop_updates();
        nearby_stops_transaction_id = NEARBY_STOPS_LENGTH;
        nearby_stops_details_transaction_id = NEARBY_STOPS_DETAILS_LENGTH + 100;
        int type = dictionary.getUnsignedIntegerAsLong(0).intValue();
        switch (type) {
            case MESSAGE_TYPE_GET_LANGUAGE: // Discover bulbs.
                send_get_language_reply();

                break;
            case MESSAGE_TYPE_GET_NEARBY_STOPS:
                //get_nearby_stops();
                //start_updates();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        start_updates();
                    }
                });
                break;
            case MESSAGE_TYPE_GET_NEARBY_STOPS_DETAILS:
                //stop_updates();
                int id = dictionary.getUnsignedIntegerAsLong(1).intValue();
                if (id < 0 || id > 9)
                    return;
                //
                handler.post(new MyRunnable(this, nearby_stops_on_pebble[id].id, nearby_stops_on_pebble[id].string_id));

                //async_nearby_stops_details.execute(id, nearby_stops_on_pebble[id].direction);
                break;
            default:
                Log.e(getPackageName(), "Received unexpected value/message from Pebble: " + type);
                break;
        }
    }
    @Override
    public IBinder onBind(Intent intent)
    {
        return binder;
    }

    public void notify_progressbar(int progress)
    {
        if (main_activity != null)
            main_activity.notify_progressbar(progress);
    }

    public void subscribe(MainActivity activity)
    {
        main_activity = activity;
    }

    public void unsubscribe()
    {
        main_activity = null;
    }


    public void start_updates()
    {
        //check for google play services
        //google_api_client.connect();
        async_nearby_stops =  new AsyncNearbyStops(this);
        async_nearby_stops.execute();

        class stop_nearby_updates_timertask extends TimerTask {

            @Override
            public void run() {
                stop_updates();
            }
        };

        timer = new Timer();
        timer.schedule(new stop_nearby_updates_timertask(), TIMERTASK_DELAY);
    }

    public void stop_updates()
    {
        if (async_nearby_stops != null) {
            async_nearby_stops.cancel(true);
            async_nearby_stops.condition.open();
            async_nearby_stops = null;
        }

        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
    }

    public void get_details()
    {
        Runnable r = new MyRunnable(this, nearby_stops_on_pebble[0].id, nearby_stops_on_pebble[0].string_id);
        handler.postDelayed(r, 1000);
    }

    public void store_nearby_stops(NearbyStops[] nearby_stops)
    {
        String string = "";
        for (int i = 0; i < PebbleService.NEARBY_STOPS_LENGTH; i++) {
            if (nearby_stops[i].distance <= 999)
                nearby_stops[i].distance_string = String.valueOf(nearby_stops[i].distance) + "m";
            Log.d(getPackageName(), "Stop name: " + nearby_stops[i].stop_name + ", direction_name: " + nearby_stops[i].direction_name + ",line_numbers: " + nearby_stops[i].line_numbers + ", distance: " + nearby_stops[i].distance_string + ", id: " + nearby_stops[i].id);
            string += "Stop name: " + nearby_stops[i].stop_name + ", direction_name: " + nearby_stops[i].direction_name + ",line_numbers: "+nearby_stops[i].line_numbers + ", distance: " + nearby_stops[i].distance_string + ", id: " + nearby_stops[i].id + "\n";
        }
        if (main_activity != null)
            main_activity.fill_textview(string);

        if (is_garmin_connected) {
            send_nearby_stops_to_garmin(nearby_stops);
            return;
        }
        nearby_stops_on_pebble = nearby_stops;
        if (nearby_stops_transaction_id < NEARBY_STOPS_LENGTH)
            nearby_stops_transaction_id = -1;
        else {
            nearby_stops_transaction_id = 0;
            send_next_nearby_stop_to_pebble();
        }

        //send to pebble
    }

    private void send_nearby_stops_to_garmin(NearbyStops[] nearby_stops) {
        ArrayList<Object> garmin_data = new ArrayList<Object>();

        garmin_data.add(MESSAGE_TYPE_GET_NEARBY_STOPS_REPLY);
        for (int i = 0; i < PebbleService.NEARBY_STOPS_LENGTH; i++) {
            if (nearby_stops[i].string_id == null || nearby_stops[i].string_id.isEmpty())
                continue;
            Map<String, Object> dictionary = new HashMap<String, Object>();
            dictionary.put("stop_id", nearby_stops[i].string_id);
            dictionary.put("stop_name", nearby_stops[i].stop_name);
            dictionary.put("direction_name", nearby_stops[i].direction_name);
            dictionary.put("line_numbers", nearby_stops[i].line_numbers);
            dictionary.put("distance", nearby_stops[i].distance);
            dictionary.put("stop_color_type", nearby_stops[i].stop_color_type);
            garmin_data.add(dictionary);
        }

        enqueue_to_garmin(garmin_data);
    }
    private Runnable sendMessageRunnable = new Runnable() {
        @Override
        public void run() {

            sendNextMessage();
        }
    };

    private void sendNextMessage() {
        //TODO: check connectiq sdk ready
        if (deliveryInProgress.get()) {
            deliveryErrorCount++;
            if (deliveryErrorCount > MAX_DELIVERY_ERROR) {
                messageQueue.remove(0);
                deliveryErrorCount = 0;
                Log.d(getPackageName(), "Reached MAX_DELIVERY_ERROR, removing message");
            }
        }
        if (messageQueue.size() == 0) {
            deliveryInProgress.set(false);
            return;
        }
        List<Object> message = messageQueue.get(0);
        deliveryInProgress.set(true);

        Log.d(getPackageName(), "Sending next message NOW.");
        try {
            connect_iq.sendMessage(garmin_device, ciq_app, message, new ConnectIQ.IQSendMessageListener() {
                @Override
                public void onMessageStatus(IQDevice iqDevice, IQApp iqApp, ConnectIQ.IQMessageStatus iqMessageStatus) {
                    if (iqMessageStatus != ConnectIQ.IQMessageStatus.SUCCESS) {
                        Log.d(getPackageName(), "Message " + iqMessageStatus.toString() + ", requeuing");
                        handler.postDelayed(sendMessageRunnable, 200 + 200 * deliveryErrorCount);
                    } else {
                        Log.d(getPackageName(), "Message SUCCESS");
                        messageQueue.remove(0);
                        deliveryErrorCount = 0;
                        deliveryInProgress.set(false);
                        handler.postDelayed(sendMessageRunnable, 200);
                    }
                }
            });
        } catch (InvalidStateException e) {
            e.printStackTrace();
        } catch (ServiceUnavailableException e) {
            e.printStackTrace();
        }

    }

    private void enqueue_to_garmin(List<Object> garmin_data) {
        if (!messageQueue.contains(garmin_data)) {
            messageQueue.add(garmin_data);
            Log.d(getPackageName(), "Queued garmin_data");
        }
        handler.post(sendMessageRunnable);
    }

    private void send_next_nearby_stop_to_pebble() {
        if (!PebbleKit.areAppMessagesSupported(getApplicationContext()))
            return;

            PebbleDictionary nearby_stop = new PebbleDictionary();
            nearby_stop.addUint8(0, (byte) MESSAGE_TYPE_GET_NEARBY_STOPS_REPLY);
            nearby_stop.addUint8(1, (byte) nearby_stops_transaction_id);
            nearby_stop.addString(2, nearby_stops_on_pebble[nearby_stops_transaction_id].stop_name);
            nearby_stop.addString(3, nearby_stops_on_pebble[nearby_stops_transaction_id].direction_name);
            nearby_stop.addString(4, nearby_stops_on_pebble[nearby_stops_transaction_id].line_numbers);
            nearby_stop.addString(5, nearby_stops_on_pebble[nearby_stops_transaction_id].distance_string);
            //Log.i(getPackageName(), "Dictionary: " + nearby_stop.toJsonString());

            PebbleKit.sendDataToPebbleWithTransactionId(getApplicationContext(), PEBBLE_APP_UUID, nearby_stop, nearby_stops_transaction_id);

     }

    public void store_nearby_stops_details(NearbyStopsDetails[] nearby_stops_details)
    {
        String string ="";
        for (int i = 0; i < PebbleService.NEARBY_STOPS_DETAILS_LENGTH; i++) {

            Log.d(getPackageName(), "Line num: " + nearby_stops_details[i].line_number + ", direction_name: " + nearby_stops_details[i].direction_name + ",starttime: " + nearby_stops_details[i].start_time + ", tripid: " + nearby_stops_details[i].trip_id + ",predstart: " + nearby_stops_details[i].predicted_start_time);
            string += "Line num: " + nearby_stops_details[i].line_number + ", direction_name: " + nearby_stops_details[i].direction_name + ",starttime: " + nearby_stops_details[i].start_time + ", tripid: " + nearby_stops_details[i].trip_id + ",predstart: "+nearby_stops_details[i].predicted_start_time + "\n";
        }
        nearby_stops_details_on_pebble = nearby_stops_details;
        if (nearby_stops_details_transaction_id < NEARBY_STOPS_DETAILS_LENGTH + 100)
            nearby_stops_details_transaction_id = 100-1;
        else {
            nearby_stops_details_transaction_id = 100;
            send_next_nearby_stops_detail_to_pebble();
        }

        //send_next_nearby_stops_detail_to_pebble();
        if (main_activity != null)
            main_activity.fill_textview(string);
        //send to pebble
    }

    private void send_next_nearby_stops_detail_to_pebble() {
        //if (!PebbleKit.areAppMessagesSupported(getApplicationContext()))
        //    return;

        //for (int i = 0; i < PebbleService.NEARBY_STOPS_DETAILS_LENGTH; i++)
        //{
            int i = nearby_stops_details_transaction_id - 100;
            PebbleDictionary nearby_stop_details = new PebbleDictionary();
            nearby_stop_details.addUint8(0, (byte) MESSAGE_TYPE_GET_NEARBY_STOPS_DETAILS_REPLY);
            nearby_stop_details.addUint8(1, (byte) i);
            nearby_stop_details.addString(2, nearby_stops_details_on_pebble[i].line_number);
            nearby_stop_details.addString(3, nearby_stops_details_on_pebble[i].direction_name);
            int seconds;
            if (nearby_stops_details_on_pebble[i].start_time == 99999)
              seconds = 0;
            else
              seconds = nearby_stops_details_on_pebble[i].start_time;
            nearby_stop_details.addUint32(4, seconds);
            seconds = nearby_stops_details_on_pebble[i].predicted_start_time;
            nearby_stop_details.addUint32(5, seconds);
            //nearby_stop.addString(5, nearby_stops_on_pebble[i].distance_string);
            //Log.i(getPackageName(), "Dictionary: " + nearby_stop_details.toJsonString());
            //if (main_activity != null)
            //    main_activity.fill_textview(nearby_stop_details.toJsonString());
            PebbleKit.sendDataToPebbleWithTransactionId(getApplicationContext(), PEBBLE_APP_UUID, nearby_stop_details, nearby_stops_details_transaction_id);
            //Log.i(getPackageName(), "Data sent.");
        //}
    }

    @Override
    public void onDestroy()
    {
        try {
            unregisterReceiver(pebble_data_receiver);
            unregisterReceiver(pebble_ack_receiver);
            unregisterReceiver(pebble_nack_receiver);
        } catch (IllegalArgumentException e) {

        }
        super.onDestroy();

        Toast.makeText(getApplicationContext(), "PebbleService stopped;", Toast.LENGTH_SHORT).show();
    }

}
