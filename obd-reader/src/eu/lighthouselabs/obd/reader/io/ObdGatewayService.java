/*
 * TODO put header
 */
package eu.lighthouselabs.obd.reader.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Binder;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;
import eu.lighthouselabs.obd.commands.ObdCommand;
import eu.lighthouselabs.obd.commands.protocol.EchoOffObdCommand;
import eu.lighthouselabs.obd.commands.protocol.LineFeedOffObdCommand;
import eu.lighthouselabs.obd.commands.protocol.ObdResetCommand;
import eu.lighthouselabs.obd.commands.protocol.SelectProtocolObdCommand;
import eu.lighthouselabs.obd.commands.protocol.TimeoutObdCommand;
import eu.lighthouselabs.obd.enums.ObdProtocols;
import eu.lighthouselabs.obd.reader.IPostListener;
import eu.lighthouselabs.obd.reader.IPostMonitor;
import eu.lighthouselabs.obd.reader.R;
import eu.lighthouselabs.obd.reader.activity.ConfigActivity;
import eu.lighthouselabs.obd.reader.activity.MainActivity;
import eu.lighthouselabs.obd.reader.io.ObdCommandJob.ObdCommandJobState;

/**
 * This service is primarily responsible for establishing and maintaining a
 * permanent connection between the device where the application runs and a more
 * OBD Bluetooth interface.
 * 
 * Secondarily, it will serve as a repository of ObdCommandJobs and at the same
 * time the application state-machine.
 */
public class ObdGatewayService extends Service {

	private static String TAG = "ObdGatewayService";

	private IPostListener _callback = null;
	private final Binder _binder = new LocalBinder();
	private boolean _isRunning = false;
	private NotificationManager _notifManager;

	private BlockingQueue<ObdCommandJob> _queue = new LinkedBlockingQueue<ObdCommandJob>();
	private Long _queueCounter = 0L;

	private BluetoothDevice _dev = null;
	private BluetoothSocket _sock = null;
	/*
	 * http://developer.android.com/reference/android/bluetooth/BluetoothDevice.html
	 * #createRfcommSocketToServiceRecord(java.util.UUID)
	 * 
	 * "Hint: If you are connecting to a Bluetooth serial board then try using
	 * the well-known SPP UUID 00001101-0000-1000-8000-00805F9B34FB. However if
	 * you are connecting to an Android peer then please generate your own
	 * unique UUID."
	 */
	private static final UUID MY_UUID = UUID
			.fromString("00001101-0000-1000-8000-00805F9B34FB");

	/*
	 * This will be used to stop queue processing.
	 */
	private boolean _run = true;

	/**
	 * Because this is a local service, we won't be binding to it, but since
	 * Service requires an implementation of the onBind() method, we provide one
	 * that simply returns null.
	 */
	@Override
	public IBinder onBind(Intent intent) {
		return _binder;
	}

	@Override
	public void onCreate() {
		_notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		showNotification();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "Received start id " + startId + ": " + intent);

		/*
		 * TODO
		 * 
		 * Register listener Start OBD connection
		 */
		startService();

