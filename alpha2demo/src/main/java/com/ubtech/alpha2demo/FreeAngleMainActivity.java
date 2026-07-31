package com.ubtech.alpha2demo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
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

public class FreeAngleMainActivity extends Activity implements
		IAlpha2RobotClientListener, AlphaActionClientListener {
	private Alpha2RobotApi mRobot;
	private ExitBroadcast mExitBroadcast;

	private String key_word = "";
	private String mPackageName;

	private boolean isOneAngle=true;

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
							mRobot.initChestSeiralApi();
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
		testOneAngle();
	}

	public void testOneAngle() {
		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				// TODO Auto-generated method stub

				if (isOneAngle) {
					int i = 120;
					boolean isRight = true;
					while (true) {
						short time = 16;
						if (isRight) {
							if (i < 165) {
								i = i + 1;
								mRobot.chest_SendOneFreeAngle((byte) 19, i,
										time);
							} else {
								isRight = false;
							}
						} else {
							if (i < 75) {
								isRight = true;

							} else {
								i = i - 1;
								mRobot.chest_SendOneFreeAngle((byte) 19, i,
										time);
							}
						}

						try {
							Thread.sleep(20);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				} else {
					int[] data = new int[20];
					for (int i = 0; i < 20; i++) {
						if (i == 18) {// ͷ��19��
							data[i] = 1;// 1-249 ����״̬ ��120��
						} else if (i == 19) {// ͷ��20�� ����״̬ ��120��
							data[i] = 250;// //���ֶ������
						} else {// �������
							data[i] = 250;// ���ֶ������
						}
					}
					short time = 20 * 50;
					mRobot.head_SendFreeAngle(data, time);
				}
			}
		}, 500);
	}
}
