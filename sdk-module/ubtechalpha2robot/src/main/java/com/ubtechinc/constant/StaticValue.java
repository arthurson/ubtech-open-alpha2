package com.ubtechinc.constant;

/**
 * Central table of the robot's wire values: broadcast action strings, intent extras and
 * serial command bytes for the chest and head microcontrollers.
 *
 * <p>Every value here is part of the contract with the robot's on-device software and
 * must stay byte-identical. Some identifier spellings are deliberately preserved because
 * they are the values the robot actually sends or the names the API has always exposed
 * (e.g. {@code ALPHA_GESTURE_ACTION = "come.ubt.alpha2.gesture"}, {@code CHES_*},
 * {@code HEADER_FALL_DERECTION}). Do not "fix" the values.
 */
public final class StaticValue {
   private StaticValue() {
   }

   // -- Ports / paths --------------------------------------------------------
   public static final int SERVER_PORT = 6100;
   public static final int MOTOR_SOCKET_PORT = 6102;
   public static String ACTION_PATH = "/mnt/shell/emulated/0/actions/";
   public static String PHOTO_PATH = "/mnt/shell/emulated/0/photos/";
   public static final short SOCKET_FLAG = 4660;
   public static final short SOCKET_VERSION = 1;

   // -- Broadcast action strings ---------------------------------------------
   public static final String CHEST_ACTION = "com.ubtechinc.services.chest";
   // 2026-08 新增: 官方 UBTech alpha2demo.apk (firmware 1.1.1.14) 反編譯確認 -
   // sonar 讀數唔係經 CHEST_ACTION 送 (嗰個淨係機身內部 raw command byte 嘅
   // debug log, 官方 demo 自己個 receiver 都淨係印 getmCmd() 冇用嚟顯示距離)。
   // 真正嘅 sonar 事件係經呢個獨立嘅 broadcast action 送出, extra 已經係
   // firmware parse 好嘅 int (key = SONAR_DISTANCE_EXTRA), 唔使自己再逐個
   // wire byte 解 CHES_SONAR_DISTANCE。見 ActionMainActivity$7.onReceive()
   // (intent.getIntExtra("sonar_distance", 0)) 同 ActionMainActivity.onCreate()
   // 個 registerReceiver(new IntentFilter("com.ubtechinc.sonar.distance"), ...)。
   public static final String SONAR_DISTANCE_ACTION = "com.ubtechinc.sonar.distance";
   public static final String SONAR_DISTANCE_EXTRA = "sonar_distance";
   public static final String HEADER_ACTION = "com.ubtechinc.services.header";
   public static final String SPEECHINIT_ACTION = "com.ubtechinc.services.speechclientinit";
   public static final String SPEECHCMD_ACTION = "com.ubtechinc.services.speechcmd";
   public static final String AUDIODEV_ACTION = "com.ubtechinc.services.audiodev";
   public static final String UPLOADCONTACTS_ACTION = "com.ubtechinc.services.uploadcontactslexicon";
   public static final String UPLOADALPHACMD_ACTION = "com.ubtechinc.services.uploadalphacmdlexicon";
   public static final String SERVICE_BASE_ACTION = "com.ubtechinc.services.baseaction";
   public static final String UPLOADALPHACMDOVER_ACTION = "com.ubtechinc.services.uploadoveralphacmdlexicon";
   public static final String STOPTTS_ACTION = "com.ubtechinc.services.stoptts";
   public static final String BASE_ACTION_EVENT = "base_event";
   public static final String BASE_ACTION_KEY_WIFICONNECTCHANGE = "wifi_connect_change";
   public static final String BASE_ACTION_KEY_CLOSEMOTORPOWER = "close_motor_power";
   public static final String BLUETOOTH_CONNECED_ACTION = "com.ubtechinc.services.bluetoothclientconnected";
   public static final String BLUETOOTH_DISCONNECTED_ACTION = "com.ubtechinc.services.bluetoothclientdisconnected";
   public static final String BLUETOOTH_SENDDATA_ACTION = "com.ubtechinc.services.bluetoothclientdata";
   public static final String ALPHA_PHONE_ACTION = "com.ubtechinc.services.phone";
   public static final String ALPHA_PHONE_NUM = "com.ubtechinc.services.phone.num";
   public static final String ALPHA_UPDATE_CHESSOFTWARE = "com.ubtechinc.update.chess";
   public static final String ALPHA_UPDATE_HEADERSOFTWARE = "com.ubtechinc.udate.header";
   public static final String ALPHA_BT_CONNECTION = "com.ubtechinc.services.bluetooth";
   public static final String ALPHA_GESTURE_ACTION = "come.ubt.alpha2.gesture";
   public static final String ALPHA_GESTURE_DIRECTION = "getstureDirection";
   public static final String ALPHA_GESTURE_ACTION_REC = "come.ubt.alpha2.gesture.rec";
   public static final String ALPHA_BT_ALARM = "com.ubt.alpha2.bt.alarm";
   public static final String ALPHA_QR_CODE = "com.ubt.alpha2.qr_code";
   public static final String ALPHA_QR_CODE_CANCLE = "com.ubt.alpha2.qr_code.cancle";
   public static final String ALPHA_APP_MANAGE = "com.ubt.alpha2.app.manager";
   public static final String ALPHA_DIGITAL_DECODE = "com.ubt.alpha2.digital.decode";
   public static final String ALPHA_THIRD_PARTY_PLAY_ACITON = "com.ubtechinc.services.thirdparty.playaction";
   public static final String ALPHA_THIRD_PARTY_ACTION_NAME = "TP_alphacmdName";
   public static final String ALPHA_THIRD_PARTY_SPEECH_RECOGNIZERRESULT = "com.ubtechinc.services.speech.thirdparty.recognizerresult";
   public static final String ALPHA_THIRD_PARTY_SPEECH_TTS = "com.ubtechinc.services.speech.thirdparty.tts";
   public static final String ALPHA_THIRD_PARTY_SPEECH_TEXT = "com.ubtechinc.services.speech.thirdparty.text";
   public static final String ALPHA_MSG_VERIFYCATION_CODE = "com.ubtechinc.services.verifycationcode";
   public static final String ALPHA_MSG_DESKCLOCK_WAKEUP = "com.ubtechinc.services.deskclock.wakeup";
   public static final String ALPHA_MSG_PLAY_ACTION = "com.ubtechinc.services.action.play";
   public static final String ALPHA_MSG_STOP_ACTION = "com.ubtechinc.services.action.stop";
   public static final String ALPHA_RECEIVE_COOEE = "AlphaCooeeServiceBussiness";
   public static final String ALPHA_SEND_COOEE = "CooeeServiceAlphaBussiness";
   public static final String ALPHA_COOEE_DECODE = "com.ubt.alpha2.cooee.decode";
   public static final String ALPHA_TTS_HINT = "com.ubtechinc.robot.tts_hint_wakeup";
   public static final String ALPHA_WIFI_RESULT = "com.ubt.alpha2.wifiresult";
   public static final String ALPHA_HARDWARE = "com.alpha2.hardware.test";
   public static final String ALPHA_MSG_BLUTETOOTH_OPEN = "com.ubtechinc.services.bluetooth.open";
   public static final String SYSTEM_SOUND_START = "android.intent.notify.SOUND_START";
   public static final String SYSTEM_SOUND_PAUSE = "android.intent.notify.SOUND_PAUSE";
   public static final String SYSTEM_SOUND_STOP = "android.intent.notify.SOUND_STOP";
   public static final String ALPHA_LED_ACTION = "com.ubtechinc.services.LED_ACTION";
   public static final String ALPHA_MASTER_CLEAR = "android.intent.action.MASTER_CLEAR";
   public static final String ALPHA_SET_RTC_TIME = "com.ubtechinc.services.SET_RTC_TIME";
   public static final String ALPHA_SEND_POWER_SAVE = "com.ubtechinc.services.POWER_SAVE";
   public static final String ALPHA_SET_CHARGE_PLAY = "com.ubtechinc.services.SET_CHARGE_PLAY";
   public static final String ALPHA_QUERY_CHARGE_PLAY = "com.ubtechinc.services.QUERY_CHARGE_PLAY";
   public static final String ALPHA_NUANCE_OFFLINE_CMD = "com.ubtechinc.services.NUANCE_OFFLINE_CMD";
   public static final String ALPHA_IFLY_OFFLINE_CMD = "com.ubtechinc.services.IFLY_OFFLINE_CMD";
   public static final String ALPHA_SERVICES_TTS = "com.ubtechinc.services.ABOUT_TTS";
   public static final String ALPHA_SERVICES_ACTION = "com.ubtechinc.services.ABOUT_ACTION";
   public static final String ALPHA_IS_TTS_ON = "alpha_is_tts_on";
   public static final String ALPHA_IS_ACTION_ON = "alpha_is_action_on";
   public static final String ALPHA_TTS_CONTENT = "alpha_tts_content";
   public static final String ALPHA_ACTION_NAME = "alpha_action_name";
   public static final String ALPHA_SPEECH_DIRECTION = "com.ubtechinc.services.SPEECH_DIRECTION";
   public static final String ALPHA_QUERY_POWER = "com.ubtechinc.services.ALPHA_QUERY_POWER";
   public static final String ALPHA_QUERY_SOFTWARE_VERSION = "com.ubtechinc.services.ALPHA_QUERY_SOFTWARE_VERSION";
   public static final String ALPHA_HANDLE_PARAM = "com.ubtechinc.services.ALPHA_HANDLE_PARAM";
   public static final String COMMAND_MESSAGE = "robot_command";
   public static final String PHOTO_URL = "photoUrl";

