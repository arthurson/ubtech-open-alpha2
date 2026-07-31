package com.ubtech.util;

import android.os.Environment;
import android.util.Log;

import com.ubtech.util.TimeUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class StateLog {

	private static String TAG = "StateLog";

	public static void Log(String log) {
		
		TimeUtils time = new TimeUtils();
		String text = time.getNowTime(TimeUtils.YYYYMMDD_HHMMSS)+" "+log+"\n";
		File file = new File(Environment.getExternalStorageDirectory(),
				"alpha2demo/StateLog");
		if (!file.exists()) {
			file.mkdirs();
		}

		File logFile = new File(file.getPath() + File.separator
				+ "statelog.txt");
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(logFile, true);
			fos.write(text.getBytes());
			fos.flush();
			fos.close();
		} catch (FileNotFoundException e) {
			Log.d(TAG, "File not found: " + e.getMessage());
		} catch (IOException e) {
			Log.d(TAG, "Error accessing file: " + e.getMessage());
		} finally {
			if (fos != null) {
				try {
					fos.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

	}
}
