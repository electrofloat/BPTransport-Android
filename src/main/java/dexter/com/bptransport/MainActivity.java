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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentActivity;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends FragmentActivity {
    private final static int CONNECTION_FAILURE_RESOLUTION_REQUEST = 9000;
    boolean bound = false;
    PebbleService service;

    public static class ErrorDialogFragment extends android.support.v4.app.DialogFragment {
        // Global field to contain the error dialog
        private Dialog mDialog;
        // Default constructor. Sets the dialog field to null
        public ErrorDialogFragment() {
            super();
            mDialog = null;
        }
        // Set the dialog to display
        public void setDialog(Dialog dialog) {
            mDialog = dialog;
        }
        // Return a Dialog to the DialogFragment.
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return mDialog;
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        // Decide what to do based on the original request code
        if (requestCode == CONNECTION_FAILURE_RESOLUTION_REQUEST && resultCode != Activity.RESULT_OK)
            finish();
    }

    private boolean is_play_services_available() {
        // Check that Google Play services is available
        int resultCode =
                GooglePlayServicesUtil.
                        isGooglePlayServicesAvailable(this);
        // If Google Play services is available
        if (ConnectionResult.SUCCESS == resultCode) {
            // In debug mode, log the status
            Log.d(getPackageName(),
                    "Google Play services is available.");
            // Continue
            return true;
            // Google Play services was not available for some reason.
            // resultCode holds the error code.
        } else {
            // Get the error dialog from Google Play services
            Dialog errorDialog = GooglePlayServicesUtil.getErrorDialog(
                    resultCode,
                    this,
                    CONNECTION_FAILURE_RESOLUTION_REQUEST);

            // If Google Play services can provide an error dialog
            if (errorDialog != null) {
                // Create a new DialogFragment for the error dialog
                ErrorDialogFragment errorFragment =
                        new ErrorDialogFragment();
                // Set the dialog in the DialogFragment
                errorFragment.setDialog(errorDialog);
                // Show the error dialog in the DialogFragment
                errorFragment.show(getSupportFragmentManager(), getPackageName());
            }
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (!is_play_services_available())
            finish();

        if (!is_service_running())
        {
            Intent bindIntent = new Intent(getApplicationContext(), PebbleService.class);
            startService(bindIntent);
        }


    }

    private boolean is_service_running()
    {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
        {
            if (PebbleService.class.getName().equals(service.service.getClassName()))
                return true;
        }
        return false;
    }

    public void notify_progressbar(int progress)
    {
        ProgressBar pb = (ProgressBar) findViewById(R.id.progressBar);
        pb.setProgress(progress);

        if (progress == 100) {
            Button button = (Button) findViewById(R.id.check_update_button);
            button.setEnabled(true);
        }
    }

    public void fill_textview(String string)
    {
        TextView tv = (TextView) findViewById(R.id.textView3);
        tv.setText("");
        tv.setText(string);
        //service.unsubscribe();
    }

    public void get_details_button_click(View view)
    {
        //service.subscribe(this);
        if (bound)
        {
            Log.d(getPackageName(), "start updates;");
            service.get_details();
        }
    }

    public void start_update_button_click(View view)
    {
        {
            service.start_updates();
        }
    }

    public void stop_update_button_click(View view)
    {
        if (bound)
        {
            service.stop_updates();
        }
        //service.unsubscribe();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class MyServiceConnection implements ServiceConnection {
        MainActivity activity;
        public MyServiceConnection(MainActivity main_activity)
        {
           activity = main_activity;
        }

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder iservice) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            PebbleService.LocalBinder binder = (PebbleService.LocalBinder) iservice;
            service = binder.getService();
            service.subscribe(activity);
            bound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    }
    private ServiceConnection connection = new MyServiceConnection(this);

    @Override
    protected void onStart() {
        super.onStart();

        Intent intent = new Intent(this, PebbleService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        long last_update_pref = prefs.getLong(getResources().getString(R.string.saved_last_update), 0);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss");
        String str1 = sdf.format(new Date(last_update_pref));
        TextView tv = (TextView) findViewById(R.id.textView2);
        tv.setText(str1);

        try {
            Process process = Runtime.getRuntime().exec("logcat -d AndroidRuntime:E *:S");
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder log=new StringBuilder();
            String line = "";
            while ((line = bufferedReader.readLine()) != null) {
                log.append(line);
            }
            TextView tv1 = (TextView)findViewById(R.id.textView3);
            tv1.setMovementMethod(new ScrollingMovementMethod());
            tv1.setText(log.toString());
        }
        catch (IOException e) {}


    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            service.unsubscribe();
            unbindService(connection);
            bound = false;
        }

    }
}
