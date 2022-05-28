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

package no.studio.android.BLERecorder;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.LegendRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.livedata.state.ConnectionState;
import no.nordicsemi.android.ble.observer.ConnectionObserver;
import no.studio.android.BLERecorder.adapter.DiscoveredBluetoothDevice;
import no.studio.android.BLERecorder.databinding.PickleBleActivityBinding;
import no.studio.android.BLERecorder.viewmodels.BLEDataViewModel;
import no.studio.android.BLERecorder.profile.BLEManager;

public class BLEDevicesActivity extends AppCompatActivity {
	public static final String EXTRA_DEVICE = "cn.ergonomics.android.ble.EXTRA_DEVICE";

	private BLEDataViewModel viewModel;
	private PickleBleActivityBinding binding;
	private LineGraphSeries<DataPoint> mSeriesX,mSeriesY,mSeriesZ;
	private double graphLastXValue = 5d;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		binding = PickleBleActivityBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());

		final Intent intent = getIntent();
		final DiscoveredBluetoothDevice device = intent.getParcelableExtra(EXTRA_DEVICE);
		final String deviceName = device.getName();
		final String deviceAddress = device.getAddress();

		final MaterialToolbar toolbar = binding.toolbar;
		toolbar.setTitle(deviceName != null ? deviceName : getString(R.string.unknown_device));
		toolbar.setSubtitle(deviceAddress);
		setSupportActionBar(toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		// GraphView Initialize
		setGraphView();

		// Configure the view model.
		viewModel = new ViewModelProvider(this).get(BLEDataViewModel.class);
		viewModel.connect(device);

		// Set up views.
		binding.recordSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> viewModel.setRecordState(isChecked));
		binding.infoNotSupported.actionRetry.setOnClickListener(v -> viewModel.reconnect());
		binding.infoTimeout.actionRetry.setOnClickListener(v -> viewModel.reconnect());
		binding.btnSave.setOnClickListener(v -> {
			viewModel.setRecordState(false);
			binding.recordSwitch.setChecked(false);
			saveRecordData();
		});
		binding.btnClear.setOnClickListener(v ->{
			viewModel.setRecordState(false);
			binding.recordSwitch.setChecked(false);
			clearRecordData(BLEManager.fileRecord);
		});
		viewModel.getConnectionState().observe(this, state -> {
			switch (state.getState()) {
				case CONNECTING:
					binding.progressContainer.setVisibility(View.VISIBLE);
					binding.infoNotSupported.container.setVisibility(View.GONE);
					binding.infoTimeout.container.setVisibility(View.GONE);
					binding.connectionState.setText(R.string.state_connecting);
					break;
				case INITIALIZING:
					binding.connectionState.setText(R.string.state_initializing);
					break;
				case READY:
					binding.progressContainer.setVisibility(View.GONE);
					binding.deviceContainer.setVisibility(View.VISIBLE);
					onConnectionStateChanged(true);
					break;
				case DISCONNECTED:
					if (state instanceof ConnectionState.Disconnected) {
						binding.deviceContainer.setVisibility(View.GONE);
						binding.progressContainer.setVisibility(View.GONE);
						final ConnectionState.Disconnected stateWithReason = (ConnectionState.Disconnected) state;
						if (stateWithReason.getReason() == ConnectionObserver.REASON_NOT_SUPPORTED) {
							binding.infoNotSupported.container.setVisibility(View.VISIBLE);
						} else {
							binding.infoTimeout.container.setVisibility(View.VISIBLE);
						}
					}
					// fallthrough
				case DISCONNECTING:
					onConnectionStateChanged(false);
					break;
			}
		});
		viewModel.getAccelData().observe(this,accelData ->{
			double[] accel = new double[3];
			accel[0] = accelData.getIntValue(Data.FORMAT_SINT16_LE,4)/1000.0f;
			accel[1] = accelData.getIntValue(Data.FORMAT_SINT16_LE,6)/1000.0f;
			accel[2] = accelData.getIntValue(Data.FORMAT_SINT16_LE,8)/1000.0f;
			binding.tvDataAccelX.setText(String.format("%.3f",accel[0]));
			binding.tvDataAccelY.setText(String.format("%.3f",accel[1]));
			binding.tvDataAccelZ.setText(String.format("%.3f",accel[2]));
			binding.tvDataHex.setText(toString(accelData));
			binding.tvDataTimestamp.setText(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(System.currentTimeMillis())));
			realtimeGraph(accel);
		});

	}

	private void onConnectionStateChanged(final boolean connected) {
		viewModel.setRecordState(false);
		binding.recordSwitch.setEnabled(connected);
		if (!connected) {
			binding.recordSwitch.setChecked(false);
		}
	}

	private String toString(Data data) {
		final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
		byte[] mValue = data.getValue();
		if (data.size() == 0)
			return "";

		final char[] out = new char[(mValue != null ? mValue.length : 0) * 3 - 1];
		for (int j = 0; j < mValue.length; j++) {
			int v = mValue[j] & 0xFF;
			out[j * 3] = HEX_ARRAY[v >>> 4];
			out[j * 3 + 1] = HEX_ARRAY[v & 0x0F];
			if (j != mValue.length - 1)
				out[j * 3 + 2] = ' ';
		}
		return new String(out);
	}

	private void saveRecordData(){
		if((BLEManager.mFileName == null) || (!BLEManager.fileRecord.exists())){
			Toast.makeText(this, "Target file not exist", Toast.LENGTH_SHORT).show();
			return;
		}
		Intent share = new Intent(Intent.ACTION_SEND);
		share.setType("application/vnd.ms-excel");
		Uri contentUri = getFileProvider(this, BLEManager.fileRecord);
		share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		share.putExtra(Intent.EXTRA_STREAM,contentUri);
		share.putExtra(Intent.EXTRA_SUBJECT, BLEManager.mFileName);
		share.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		this.startActivity(Intent.createChooser(share, "title"));
		Toast.makeText(this, "Save \""+ BLEManager.mFileName+"\" Success", Toast.LENGTH_SHORT).show();
	}

	private Uri getFileProvider(Context context, File file){
		String authority = context.getPackageName()+".fileProvider";
		return FileProvider.getUriForFile(context,authority,file);
	}

	private void clearRecordData(File delFile) {

		if ((delFile == null) || (!delFile.exists())) {
			Toast.makeText(getApplicationContext(), "Target file not exist", Toast.LENGTH_SHORT).show();
		} else {
			if (delFile.exists() && delFile.isFile()) {
				if (delFile.delete()) {
					Log.e("--Method--", "Copy_Delete.deleteSingleFile: Delete single file" + delFile + "success");
					Toast.makeText(getApplicationContext(), "Clear \""+ BLEManager.mFileName+"\" Success", Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(getApplicationContext(), "Clear \""+ BLEManager.mFileName+"\" Failed", Toast.LENGTH_SHORT).show();
				}
			} else {
				Toast.makeText(getApplicationContext(), "Target file not exist", Toast.LENGTH_SHORT).show();
			}
		}
	}
	/** GraphView Init */
	private void setGraphView(){

		GraphView graphView = findViewById(R.id.rt_graph);
		mSeriesX = new LineGraphSeries<>();
		mSeriesX.setColor(Color.RED);
		mSeriesX.setTitle("X-accel");
		graphView.addSeries(mSeriesX);

		mSeriesY = new LineGraphSeries<>();
		mSeriesY.setColor(Color.GREEN);
		mSeriesY.setTitle("Y-accel");
		graphView.addSeries(mSeriesY);

		mSeriesZ = new LineGraphSeries<>();
		mSeriesZ.setColor(Color.BLUE);
		mSeriesZ.setTitle("Z-accel");
		graphView.addSeries(mSeriesZ);

		graphView.getViewport().setXAxisBoundsManual(true);
		graphView.getViewport().setMinX(0);
		graphView.getViewport().setMaxX(400);

		graphView.getLegendRenderer().setVisible(true);
		graphView.getLegendRenderer().setAlign(LegendRenderer.LegendAlign.TOP);
		graphView.getGridLabelRenderer().setLabelVerticalWidth(80);
		// enable scaling
		graphView.getViewport().setScalable(true);
	}

	private void realtimeGraph(double[] accelData){
		graphLastXValue += 1d;
		mSeriesX.appendData(new DataPoint(graphLastXValue,accelData[0]),true,5000);
		mSeriesY.appendData(new DataPoint(graphLastXValue,accelData[1]),true,5000);
		mSeriesZ.appendData(new DataPoint(graphLastXValue,accelData[2]),true,5000);
	}
}