		/*
		 * We want this service to continue running until it is explicitly
		 * stopped, so return sticky.
		 */
		return START_STICKY;
	}

	private void startService() {
		/*
		 * Retrieve preferences
		 */
		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		/*
		 * Let's get the remote Bluetooth device TODO clean this
		 */
		String remoteDevice = prefs.getString(
				ConfigActivity.BLUETOOTH_LIST_KEY, null);
		if (remoteDevice == null || "".equals(remoteDevice)) {
			Toast.makeText(this, "No Bluetooth device selected",
					Toast.LENGTH_LONG).show();

			stopSelf();
		}

		final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
		BluetoothDevice dev = btAdapter.getRemoteDevice(remoteDevice);

		/*
		 * TODO put this as deprecated Determine if upload is enabled
		 */
		// boolean uploadEnabled = prefs.getBoolean(
		// ConfigActivity.UPLOAD_DATA_KEY, false);
		// String uploadUrl = null;
		// if (uploadEnabled) {
		// uploadUrl = prefs.getString(ConfigActivity.UPLOAD_URL_KEY,
		// null);
		// }

		/*
		 * Get GPS
		 */
		LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		boolean gps = prefs.getBoolean(ConfigActivity.ENABLE_GPS_KEY, false);
		/*
		 * Get more preferences
		 */
		int period = ConfigActivity.getUpdatePeriod(prefs);
		double ve = ConfigActivity.getVolumetricEfficieny(prefs);
		double ed = ConfigActivity.getEngineDisplacement(prefs);
		boolean imperialUnits = prefs.getBoolean(
				ConfigActivity.IMPERIAL_UNITS_KEY, false);
		ArrayList<ObdCommand> cmds = ConfigActivity.getObdCommands(prefs);

		/*
		 * Establish Bluetooth connection
		 * 
		 * Because discovery is a heavyweight procedure for the Bluetooth
		 * adapter, this method should always be called before attempting to
		 * connect to a remote device with connect(). Discovery is not managed
		 * by the Activity, but is run as a system service, so an application
		 * should always call cancel discovery even if it did not directly
		 * request a discovery, just to be sure. If Bluetooth state is not
		 * STATE_ON, this API will return false.
		 * 
		 * see
		 * http://developer.android.com/reference/android/bluetooth/BluetoothAdapter
		 * .html#cancelDiscovery()
		 */
		btAdapter.cancelDiscovery();

		try {
			startObdConnection();
		} catch (Exception e) {
			Log.e(TAG, "Can't connect to remote device. -> " + e.getMessage());
			Toast.makeText(this, "Can't connect to remote device.",
					Toast.LENGTH_SHORT);
			try {
				stopService();
			} catch (IOException e2) {
				Log.e(TAG,
						"Can't connect to remote device. -> " + e2.getMessage());
			}
		}

	}

	/**
	 * Start and configure the connection to the OBD interface.
	 * 
	 * @throws IOException
	 */
	private void startObdConnection() throws IOException {
		// Instantiate a BluetoothSocket for the remote device and connect it.
		_sock = _dev.createRfcommSocketToServiceRecord(MY_UUID);
		_sock.connect();

		// Let's configure the connection.
		queueJob(new ObdCommandJob(new ObdResetCommand()));
		queueJob(new ObdCommandJob(new EchoOffObdCommand()));

		/*
		 * Will send second-time based on tests.
		 * 
		 * TODO this can be done w/o having to queue jobs by just issuing
		 * command.run(), command.getResult() and validate the result.
		 */
		queueJob(new ObdCommandJob(new EchoOffObdCommand()));
		queueJob(new ObdCommandJob(new LineFeedOffObdCommand()));
		queueJob(new ObdCommandJob(new TimeoutObdCommand(62)));

		// For now set protocol to AUTO
		queueJob(new ObdCommandJob(new SelectProtocolObdCommand(
				ObdProtocols.AUTO)));

		// Service is running..
		_isRunning = true;

		// Let's start queue execution
		_queueCounter = 0L;
		executeQueue();
	}

	/**
	 * Runs the queue until the service is stopped
	 */
	private void executeQueue() {
		while (_run) {
			while (!_queue.isEmpty()) {
				ObdCommandJob job = null;
				try {
					job = _queue.take();
					if (job.getState().equals(ObdCommandJobState.NEW)) {
						job.setState(ObdCommandJobState.RUNNING);
						job.getCommand().run(_sock.getInputStream(),
								_sock.getOutputStream());
					}
				} catch (Exception e) {
					job.setState(ObdCommandJobState.EXECUTION_ERROR);
					Log.e(TAG, "Failed to run command. -> " + e.getMessage());
				}

				if (job != null) {
					job.setState(ObdCommandJobState.FINISHED);
					_callback.stateUpdate(job);
				}
			}
		}
	}

	/**
	 * This method will add a job to the queue while setting its ID to the
	 * internal queue counter.
	 * 
	 * @param job
	 * @return
	 */
	public Long queueJob(ObdCommandJob job) {
		_queueCounter++;

		job.setId(_queueCounter);
		try {
			_queue.put(job);
		} catch (InterruptedException e) {
			job.setState(ObdCommandJobState.QUEUE_ERROR);
		}

		return _queueCounter;
	}

	/**
	 * Stop OBD connection and queue processing.
	 * 
	 * @throws IOException
	 */
	public void stopService() throws IOException {
		_run = false;
		_queue.removeAll(_queue); // is this safe?
		_sock.close();
		stopSelf();
	}

	/**
	 * Show a notification while this service is running.
	 */
	private void showNotification() {
		// Set the icon, scrolling text and timestamp
		Notification notification = new Notification(R.drawable.icon,
				getText(R.string.service_started), System.currentTimeMillis());

		// Launch our activity if the user selects this notification
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, MainActivity.class), 0);

		// Set the info for the views that show in the notification panel.
		notification.setLatestEventInfo(this,
				getText(R.string.notification_label),
				getText(R.string.service_started), contentIntent);

		// Send the notification.
		_notifManager.notify(R.string.service_started, notification);
	}

	/**
	 * TODO put description
	 */
	public class LocalBinder extends Binder implements IPostMonitor {
		public void setListener(IPostListener callback) {
			_callback = callback;
		}

		public boolean isRunning() {
			return _isRunning;
		}
	}

}