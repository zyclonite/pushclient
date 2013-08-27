package net.zyclonite.pushclient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Date;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

public class PushService extends Service {
	public static final String TAG = "PushService";

	private static final String HOST = "srv.wsn.at";
	private static final int PORT = 80;
	private static final String PATH = "/pushrouter/stream";

	private static final String ACTION_START = "net.zyclonite.pushclient.START";
	private static final String ACTION_STOP = "net.zyclonite.pushclient.STOP";
	private static final String ACTION_KEEPALIVE = "net.zyclonite.pushclient.KEEP_ALIVE";
	private static final String ACTION_RECONNECT = "net.zyclonite.pushclient.RECONNECT";

	public static ServiceUpdateUIListener uiUpdateListener;

	private ConnectivityManager mConnMan;
	private NotificationManager mNotifMan;

	private boolean mStarted;
	private ConnectionThread mConnection;

	private static final long KEEP_ALIVE_INTERVAL = 1000 * 60 * 28;

	private static final long INITIAL_RETRY_INTERVAL = 1000 * 10;
	private static final long MAXIMUM_RETRY_INTERVAL = 1000 * 60 * 30;

	private SharedPreferences mPrefs;

	private static final int NOTIF_CONNECTED = 0;

	private static final String PREF_STARTED = "isStarted";

	public static void actionStart(Context ctx) {
		Intent i = new Intent(ctx, PushService.class);
		i.setAction(ACTION_START);
		ctx.startService(i);
	}

	public static void actionStop(Context ctx) {
		Intent i = new Intent(ctx, PushService.class);
		i.setAction(ACTION_STOP);
		ctx.startService(i);
	}

	public static void actionPing(Context ctx) {
		Intent i = new Intent(ctx, PushService.class);
		i.setAction(ACTION_KEEPALIVE);
		ctx.startService(i);
	}

	public static void setUpdateListener(ServiceUpdateUIListener listener) {
		uiUpdateListener = listener;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		mPrefs = getSharedPreferences(TAG, MODE_PRIVATE);

		mConnMan = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

		mNotifMan = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

		handleCrashedService();
	}

	private void handleCrashedService() {
		if (wasStarted() == true) {
			hideNotification();
			stopKeepAlives();
			start();
		}
	}

	@Override
	public void onDestroy() {
		log("Service destroyed (started=" + mStarted + ")");

		if (mStarted == true)
			stop();
	}

	private void log(String message) {
		if (uiUpdateListener != null) {
			uiUpdateListener.updateUI(message+"\n");
		}
		Log.d(TAG, message);
	}

	private boolean wasStarted() {
		return mPrefs.getBoolean(PREF_STARTED, false);
	}

	private void setStarted(boolean started) {
		mPrefs.edit().putBoolean(PREF_STARTED, started).commit();
		mStarted = started;
	}

	@Override
	public void onStart(Intent intent, int startId) {
		log("Service started with intent=" + intent);

		super.onStart(intent, startId);

		if (intent.getAction().equals(ACTION_STOP) == true) {
			stop();
			stopSelf();
		} else if (intent.getAction().equals(ACTION_START) == true)
			start();
		else if (intent.getAction().equals(ACTION_KEEPALIVE) == true)
			keepAlive();
		else if (intent.getAction().equals(ACTION_RECONNECT) == true)
			reconnectIfNecessary();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private synchronized void start() {
		if (mStarted == true) {
			Log.w(TAG, "Attempt to start connection that is already active");
			return;
		}

		setStarted(true);

		registerReceiver(mConnectivityChanged, new IntentFilter(
				ConnectivityManager.CONNECTIVITY_ACTION));

		log("Connecting...");

		mConnection = new ConnectionThread(HOST, PORT);
		mConnection.start();
	}

	private synchronized void stop() {
		if (mStarted == false) {
			Log.w(TAG, "Attempt to stop connection not active.");
			return;
		}

		setStarted(false);

		unregisterReceiver(mConnectivityChanged);
		cancelReconnect();

		if (mConnection != null) {
			mConnection.abort();
			mConnection = null;
		}
	}

	private synchronized void keepAlive() {
		try {
			if (mStarted == true && mConnection != null)
				mConnection.sendKeepAlive();
		} catch (IOException e) {
		}
	}

	private void startKeepAlives() {
		Intent i = new Intent();
		i.setClass(this, PushService.class);
		i.setAction(ACTION_KEEPALIVE);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
		alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP,
				System.currentTimeMillis() + KEEP_ALIVE_INTERVAL,
				KEEP_ALIVE_INTERVAL, pi);
	}

	private void stopKeepAlives() {
		Intent i = new Intent();
		i.setClass(this, PushService.class);
		i.setAction(ACTION_KEEPALIVE);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
		alarmMgr.cancel(pi);
	}

