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
import android.os.AsyncTask;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Set;
import java.util.TimeZone;

/**
 * Created by DEXTER on 2014.11.09..
 */
public class AsyncNearbyStopsDetails extends AsyncTask<Object, Void, PebbleService.NearbyStopsDetails[]> {
    private PebbleService service;
    private Context local_context;
    private JSONObject json;

    public AsyncNearbyStopsDetails(PebbleService srv) {
        service = srv;
        local_context = service.getApplicationContext();
    }

    @Override
    protected PebbleService.NearbyStopsDetails[] doInBackground(Object... params) {

        final long id = (Long) params[0];
        final String string_id = (String) params[1];
        /*Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                get_json(string_id);
            }
        });
        thread.start();*/
        get_json(string_id);
        //PebbleService.NearbyStopsDetails[] nearby_stops_details = get_nearby_stops_details(id, (Integer) params[1]);
        /*try {
            thread.join();
        } catch (Exception e) {

        }*/
        PebbleService.NearbyStopsDetails[] nearby_stops_details = new PebbleService.NearbyStopsDetails[PebbleService.NEARBY_STOPS_DETAILS_LENGTH];
        for (int i = 0; i < PebbleService.NEARBY_STOPS_DETAILS_LENGTH;i++) {
            nearby_stops_details[i] = new PebbleService.NearbyStopsDetails();
            nearby_stops_details[i].line_number = "";
            nearby_stops_details[i].direction_name = "";
            nearby_stops_details[i].start_time = 99999;
            nearby_stops_details[i].predicted_start_time = 0;
        }
        combine_online_offline(nearby_stops_details);
        return nearby_stops_details;
    }

    @Override
    protected void onPostExecute(PebbleService.NearbyStopsDetails[] nearby_stops_details) {
        service.store_nearby_stops_details(nearby_stops_details);
    }

    @Override
    protected void onCancelled(PebbleService.NearbyStopsDetails[] nearby_stops_details) {

    }

    private void get_json(String string_id) {
        //PebbleService.Stops stops = PebbleService.stops_cache.get(id);
        //String string_id = "BKK_" + stops.string_id;
        try {

            URL url = new URL("http://futar.bkk.hu/bkk-utvonaltervezo-api/ws/otp/api/where/arrivals-and-departures-for-stop.json?includeReferences=routes,trips&stopId=" + string_id);
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
    }

    private void combine_online_offline(PebbleService.NearbyStopsDetails[] nearby_stops_details) {

        try {
            if (!json.getString("status").equals("OK"))
                return;

            Calendar current = Calendar.getInstance();

            JSONObject data = json.getJSONObject("data");
            JSONObject entry = data.getJSONObject("entry");
            JSONArray stop_times = entry.getJSONArray("stopTimes");
            for (int i = 0; i < stop_times.length(); i++) {  //TODO: SORT BY departuretime
                JSONObject row = stop_times.getJSONObject(i);
                int departure_time = row.getInt("departureTime");

                current.set(Calendar.HOUR_OF_DAY, (nearby_stops_details[i].start_time / 60) / 60);
                current.set(Calendar.MINUTE, (nearby_stops_details[i].start_time / 60) % 60);
                current.set(Calendar.SECOND, 0);

                if (departure_time == current.getTimeInMillis() / 1000) {
                    nearby_stops_details[i].predicted_start_time = get_start_time(row, "predictedDepartureTime");
                } else {
                    shift_nearby_stops_details(i, nearby_stops_details);

                    String trip_id = row.getString("tripId");
                    JSONObject references = data.getJSONObject("references");
                    JSONObject trips = references.getJSONObject("trips");
                    JSONObject trip = trips.getJSONObject(trip_id);
                    String route_id = trip.getString("routeId");
                    String predicted="";

                    try {
                        nearby_stops_details[i].predicted_start_time = get_start_time(row, "predictedDepartureTime");
                        predicted ="*";
                    } catch (Exception e) {}

                    nearby_stops_details[i].start_time = get_start_time(row, "departureTime");
                    JSONObject routes = references.getJSONObject("routes");
                    JSONObject route = routes.getJSONObject(route_id);

                    nearby_stops_details[i].direction_name = trim_headsign(predicted + trip.getString("tripHeadsign"));
                    nearby_stops_details[i].line_number = trim_line_number(route.getString("shortName"));

                }
            }

        } catch (Exception e) {

        }
    }

    private String trim_line_number(String line_number)
    {
        if (line_number.length() > 4)
            return new String(line_number.substring(0,4));

        return line_number;
    }

    private String trim_headsign(String headsign)
    {

        if (headsign.length() > (31 - 2))
            return new String(headsign.substring(0,31 - 2));

        return headsign;
    }

    private int get_start_time(JSONObject row, String time) throws JSONException {
        try {
            int predicted_dep_time = row.getInt(time);
            Calendar calendar = Calendar.getInstance();
            Long c = (long)predicted_dep_time * 1000;
            calendar.setTimeInMillis(c);
            return (calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)) * 60 + calendar.get(Calendar.SECOND);
        } catch (JSONException e){
           throw e;
        }
    };

    private void shift_nearby_stops_details(int i, PebbleService.NearbyStopsDetails[] nearby_stops_details)
    {
        if ( i > nearby_stops_details.length - 2)
            return;
       for (int j = nearby_stops_details.length - 2; j >= i; j--) {
           nearby_stops_details[j + 1].line_number = nearby_stops_details[j].line_number;
           nearby_stops_details[j + 1].direction_name = nearby_stops_details[j].direction_name;
           nearby_stops_details[j + 1].start_time = nearby_stops_details[j].start_time;
           nearby_stops_details[j + 1].stop_id = nearby_stops_details[j].stop_id;
           nearby_stops_details[j + 1].trip_id = nearby_stops_details[j].trip_id;
           nearby_stops_details[j+1].predicted_start_time = nearby_stops_details[j].predicted_start_time;
       }
    }

    static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

}
