package dexter.com.bptransport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.ConnectIQAdbStrategy;
import com.garmin.android.connectiq.IQDevice;

import java.util.HashMap;

/**
 * Created by dexter on 2017. 12. 01..
 */

class CWrapper {
    private static class ConnectIQWrappedReceiver extends BroadcastReceiver {
        private final BroadcastReceiver receiver;

        ConnectIQWrappedReceiver(BroadcastReceiver receiver) {
            this.receiver = receiver;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.garmin.android.connectiq.SEND_MESSAGE_STATUS".equals(intent.getAction())) {
                replaceIQDeviceById(intent, "com.garmin.android.connectiq.EXTRA_REMOTE_DEVICE");
            } else if ("com.garmin.android.connectiq.OPEN_APPLICATION".equals(intent.getAction())) {
                replaceIQDeviceById(intent, "com.garmin.android.connectiq.EXTRA_OPEN_APPLICATION_DEVICE");
            }
            receiver.onReceive(context, intent);
        }
    }

    ;

    private static void replaceIQDeviceById(Intent intent, String extraName) {
        try {
            IQDevice device = intent.getParcelableExtra(extraName);
            if (device != null) {
                intent.putExtra(extraName, device.getDeviceIdentifier());
            }
        } catch (ClassCastException e) {
// It's already a long, i.e. on the simulator.
        }
    }

    public static void initializeConnectIQ(
            Context context, ConnectIQ connectIQ, boolean autoUI, ConnectIQ.ConnectIQListener listener) {
        if (connectIQ instanceof ConnectIQAdbStrategy) {
            connectIQ.initialize(context, autoUI, listener);
            return;
        }
        Context wrappedContext = new ContextWrapper(context) {
            private HashMap<BroadcastReceiver, BroadcastReceiver> receiverToWrapper = new HashMap<BroadcastReceiver, BroadcastReceiver>();

            @Override
            public Intent registerReceiver(final BroadcastReceiver receiver, IntentFilter filter) {
                BroadcastReceiver wrappedRecv = new ConnectIQWrappedReceiver(receiver);
                synchronized (receiverToWrapper) {
                    receiverToWrapper.put(receiver, wrappedRecv);
                }
                return super.registerReceiver(wrappedRecv, filter);
            }

            @Override
            public void unregisterReceiver(BroadcastReceiver receiver) {
// We need to unregister the wrapped receiver.
                BroadcastReceiver wrappedReceiver = null;
                synchronized (receiverToWrapper) {
                    wrappedReceiver = receiverToWrapper.get(receiver);
                    receiverToWrapper.remove(receiver);
                }
                if (wrappedReceiver != null) super.unregisterReceiver(wrappedReceiver);
            }
        };
        connectIQ.initialize(wrappedContext, autoUI, listener);
    }
}