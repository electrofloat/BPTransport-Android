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

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
//import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Created by DEXTER on 2014.11.09..
 */
public class AsyncNearbyStops extends AsyncTask<Void, PebbleService.NearbyStops[], Void> implements
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener {
    //private LocationClient location_client;
    private LocationRequest location_request;
    private boolean location_is_connected = false;
    private PebbleService service;
    private Context local_context;
    private Location location;
    private GoogleApiClient google_api_client;
    private JSONObject json;
    private static final int DEVIATION = 3; //in meters;

    public ConditionVariable condition = new ConditionVariable(false);

    // Milliseconds per second
    private static final int MILLISECONDS_PER_SECOND = 1000;
    // Update frequency in seconds
    public static final int UPDATE_INTERVAL_IN_SECONDS = 5;
    // Update frequency in milliseconds
    private static final long UPDATE_INTERVAL =
            MILLISECONDS_PER_SECOND * UPDATE_INTERVAL_IN_SECONDS;
    // The fastest update frequency, in seconds
    private static final int FASTEST_INTERVAL_IN_SECONDS = 5;
    // A fast frequency ceiling in milliseconds
    private static final long FASTEST_INTERVAL =
            MILLISECONDS_PER_SECOND * FASTEST_INTERVAL_IN_SECONDS;

    public AsyncNearbyStops(PebbleService srv)
    {
        service = srv;
        local_context = service.getApplicationContext();
        location_request = LocationRequest.create();
        location_request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        location_request.setInterval(UPDATE_INTERVAL);
        location_request.setFastestInterval(FASTEST_INTERVAL);

        google_api_client = new GoogleApiClient.Builder(service.getApplicationContext())
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    @Override
    protected void onPreExecute() {
        google_api_client.connect();


    }

    private String[] get_shortname_and_direction(String route_id, String id) {
        String[] ret = new String[2];
        try {
            URL url = new URL("http://futar.bkk.hu/bkk-utvonaltervezo-api/ws/otp/api/where/route-details.json?includeReferences=false&routeId=" + route_id);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(5000 /* milliseconds */);
            conn.setConnectTimeout(5000 /* milliseconds */);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            // Starts the query
            conn.connect();
            int response = conn.getResponseCode();
            //Log.d(context.getPackageName(), "The response is: " + response);
            int total = conn.getContentLength() * 2;
            InputStream inputstream = conn.getInputStream();
            String data = convertStreamToString(inputstream);

            json = new JSONObject(data);
            if (!json.getString("status").equals("OK"))
                return ret;

            JSONObject json_data = json.getJSONObject("data");
            JSONObject entry = json_data.getJSONObject("entry");
            String shortname = entry.getString("shortName");
            ret[0] = shortname;
            JSONArray variants = entry.getJSONArray("variants");
            for (int i = 0; i < variants.length() && !isCancelled(); i++) {
                JSONObject row = variants.getJSONObject(i);
                JSONArray stop_ids = row.getJSONArray("stopIds");
                for (int j = 0; j < stop_ids.length() && !isCancelled(); j++) {
                    String stop_id = stop_ids.getString(j);
                    if (stop_id.equals(id)) {
                        String headsign = row.getString("headsign");
                        ret[1] = headsign;
                        Log.d(local_context.getPackageName(), "stop_id: " + id + " route_id: " + route_id + " headsign: " + headsign);
                        return ret;
                    }
                }
            }


        } catch (Exception e) {

        }

        return ret;
    }

    private PebbleService.NearbyStops[] get_nearby_stops_online(Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();

        try {

            URL url = new URL("http://futar.bkk.hu/bkk-utvonaltervezo-api/ws/otp/api/where/stops-for-location.json?lon=" + lon + "&lat=" + lat + "&radius=500");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(5000 /* milliseconds */);
            conn.setConnectTimeout(5000 /* milliseconds */);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            // Starts the query
            conn.connect();
            int response = conn.getResponseCode();
            //Log.d(context.getPackageName(), "The response is: " + response);
            int total = conn.getContentLength() * 2;
            InputStream inputstream = conn.getInputStream();
            String data = convertStreamToString(inputstream);

            json = new JSONObject(data);

        } catch (Exception e) {

        }

        PebbleService.NearbyStops[] nearby_stops = new PebbleService.NearbyStops[PebbleService.NEARBY_STOPS_LENGTH];
        for (int i = 0; i < PebbleService.NEARBY_STOPS_LENGTH;i++) {
            nearby_stops[i] = new PebbleService.NearbyStops();
            nearby_stops[i].distance = Integer.MAX_VALUE;
            nearby_stops[i].distance_string = "999m";
            nearby_stops[i].direction_name = "->";
            nearby_stops[i].line_numbers = "";
            nearby_stops[i].stop_name = "";
            nearby_stops[i].stop_color_type = "";
        }

        try {
            if (!json.getString("status").equals("OK"))
                return nearby_stops;

            JSONObject data = json.getJSONObject("data");
            JSONArray list = data.getJSONArray("list");
            JSONObject references = data.getJSONObject("references");
            JSONObject routes = references.getJSONObject("routes");
            //int min = Math.min(list.length(), PebbleService.NEARBY_STOPS_LENGTH);
            for (int i = 0; i < list.length() && !isCancelled(); i++) {
                JSONObject row = list.getJSONObject(i);
                String id = row.getString("id");
                Location stop_location = new Location("");
                stop_location.setLatitude(row.getDouble("lat"));
                stop_location.setLongitude(row.getDouble("lon"));
                int distance = (int)location.distanceTo(stop_location);
                JSONArray route_ids = row.getJSONArray("routeIds");
                String stop_color_type = row.getString("stopColorType");
                if (route_ids.length() == 0)
                    continue;
                String line_numbers = "";
                String direction_name = "";
                for (int j = 0; j<route_ids.length();j++)
                {
                    String route_id = route_ids.getString(j);
                    String ret[] = get_shortname_and_direction(route_id, id);
                    Log.d(local_context.getPackageName(), "ret[0]: " + ret[0] + " ret[1]: " + ret[1]);
                    line_numbers += ret[0] + ", ";
                    if (direction_name.isEmpty())
                        direction_name = "->" + ret[1];
                    /*JSONObject route = routes.getJSONObject(route_id);
                    line_numbers += route.getString("shortName") + ", ";
                    if (direction_name.isEmpty())
                        direction_name = "->" + route.getString("description");*/
                }
                if (direction_name.length() > 31)
                    direction_name = direction_name.substring(0,31);
                if (!line_numbers.isEmpty())
                    line_numbers = line_numbers.substring(0, line_numbers.length() - 2);
                if (line_numbers.length() > 31)
                    line_numbers = line_numbers.substring(0,31);

                insert_into_nearby_stops_online(nearby_stops, distance, 0, row.getString("name"), id, line_numbers, direction_name, stop_color_type);
            }

        } catch(Exception e) {
        }

        return nearby_stops;
    }

    static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    @Override
    protected Void doInBackground(Void... params) {
        PebbleService.NearbyStops[] nearby_stops;
        while (!this.isCancelled())
        {
            //Looper.loop();
            condition.block();
            if (!isCancelled() && this.location != null) {
                //nearby_stops = get_nearby_stops(this.location);
                nearby_stops = get_nearby_stops_online(this.location);
                publishProgress(nearby_stops);
            }
            condition.close();
        }

        return null;
    }

    @Override
    protected void onProgressUpdate(PebbleService.NearbyStops[]... progress) {
        //setProgressPercent(progress[0]);
        service.store_nearby_stops(progress[0]);
    }

    @Override
    protected void onPostExecute(Void param) {
        google_api_client.disconnect();
    }

    @Override
    protected void onCancelled(Void param) {
        google_api_client.disconnect();
    }

    @Override
    public void onConnected(Bundle bundle) {
        location_is_connected = true;
        this.location = LocationServices.FusedLocationApi.getLastLocation(google_api_client);
        LocationServices.FusedLocationApi.requestLocationUpdates(google_api_client, location_request, this);
        condition.open();
    }

    @Override
    public void onConnectionSuspended(int i) {
       condition.open();
    }

   /* @Override
    public void onDisconnected() {
        location_is_connected = false;

    }*/

    @Override
    public void onLocationChanged(Location location) {
        if ((int)location.distanceTo(this.location) <= DEVIATION)
            return;
        this.location = location;
        condition.open();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        condition.open();
    }

    private void insert_into_nearby_stops_online(PebbleService.NearbyStops[] nearby_stops, int distance, long id, String stop_name, String string_id, String line_numbers, String direction_name, String stop_color_type)
    {
        int place = 0;
        for(;place < PebbleService.NEARBY_STOPS_LENGTH && nearby_stops[place].distance < distance; place++)
            ;
        if (place == PebbleService.NEARBY_STOPS_LENGTH)
            return;

        for (int i = PebbleService.NEARBY_STOPS_LENGTH - 1; i > place; i--) {
            nearby_stops[i].distance = nearby_stops[i - 1].distance;
            nearby_stops[i].id = nearby_stops[i-1].id;
            nearby_stops[i].stop_name = nearby_stops[i-1].stop_name;
            nearby_stops[i].string_id = nearby_stops[i-1].string_id;
            nearby_stops[i].direction_name = nearby_stops[i-1].direction_name;
            nearby_stops[i].line_numbers = nearby_stops[i-1].line_numbers;
            nearby_stops[i].stop_color_type = nearby_stops[i-1].stop_color_type;
        }
        nearby_stops[place].distance = distance;
        nearby_stops[place].id = id;
        if (stop_name.length() > 31)
            stop_name = stop_name.substring(0,31);
        nearby_stops[place].stop_name = stop_name;
        nearby_stops[place].string_id = string_id;
        nearby_stops[place].direction_name = direction_name;
        nearby_stops[place].line_numbers = line_numbers;
        nearby_stops[place].stop_color_type = stop_color_type;
    }
}
