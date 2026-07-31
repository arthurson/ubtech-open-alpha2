package com.ubtech.alpha2demo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.ubtech.alpha2demo.R;
import com.ubtechinc.alpha2ctrlapp.network.action.ClientAuthorizeListener;
import com.ubtechinc.alpha2robot.Alpha2RobotApi;
import com.ubtechinc.alpha2serverlib.interfaces.AlphaActionClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotClientListener;
import com.ubtechinc.developer.DeveloperAppStaticValue;

/**
 * [ActionDemo]
 * 
 * @author zengdengyi
 * @version 1.0
 * @date 2015��8��17�� ����3:01:28
 * 
 **/

public class ActionMainActivity extends Activity implements
		IAlpha2RobotClientListener, AlphaActionClientListener {
	private Alpha2RobotApi mRobot;
	private ExitBroadcast mExitBroadcast;

	private String key_word = "";
	private String mPackageName;

	private boolean isBusy;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		init();
	}

	/**
	 * init Alpha2RobotApi
	 * 
	 * @author zengdengyi
	 * @date 2015��10��21�� ����4:28:35
	 */
	public void init() {
		mPackageName = this.getPackageName();
		mRobot = new Alpha2RobotApi(this, "7859E53359337349CC9390667F1B6B7B",
				new ClientAuthorizeListener() {

					@Override
					public void onResult(int code, String info) {
						// TODO Auto-generated method stub
						Log.i("zdy", "code = " + code + " info= " + info);
						if (code == 1) {
							mRobot.initActionApi(ActionMainActivity.this);
						}
					}
				});

	}

	public class ExitBroadcast extends BroadcastReceiver {
		@Override
		public void onReceive(Context arg0, Intent intent) {
			// TODO Auto-generated method stub
			if (intent.getAction().equals(DeveloperAppStaticValue.APP_EXIT)) {
				Log.i("zdy", "speech_stopRecognized ");
				mRobot.releaseApi();
				mRobot = null;
				System.exit(0);
			} else if (intent.getAction().equals(mPackageName)) {

			}
		}
	}

	@Override
	public void onServerCallBack(String text) {
		// TODO Auto-generated method stub
	}

	@Override
	public void onServerPlayEnd(boolean isEnd) {

		Log.d("zdy", "onServerPlayEnd");

	}

	@Override
	public void onActionStop(String strActionFileName) {
		// TODO Auto-generated method stub

	}

	@Override
	protected void onDestroy() {
		// TODO Auto-generated method stub
		super.onDestroy();
		if (mExitBroadcast != null) {
			this.unregisterReceiver(mExitBroadcast);
			mExitBroadcast = null;
		}
		if (mRobot != null) {
			// mRobot.speech_stopRecognized();
		}
	}

	/**
	 * @author zengdengyi
	 * @param view
	 * @date 2015��10��21�� ����4:30:55 robot play actionFile
	 */
	public void onTest(View view) {

		mRobot.action_PlayActionName("Turn right");
	}

}
