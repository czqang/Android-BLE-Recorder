/*
 * Copyright (c) 2018, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.studio.android.BLERecorder.profile;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.livedata.ObservableBleManager;
import no.studio.android.BLERecorder.BuildConfig;
import no.studio.android.BLERecorder.profile.callback.BLEAccelDataCallback;
import no.studio.android.BLERecorder.profile.callback.BLEBatteryDataCallback;
import no.nordicsemi.android.log.LogContract;
import no.nordicsemi.android.log.LogSession;
import no.nordicsemi.android.log.Logger;

public class BLEManager extends ObservableBleManager {
	/** BLE Advertisement Service UUID*/
	public final static UUID LBS_UUID_ADV_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
	/** BLE Service UUID. */
	public final static UUID LBS_UUID_SERVICE = UUID.fromString("f000fff0-0451-4000-b000-000000000000");
	/** Acceleration characteristic UUID. */
	private final static UUID LBS_UUID_ACCEL_CHAR = UUID.fromString("f000fff1-0451-4000-b000-000000000000");
	/** Battery characteristic UUID. */
	private final static UUID LBS_UUID_BATTERY_CHAR = UUID.fromString("f000fff2-0451-4000-b000-000000000000");

	private final MutableLiveData<Data> accelData = new MutableLiveData<>();
	private final MutableLiveData<Byte> batteryState = new MutableLiveData<>();

	private BluetoothGattCharacteristic accelCharacteristic, batteryCharacteristic;
	private LogSession logSession;
	private boolean supported;
	private boolean recordSwitchOn;
	final Context context;


	public BLEManager(@NonNull final Context context) {
		super(context);
		this.context = context;
	}

	public final LiveData<Data> getAccelData(){ return accelData;}
	public final LiveData<Byte> getBatteryState(){ return batteryState;}

	public static String mFileName = null;
	public static File dirRecord = null;
	public static File fileRecord = null;
	private FileOutputStream fileOutputStream = null;

	@NonNull
	@Override
	protected BleManagerGattCallback getGattCallback() {
		return new BLEManagerGattCallback();
	}

	/**
	 * Sets the log session to be used for low level logging.
	 * @param session the session, or null, if nRF Logger is not installed.
	 */
	public void setLogger(@Nullable final LogSession session) {
		logSession = session;
	}

	@Override
	public void log(final int priority, @NonNull final String message) {
		if (BuildConfig.DEBUG) {
			Log.println(priority, "BLEManager", message);
		}
		// The priority is a Log.X constant, while the Logger accepts it's log levels.
		Logger.log(logSession, LogContract.Log.Level.fromPriority(priority), message);
	}

	@Override
	protected boolean shouldClearCacheWhenDisconnected() {
		return !supported;
	}

	/**
	 * The Acceleration callback will be notified when a notification from Button characteristic
	 * has been received, or its data was read.
	 * <p>
	 * If the data received are valid (single byte equal to 0x00 or 0x01), the
	 * {@link BLEAccelDataCallback#onAccelDataChanged} will be called.
	 * Otherwise, the {@link BLEAccelDataCallback#onInvalidDataReceived(BluetoothDevice, Data)}
	 * will be called with the data received.
	 */
	private	final BLEAccelDataCallback accelCallback = new BLEAccelDataCallback() {
		@SuppressLint({"WrongConstant", "DefaultLocale"})
		@Override
		public void onAccelDataChanged(@NonNull final Data accel) {
			float mAccX,mAccY,mAccZ;
			log(LogContract.Log.Level.APPLICATION, "Accelerate " + accel.toString());
			// The BLEManager is initialized with a default Handler, which will use
			// UI thread for the callbacks. setValue can be called safely.
			// If you're using a different handler, or coroutines, use postValue(..) instead.
			if(recordSwitchOn) {
				mAccX = accel.getIntValue(Data.FORMAT_SINT16_LE, 4) / 1000.0f;
				mAccY = accel.getIntValue(Data.FORMAT_SINT16_LE, 6) / 1000.0f;
				mAccZ = accel.getIntValue(Data.FORMAT_SINT16_LE, 8) / 1000.0f;
//				log(Log.WARN, "X:" + mAccX.shortValue() + "   Y:" + mAccY.shortValue() + "   Z:" + mAccZ.shortValue());
				accelData.setValue(accel);
				if (fileRecord.exists()) {
					try {
						fileOutputStream = new FileOutputStream(fileRecord,true);
						fileOutputStream.write((String.format("%.3f,", mAccX) + String.format("%.3f,", mAccY) + String.format("%.3f,", mAccZ) + "\r\n").getBytes(StandardCharsets.UTF_8));
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						if (fileOutputStream != null) {
							try {
								fileOutputStream.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
						}
					}
				}
			}
		}

		@Override
		public void onInvalidDataReceived(@NonNull final BluetoothDevice device,
										  @NonNull final Data data) {
			log(Log.WARN, "Accel invalid data received: " + data);
		}
	};

	/**
	 * The Battery callback will be notified when the Battery state was read or sent to the target device.
	 * <p>
	 * This callback implements both {@link no.nordicsemi.android.ble.callback.DataReceivedCallback}
	 * and {@link no.nordicsemi.android.ble.callback.DataSentCallback} and calls the same
	 * method on success.
	 * <p>
	 * If the data received were invalid, the
	 * {@link BLEBatteryDataCallback#onInvalidDataReceived(BluetoothDevice, Data)} will be
	 * called.
	 */
	private final BLEBatteryDataCallback batteryCallback = new BLEBatteryDataCallback() {
		@SuppressLint("WrongConstant")
		@Override
		public void onBatteryStateChanged(@NonNull final BluetoothDevice device,
										  final boolean on) {
			log(LogContract.Log.Level.APPLICATION, "LED " + (on ? "ON" : "OFF"));
			// The BLEManager is initialized with a default Handler, which will use
			// UI thread for the callbacks. setValue can be called safely.
			// If you're using a different handler, or coroutines, use postValue(..) instead.
		}

		@Override
		public void onInvalidDataReceived(@NonNull final BluetoothDevice device,
										  @NonNull final Data data) {
			// Data can only invalid if we read them. We assume the app always sends correct data.
			log(Log.WARN, "Battery invalid data received: " + data);
		}
	};

	/**
	 * BluetoothGatt callbacks object.
	 */
	private class BLEManagerGattCallback extends BleManagerGattCallback {
		@Override
		protected void initialize() {
			setNotificationCallback(accelCharacteristic).with(accelCallback);
			readCharacteristic(batteryCharacteristic).with(batteryCallback).enqueue();
//			readCharacteristic(accelCharacteristic).with(accelCallback).enqueue();
			enableNotifications(accelCharacteristic).enqueue();
		}

		@Override
		public boolean isRequiredServiceSupported(@NonNull final BluetoothGatt gatt) {
			final BluetoothGattService service = gatt.getService(LBS_UUID_SERVICE);
			if (service != null) {
				accelCharacteristic = service.getCharacteristic(LBS_UUID_ACCEL_CHAR);
				batteryCharacteristic = service.getCharacteristic(LBS_UUID_BATTERY_CHAR);
			}

			boolean writeRequest = false;
			if (batteryCharacteristic != null) {
				final int batteryProperties = batteryCharacteristic.getProperties();
				writeRequest = (batteryProperties & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0;
			}

			supported = accelCharacteristic != null && batteryCharacteristic != null && writeRequest;
			return supported;
		}

		@Override
		protected void onServicesInvalidated() {
			accelCharacteristic = null;
			batteryCharacteristic = null;
		}
	}

	/**
	 * Sends a request to the device to turn the LED on or off.
	 *
	 * @param on true to turn the LED on, false to turn it off.
	 */
	@SuppressLint("SimpleDateFormat")
	public void turnRecordSwitch(final boolean on) {
		// Are we connected?
		if (accelCharacteristic == null)
			return;

		// No need to change?
		if (recordSwitchOn == on)
			return;
		else {
			recordSwitchOn = on;
		}

		if(recordSwitchOn){

			mFileName = (new SimpleDateFormat("yyyyMMdd_HH.mm.ss").format(new Date(System.currentTimeMillis())))+".csv";
			dirRecord = context.getExternalFilesDir("RecordBLE");
			if (!dirRecord.exists()) { dirRecord.mkdirs(); }
			fileRecord = new File(dirRecord, mFileName);
			if(!fileRecord.exists()) {
				try {
					fileRecord.createNewFile();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			try {
				FileOutputStream fileOutputStream = new FileOutputStream(fileRecord);
				fileOutputStream.write("X Accel,Y Accel,Z Accel,\r\n".getBytes(StandardCharsets.UTF_8));
				log(Log.WARN, "fileRecord write success. " + fileRecord);
			} catch (IOException e) {
				e.printStackTrace();
			}finally {
				if(fileOutputStream!=null) {
					try {
						fileOutputStream.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}

			if(dirRecord.exists())
				log(Log.WARN, "dirRecord create success. " + dirRecord);
			else log(Log.WARN, "dirRecord create failed. " + dirRecord);
			if(fileRecord.exists())
				log(Log.WARN, "fileRecord create success. " + fileRecord);
			else log(Log.WARN, "fileRecord create failed. " + fileRecord);
		}
	}
}