	public void scheduleReconnect(long startTime) {
		long interval = mPrefs.getLong("retryInterval", INITIAL_RETRY_INTERVAL);

		long now = System.currentTimeMillis();
		long elapsed = now - startTime;

		if (elapsed < interval)
			interval = Math.min(interval * 4, MAXIMUM_RETRY_INTERVAL);
		else
			interval = INITIAL_RETRY_INTERVAL;

		log("Rescheduling connection in " + interval + "ms.");

		mPrefs.edit().putLong("retryInterval", interval).commit();

		Intent i = new Intent();
		i.setClass(this, PushService.class);
		i.setAction(ACTION_RECONNECT);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
		alarmMgr.set(AlarmManager.RTC_WAKEUP, now + interval, pi);
	}

	public void cancelReconnect() {
		Intent i = new Intent();
		i.setClass(this, PushService.class);
		i.setAction(ACTION_RECONNECT);
		PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
		AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
		alarmMgr.cancel(pi);
	}

	private synchronized void reconnectIfNecessary() {
		if (mStarted == true && mConnection == null) {
			log("Reconnecting...");

			mConnection = new ConnectionThread(HOST, PORT);
			mConnection.start();
		}
	}

	private BroadcastReceiver mConnectivityChanged = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			NetworkInfo info = (NetworkInfo) intent
					.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);

			boolean hasConnectivity = (info != null && info.isConnected()) ? true
					: false;

			log("Connecting changed: connected=" + hasConnectivity);

			if (hasConnectivity)
				reconnectIfNecessary();
		}
	};

	private void showNotification() {
		Notification n = new Notification();

		n.flags = Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;

		n.icon = R.drawable.connected_notify;
		n.when = System.currentTimeMillis();

		PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this,
				PushActivity.class), 0);

		n.setLatestEventInfo(this, "PushClient connected", "Connected to "
				+ HOST + ":" + PORT, pi);

		mNotifMan.notify(NOTIF_CONNECTED, n);
	}

	private void hideNotification() {
		mNotifMan.cancel(NOTIF_CONNECTED);
	}

	private void announceHttpMessage(final String message) {
		Handler handler = new Handler(Looper.getMainLooper());
		handler.post(new Runnable() {

			public void run() {
			Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
			}
		});
		if (uiUpdateListener != null) {
			uiUpdateListener.updateUI(message+"\n");
		}
	}

	private class ConnectionThread extends Thread {
		private final Socket mSocket;
		private final String mHost;
		private final int mPort;

		private volatile boolean mAbort = false;

		public ConnectionThread(String host, int port) {
			mHost = host;
			mPort = port;
			mSocket = new Socket();
		}

		public boolean isConnected() {
			return mSocket.isConnected();
		}

		private boolean isNetworkAvailable() {
			NetworkInfo info = mConnMan.getActiveNetworkInfo();
			if (info == null)
				return false;

			return info.isConnected();
		}

		public void run() {
			Socket s = mSocket;
			String response = "";
			StringBuilder request = new StringBuilder();
			long startTime = System.currentTimeMillis();

			request.append("GET ");
			request.append(PATH);
			request.append("\n");
			try {
				s.connect(new InetSocketAddress(mHost, mPort), 20000);

				// s.setSoTimeout((int)KEEP_ALIVE_INTERVAL + 120000);

				log("Connection established to " + s.getInetAddress() + ":"
						+ mPort);

				startKeepAlives();
				showNotification();

				BufferedReader in = new BufferedReader(new InputStreamReader(
						s.getInputStream()), 1024);
				OutputStream out = s.getOutputStream();

				out.write(request.toString().getBytes());

				while ((response = in.readLine()) != null) {
					announceHttpMessage(response);
				}

				if (mAbort == false)
					log("Server closed connection unexpectedly.");
			} catch (IOException e) {
				log("Unexpected I/O error: " + e.toString());
			} finally {
				stopKeepAlives();
				hideNotification();

				if (mAbort == true)
					log("Connection aborted, shutting down.");
				else {
					try {
						s.close();
					} catch (IOException e) {
					}

					synchronized (PushService.this) {
						mConnection = null;
					}

					if (isNetworkAvailable() == true)
						scheduleReconnect(startTime);
				}
			}
		}

		public void sendKeepAlive() throws IOException {
			Socket s = mSocket;
			Date d = new Date();
			s.getOutputStream().write((d.toString() + "\n").getBytes());

			log("Keep-alive sent.");
		}

		public void abort() {
			log("Connection aborting.");

			mAbort = true;

			try {
				mSocket.shutdownOutput();
			} catch (IOException e) {
			}

			try {
				mSocket.shutdownInput();
			} catch (IOException e) {
			}

			try {
				mSocket.close();
			} catch (IOException e) {
			}

			while (true) {
				try {
					join();
					break;
				} catch (InterruptedException e) {
				}
			}
		}
	}
}