   // -- Chest microcontroller serial command bytes ---------------------------
   public static final byte CHEST_CMD_START = 1;
   public static final byte CHEST_CMD_STOP = 2;
   public static final byte CHEST_CMD_SENDMOTOR = 3;
   public static final byte CHEST_CMD_SETTING = 4;
   public static final byte CHES_CMD_MOTORANGLE = 5;
   public static final byte CHES_CMD_READANGLE = 6;
   public static final byte CHES_CMD_PAUSE = 7;
   public static final byte CHES_CMD_STOP = 9;
   public static final byte CHES_CMD_WIFISTATUS = 20;
   public static final byte CHES_CMD_ACTION = 21;
   public static final byte CHES_CMD_BTSTATUS = 22;
   public static final byte CHES_CMD_MOTOR_POWER = 25;
   public static final byte CHES_CMD_SAVE_ALARM = 26;
   public static final byte CHES_CMD_END_ALARM = 27;
   public static final byte CHES_CMD_AJUST_TIME = 28;
   public static final byte CHES_CMD_READ_TIME = 29;
   public static final byte CHES_CMD_READ_ALARM = 30;
   public static final byte CHES_CMD_START_UPDATE = 48;
   public static final byte CHES_CMD_UPDATE_PAGE = 49;
   public static final byte CHES_CMD_UPDATE_END = 50;
   public static final byte CHEST_READ_VERSION = 51;
   public static final byte CHEST_SET_ALL_ANGLE = 52;
   public static final byte CHEST_CHANGE_PLAY = 53;
   public static final byte CHEST_WRITE_SID_EEPROM = 54;
   public static final byte CHEST_READ_SID_EEPROM = 55;
   public static final byte CHEST_SQUAT = 56;
   public static final byte CHEST_POWER_SAVE = 64;
   public static final byte CHEST_SEND_POWER = -128;
   public static final byte CHES_SEND_OBSTACLE = -127;
   public static final byte CHES_SEND_ANGLEINFO = -126;
   public static final byte CHES_SEND_SHUTDWON = -125;
   public static final byte CHES_SEND_ALARM = -121;
   public static final byte CHEST_TOUCH_BOARD = -119;
   public static final byte CHES_DC_STATE = -118;
   public static final byte CHES_SEND_TEMPBEYOND = -110;
   public static final byte CHES_SEND_TRANSFORM = -106;
   public static final byte CHES_SEND_FALLDOWN = -105;

