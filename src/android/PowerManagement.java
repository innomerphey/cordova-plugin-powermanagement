/*
 * Copyright 2013-2014 Wolfgang Koller
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Cordova (Android) plugin for accessing the power-management functions of the device
 * @author Wolfgang Koller <viras@users.sourceforge.net>
 */
package org.apache.cordova.powermanagement;

import org.json.JSONArray;
import org.json.JSONException;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;

import android.util.Log;

import org.apache.cordova.CordovaWebView;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;

/**
 * Plugin class which does the actual handling
 */
public class PowerManagement extends CordovaPlugin {
	// As we only allow one wake-lock, we keep a reference to it here
	private PowerManager.WakeLock wakeLock = null;
	private PowerManager powerManager = null;
	private boolean releaseOnPause = true;

	private Handler handler;
	private CordovaWebView webView;
	private String wakeLockTag;
	private boolean heartbeatActive = false;

	/**
	 * Visibility keep-alive only. Does not use private ALARM_WAKEUP PendingIntents
	 * (rejected under modern target SDK / mutable-implicit PendingIntent rules).
	 */
	private final Runnable heartbeat = new Runnable() {
		public void run() {
			if (!heartbeatActive) {
				return;
			}
			try {
				final CordovaWebView wv = webView;
				if (wv != null && wv.getEngine() != null) {
					final View view = wv.getEngine().getView();
					if (view != null) {
						// Handler is bound to the main looper; View APIs require the UI thread.
						view.dispatchWindowVisibilityChanged(View.VISIBLE);
					}
				}
			} catch (Exception e) {
				Log.d("PowerManagementPlugin", "Heartbeat visibility dispatch failed: " + e.getMessage());
			} finally {
				if (heartbeatActive && handler != null) {
					handler.postDelayed(this, 10000);
				}
			}
		}
	};

	/**
	 * Fetch a reference to the power-service when the plugin is initialized
	 */
	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webViewPara) {
		super.initialize(cordova, webViewPara);

		this.webView = webViewPara;
		this.powerManager = (PowerManager) cordova.getActivity().getSystemService(Context.POWER_SERVICE);
		// Main looper so visibility dispatch and cancel/removeCallbacks are UI-thread safe.
		this.handler = new Handler(Looper.getMainLooper());
		this.wakeLockTag = cordova.getActivity().getPackageName() + ":PowerManagement";
	}

	private void startHeartbeat() {
		heartbeatActive = true;
		if (handler != null) {
			handler.removeCallbacks(heartbeat);
			handler.postDelayed(heartbeat, 10000);
		}
	}

	private void stopHeartbeat() {
		heartbeatActive = false;
		if (handler != null) {
			handler.removeCallbacks(heartbeat);
		}
	}

	@Override
	public boolean execute(String action, JSONArray args,
			CallbackContext callbackContext) throws JSONException {

		PluginResult result = null;
		Log.d("PowerManagementPlugin", "Plugin execute called - " + this.toString() );
		Log.d("PowerManagementPlugin", "Action is " + action );

		try {
			if( action.equals("acquire") ) {
				if( args.length() > 0 && args.getBoolean(0) ) {
					Log.d("PowerManagementPlugin", "Only dim lock" );
					result = this.acquire( PowerManager.SCREEN_DIM_WAKE_LOCK );
				} else if (args.length() > 1 && args.getBoolean(1) ) {
					Log.d("PowerManagementPlugin", "Partial wake lock" );
					result = this.acquire( PowerManager.PARTIAL_WAKE_LOCK );
				} else {
					Log.d("PowerManagementPlugin", "Full wake lock" );
					result = this.acquire( PowerManager.FULL_WAKE_LOCK );
				}
			} else if( action.equals("release") ) {
				result = this.release();
			} else if( action.equals("setReleaseOnPause") ) {
				try {
					this.releaseOnPause = args.getBoolean(0);
					result = new PluginResult(PluginResult.Status.OK);
				} catch (Exception e) {
					result = new PluginResult(PluginResult.Status.ERROR, "Could not set releaseOnPause");
				}
			}
		}
		catch( JSONException e ) {
			result = new PluginResult(Status.JSON_EXCEPTION, e.getMessage());
		}

		callbackContext.sendPluginResult(result);
		return true;
	}

	/**
	 * Acquire a wake-lock
	 * @param p_flags Type of wake-lock to acquire
	 * @return PluginResult containing the status of the acquire process
	 */
	private PluginResult acquire( int p_flags ) {
		PluginResult result = null;

		if (this.wakeLock == null) {
			this.wakeLock = this.powerManager.newWakeLock(p_flags, this.wakeLockTag);
			try {
				// No acquire timeout: SHD holds a partial lock across long sync sessions
				// and releases explicitly. A timeout without safe renewal would drop the lock.
				this.wakeLock.acquire();
				this.startHeartbeat();
				result = new PluginResult(PluginResult.Status.OK);
			}
			catch( Exception e ) {
				this.stopHeartbeat();
				this.wakeLock = null;
				result = new PluginResult(PluginResult.Status.ERROR,"Can't acquire wake-lock - check your permissions!");
			}
		}
		else {
			result = new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION,"WakeLock already active - release first");
		}

		return result;
	}

	/**
	 * Release an active wake-lock
	 * @return PluginResult containing the status of the release process
	 */
	private PluginResult release() {
		PluginResult result = null;

		this.stopHeartbeat();

		if( this.wakeLock != null ) {
			try {
				if (this.wakeLock.isHeld()) {
					this.wakeLock.release();
				}
				result = new PluginResult(PluginResult.Status.OK, "OK");
			}
			catch (Exception e) {
				result = new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION, "WakeLock already released");
			}

			this.wakeLock = null;
		}
		else {
			result = new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION, "No WakeLock active - acquire first");
		}

		return result;
	}

	/**
	 * Make sure any wakelock is released if the app goes into pause
	 */
	@Override
	public void onPause(boolean multitasking) {
		if( this.releaseOnPause && this.wakeLock != null ) {
			Log.d("PowerManagementPlugin", "Wake lock pause release" );
			this.stopHeartbeat();
			if (this.wakeLock.isHeld()) {
				this.wakeLock.release();
			}
		}

		super.onPause(multitasking);
	}

	/**
	 * Make sure any wakelock is acquired again once we resume
	 */
	@Override
	public void onResume(boolean multitasking) {
		if( this.releaseOnPause && this.wakeLock != null ) {
			Log.d("PowerManagementPlugin", "Wake lock resume acquire" );
			if (!this.wakeLock.isHeld()) {
				this.wakeLock.acquire();
			}
			this.startHeartbeat();
		}

		super.onResume(multitasking);
	}

	@Override
	public void onDestroy() {
		this.stopHeartbeat();
		if (this.wakeLock != null) {
			try {
				if (this.wakeLock.isHeld()) {
					this.wakeLock.release();
				}
			} catch (Exception e) {
				Log.d("PowerManagementPlugin", "WakeLock release on destroy failed: " + e.getMessage());
			}
			this.wakeLock = null;
		}
		super.onDestroy();
	}
}