   // -- Head microcontroller serial command bytes ----------------------------
   public static final byte LED_EAR = 1;
   public static final byte LED_EYE = 2;
   public static final byte LED_MOUTH = 3;
   public static final byte INFRARED_SETTING = 4;
   public static final byte STARTUP_CMD = 5;
   public static final byte HEAD_PAUSE_CMD = 6;
   public static final byte HEAD_CONTINUE_CMD = 7;
   public static final byte STOP_CMD = 8;
   public static final byte HEAD_ACTION = 21;
   public static final byte HEAD_SHUTDOWN = 22;
   public static final byte SOUND_CONTROL = 25;
   public static final byte HEAD_BLUETOOTH_OPEN = 32;
   public static final byte HEAD_CONTROL_BYPASS = 39;
   public static final byte HEAD_CMD_START_UPDATE = 48;
   public static final byte HEAD_CMD_UPDATE_PAGE = 49;
   public static final byte HEAD_CMD_UPDATE_END = 50;
   public static final byte HEADER_SEND_OBSTACLE = -128;
   public static final byte HEADER_SEND_KEY = -127;
   public static final byte HEADER_SEND_TRANSFORM = -106;
   public static final byte HEADER_SEND_NOISE = 39;
   public static final byte HEADER_SEND_AMP = 25;
   public static final byte HEADER_READ_VERSION = 51;
   public static final byte HEADER_SYSTEM_REBOOT = 33;
   public static final byte HEADER_HIGHT_TEMP = -124;
   public static final byte HEADER_SOUND_DIRECTION = -126;
   public static final byte HEADER_FALL_DERECTION = -125;
}
