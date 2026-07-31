package com.ubtech.alpha2demo;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;

import com.ubtech.interfaces.RobotHandle;
import com.ubtech.state.ChatCode;
import com.ubtech.state.RobotState;
import com.ubtech.util.FucUtil;
import com.ubtech.util.StateLog;
import com.ubtechinc.alpha2ctrlapp.network.action.ClientAuthorizeListener;
import com.ubtechinc.alpha2robot.Alpha2RobotApi;
import com.ubtechinc.alpha2robot.constant.AlphaContant;
import com.ubtechinc.alpha2serverlib.interfaces.AlphaActionClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2ActionListListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2CustomMessageListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarListener;
import com.ubtechinc.alpha2serverlib.util.Alpha2SpeechMainServiceUtil;
import com.ubtechinc.contant.CustomLanguage;
import com.ubtechinc.contant.LauguageType;
import com.ubtechinc.contant.StaticValue;
import com.ubtechinc.developer.DeveloperAppStaticValue;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 *
 * If you don't understand the api that using following, please check alpha2 API.zip for more details.
 *
 * @author wzt
 *
 */

public class NewSDKActivity extends Activity implements
        IAlpha2RobotClientListener, Alpha2SpeechMainServiceUtil.ISpeechInitInterface,
        IAlpha2RobotTextUnderstandListener, IAlpha2SpeechGrammarInitListener,
        AlphaActionClientListener,IAlpha2ActionListListener, IAlpha2CustomMessageListener {
    private static String TAG = "NewSDKActivity";
    private Alpha2RobotApi mRobot;
    private ExitBroadcast mExitBroadcast;
    private ArrayList<String> mAlphaActionList = new ArrayList<String>();
    private ArrayList<String> mAlphaDanceList = new ArrayList<String>();
    private ArrayList<String> mAlphaStoryList = new ArrayList<String>();

    private String key_word = "";
    private String mPackageName;
    private String mLocalGrammar;
    private String localVersion;
    private RobotState mState;

    private String sourceActivity;

    public byte currentAngle;

    /** IMPORTANT:
     *
     *  It's strongly recommended to handle the ASR result in handler, which decouples main service and this app.
     *
     */
    private Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            // TODO Auto-generated method stub
            super.handleMessage(msg);
            switch (msg.what) {
                case ChatCode.GRAMMER_RESULT:
                    grammerResult((String) msg.obj);
                    break;
                case ChatCode.RECOGNIZED_RESULT:
                    break;
                case ChatCode.GRAMMER_INIT:
                    break;
                case ChatCode.START_SMARTCAMERA:
                    changeSartCamera();
                    break;
                case ChatCode.GETACTION_LIST:
                    /**
                     * Get the list of actions.
                     */
                    mRobot.action_getActionList(NewSDKActivity.this);
                    break;
                case ChatCode.ACTION_LISG:
                    initActionList((ArrayList<ArrayList<String>>) msg.obj);
                    break;
                default:
                    break;
            }
        }

    };

    private void initActionList(ArrayList<ArrayList<String>> list) {
        if(list != null) {
            for (ArrayList<String> item : list) {
                if (item.get(1) != null && item.get(2) != null) {
                    if("1".equals(item.get(1))) {
                        mAlphaActionList.add(item.get(2));
                    } else if("2".equals(item.get(1))) {
                        mAlphaDanceList.add(item.get(2));
                    } else if("3".equals(item.get(1))) {
                        mAlphaStoryList.add(item.get(2));
                    }
                }
            }
        }

    }
    private boolean isWakeup;

    public synchronized RobotState getmState() {
        return mState;
    }

    public synchronized void setmState(RobotState mState) {
        Log.i("zdy", "mState " + this.mState + " --> " + mState);
        this.mState = mState;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        /**
         * The offline grammar which will be applyed in iflytek.
         * It only used when you specify the ASR language as LauguageType.LAU_CHINESE
         */
        mLocalGrammar = FucUtil.readFile(this, "call.bnf", "utf-8");
        init();

    }

    /**
     * init Alpha2RobotApi
     */
    public void init() {
        localVersion = getVersionName(this);
        Bundle bundle = getIntent().getExtras();
        Log.i("zdy", "bundle " + bundle);

        if (bundle != null) {
            Log.i("zdy", "bundle " + sourceActivity);
            sourceActivity = bundle.getString("srcApp");
        }

        /**
         * It's recommended to register broadcastreceiver in onCreate().
         * If you register it later, you may miss receiving some broadcasts.
         */
        mPackageName = this.getPackageName();
        IntentFilter filter = new IntentFilter();
        //
        filter.addAction(DeveloperAppStaticValue.APP_EXIT);
        filter.addAction(mPackageName);
        filter.addAction(StaticValue.ALPHA_SPEECH_DIRECTION);
        filter.addAction(StaticValue.ALPHA_TTS_HINT);
        filter.addAction(DeveloperAppStaticValue.APP_ROBOT_UUID_INFO);
        filter.addAction(mPackageName + DeveloperAppStaticValue.APP_CONFIG);
        filter.addAction(mPackageName + DeveloperAppStaticValue.APP_CONFIG_SAVE);
        filter.addAction(mPackageName + DeveloperAppStaticValue.APP_BUTTON_EVENT);
        filter.addAction(mPackageName + DeveloperAppStaticValue.APP_BUTOON_EVENT_CLICK);
        mExitBroadcast = new ExitBroadcast();
        NewSDKActivity.this.registerReceiver(mExitBroadcast, filter);

        /**
         * "02DB30C700228968A5BFFB453F589DE" is the appkey which you get after you register your app in www.ubtrobot.com.
         */
        mRobot = new Alpha2RobotApi(this, "02DB30C700228968A5BFFB453F589DE",
                new ClientAuthorizeListener() {

                    @Override
                    public void onResult(int code, String info) {
                        // TODO Auto-generated method stub
                        Log.i("zdy", "code = " + code + " info= " + info);
                        if (code == 1) {
                            initApi();
                        }
                    }
                });
    }

    private void initApi() {
        mRobot.initChestSeiralApi();
        mRobot.initSpeechApi(NewSDKActivity.this, NewSDKActivity.this, null);
        mRobot.initActionApi(NewSDKActivity.this);
        mRobot.initCustomMessageApi(NewSDKActivity.this);

        mHandler.sendEmptyMessageDelayed(ChatCode.GETACTION_LIST, 1000);
    }

    /**
     *  The callback api after the initialization of ASR.
     */
    @Override
    public void initOver() {
        // TODO Auto-generated method stub

        mRobot.speech_setVoiceName("xiaoyan");
        /**
         * Specify the ASR language, such as LauguageType.LAU_CHINESE, LauguageType.LAU_ENGLISH.
         */
        mRobot.speech_setRecognizedLanguage(LauguageType.LAU_CHINESE);
        /**
         * IMPORTANT: the mLocalGrammar can't be null when you specify the ASR language as LauguageType.LAU_CHINESE.
         * And you shoud set mLocalGrammar as null when you specify the ASR language as LauguageType.LAU_ENGLISH.
         */
        mRobot.speech_initGrammar(mLocalGrammar, this);

        /**
         * IMPORTANT: It must be called when you specify the ASR language as LauguageType.LAU_CHINESE.
         * And you should not call this when you specify the ASR language as LauguageType.LAU_ENGLISH.
         */
        mRobot.header_setNoise(false);

        mRobot.requestRobotUUID();
    }

    /**
     * The callback api that get the list of actions which include basic aciton, dance, story.
     */
    @Override
    public void onGetActionList(ArrayList<ArrayList<String>> list) {
        Message msg = new Message();
        msg.what = ChatCode.ACTION_LISG;
        msg.obj = list;
        mHandler.sendMessage(msg);

    }

    /**
     * The callback api when receives messge from custom app that installed in mobile phone.
     */
    @Override
    public void onReceiveMessage(byte[] bytes) {

    }

    public class ExitBroadcast extends BroadcastReceiver {

        @Override
        public void onReceive(Context arg0, Intent intent) {
            // TODO Auto-generated method stub
            String action = intent.getAction();
            Log.i("zdy", "xxxxx " + action);

            if (action.equals(DeveloperAppStaticValue.APP_EXIT)) {
                NewSDKActivity.this.finish();
            } else if (action.equals(mPackageName)) {

            } else if (StaticValue.ALPHA_SPEECH_DIRECTION.equals(action)) {
                /**
                 * Receive the angle that the head should turn to, which indicates the direction of sound.
                 */
                byte angle = intent.getByteExtra("absoluteAngle", (byte) 0);
                currentAngle = angle;
                processSpeechAngle(angle);
            } else if (StaticValue.ALPHA_TTS_HINT.equals(action)) {
                /**
                 * This indicates that robot has been wakeup.
                 */
                Bundle bundle = intent.getExtras();
                String hintEvent = bundle.getString("hint_event");
                if (hintEvent != null && hintEvent.equals("wakeup")) {
                    stopProcess();
                    isWakeup = true;
                }
            } else if (action.equals(
                    mPackageName + DeveloperAppStaticValue.APP_CONFIG)) {
                /**
                 * The Alpha2Ctrl installed in mobile phone gets the configuration of this app.
                 * You must put a 'config.json' in assets
                 * and add the following info in AndroidManifest.xml:
                 * <meta-data
                 *     android:name="alpha2_appconfig"
                 *     android:value="config" />
                 */
                mRobot.sendConfig2Server(intent, mPackageName, "utf-8");
            } else if (action.equals(
                    mPackageName + DeveloperAppStaticValue.APP_CONFIG_SAVE)) {
                /**
                 * The Alpha2Ctrl installed in mobile phone sets the configuration of this app.
                 */
                mRobot.writeConfig(intent);
            } else if (intent.getAction().equals(
                    mPackageName + DeveloperAppStaticValue.APP_BUTTON_EVENT)) {
                /**
                 * The Alpha2Ctrl installed in mobile phone gets the button of this app.
                 * You must put a 'button.json' in assets
                 * and add the following info in AndroidManifest.xml:
                 * <meta-data
                 *      android:name="alpha2_buttonevent"
                 *      android:value="buttonevent" />
                 */
                mRobot.sendButtonEvent2Server(intent, mPackageName, "gbk");
            } else if (intent.getAction().equals(
                    mPackageName + DeveloperAppStaticValue.APP_BUTOON_EVENT_CLICK)) {
                /**
                 * The Alpha2Ctrl installed in mobile phone sends the button event to this app.
                 */
                String index = mRobot.parseClickEvent(intent, mPackageName);
                if (index.equals("0")) {
                    //cat.performClick();
                }
            } else if (DeveloperAppStaticValue.APP_ROBOT_UUID_INFO.equals(action)) {
                /**
                 * Receive uuid of the robot after calling requestRobotUUID().
                 */
                String uuid = intent.getStringExtra("robot_uuid");
            }
        }
    }

    @Override
    public void onServerCallBack(String text) {
        // TODO Auto-generated method stub
        Log.e("zdy", "ASR Result=" + text);
        if (text != null && !text.equals("")) {
            mHandler.obtainMessage(ChatCode.RECOGNIZED_RESULT, text)
                    .sendToTarget();
        }

    }

    /**
     * When the TTS is finished, this api will be called.
     */
    @Override
    public void onServerPlayEnd(boolean isEnd) {
        Log.d("zdy", "onServerPlayEnd");
    }


    @Override
    public void onAlpha2UnderStandError(int arg0) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onAlpha2UnderStandTextResult(String arg0) {
        // TODO Auto-generated method stub
        Log.i("zdy", "nlp result" + arg0);
        if (arg0 != null && !arg0.equals("")) {
            int number = new Random().nextInt(10);
            String actionName = String.format("ACT%d", number);
            mRobot.action_PlayActionName(actionName);
            String newText = new String(arg0);
            mRobot.speech_startTTS(LauguageType.LAU_CHINESE, newText, "");
        }

    }

    @Override
    protected void onDestroy() {
        // TODO Auto-generated method stub
        super.onDestroy();
        if (mExitBroadcast != null) {
            this.unregisterReceiver(mExitBroadcast);
            mExitBroadcast = null;
        }
        stopProcess();

        /**
         * Before destroy, stop TTS and action.
         */
        if (mRobot != null) {
            mRobot.speech_StopTTS();
            mRobot.action_StopAction();
        }

        if (mRobot != null) {
            mRobot.releaseApi();
            mRobot = null;
        }
        Log.i("zdy", "onDestroy ");

        System.exit(0);
    }


    @Override
    public void speechGrammarInitCallback(String arg0, int nErrorCode) {
        // TODO Auto-generated method stub

        Log.i("zdy", "speeh_startGrammar init over");
        StateLog.Log(" speechGrammarInitCallback init over");
        setmState(RobotState.IDEL);
        StateLog.Log(" speechGrammarInitCallback init over");

        mHandler.obtainMessage(ChatCode.GRAMMER_INIT).sendToTarget();

        mRobot.speeh_startGrammar(new IAlpha2SpeechGrammarListener() {

            @Override
            public void onSpeechGrammarResult(int SpeechResultType,
                                              String strResult) {
                Log.i("zdy", "mState =" + mState);
                Log.i("zdy", "SpeechResultType =" + SpeechResultType);
                Log.i("zdy", "strResult =" + strResult);
                mHandler.obtainMessage(ChatCode.GRAMMER_RESULT, strResult)
                        .sendToTarget();

            }

            @Override
            public void onSpeechGrammarError(int nErrorCode) {
                // TODO Auto-generated method stub

            }

        });

    }

    public void grammerResult(String strResult) {
        StateLog.Log(" grammerResult " + strResult);
        StateLog.Log(" grammerResult1 " + getmState());
        if (getmState() == RobotState.IDEL) {
            StateLog.Log(" grammerResult2 " + getmState());
            setmState(RobotState.PARSE);
            StateLog.Log(" grammerResult3 " + getmState());

            setmState(RobotState.IDEL);
/*
            NLPResult nlp = new JsonResultParse(NewSDKActivity.this)
                    .paseJson(strResult);

            if (nlp.isParseState()) {
                ExcelParser excel = new ExcelParser();
                NLPResult nlpExc = excel.readExcelToPase(this,
                        nlp.getRecognizeResult());
                if (nlpExc.isParseState()) {
                    nlp =
                            nlpExc;
                }
            }

            synchronized (this) {
                processResult(nlp);
            }*/
            // mCheckTimeOut.clearTimeCheck();
        }
    }

    private void stopProcess() {
        isWakeup = false;

        StateLog.Log(" stopProcess1 " + getmState());
        setmState(RobotState.IDEL);
        StateLog.Log(" stopProcess2 " + getmState());

    }

    public void changeSartCamera() {

        Intent intent = new Intent(StaticValue.ALPHA_APP_MANAGE);
        Bundle bundle = new Bundle();
        bundle.putString("appevent", "start");
        bundle.putString("packageName", "com.ubtech.smartcamera");
        bundle.putString("name", "乐拍");
        bundle.putString("clientIP", "");
        bundle.putString("srcApp", this.getPackageName());
        bundle.putByte("angle", currentAngle);
        intent.putExtras(bundle);
        this.sendBroadcast(intent);

    }

    private boolean isAppInstalled(Context context, String uri) {
        PackageManager pm = context.getPackageManager();
        boolean installed = false;
        try {
            pm.getPackageInfo(uri, PackageManager.GET_ACTIVITIES);
            installed = true;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        }
        Log.e("zdy", "changeSartCamera " + installed);

        return installed;
    }

    private void processSpeechAngle(byte angle) {
        if (isWakeup == true) return;
        int angleINT = angle;
        int angleHight = (angleINT << 8);
        if (angleINT < 0) {
            angleINT = 256 + angleINT;
        }
        if (getmState() == RobotState.IDEL) {
            Log.d("zdy", "processSpeechAngle angleINT=" + angleINT);
            /**
             * Command the robot to turn it's head.
             */
            mRobot.chest_SendOneFreeAngle((byte) 19, angleINT, (short) 500);
        }
    }

    /**
     * When action is finished, this will be called.It indicates the end of action.
     */
    @Override
    public void onActionStop(String arg0) {
        // TODO Auto-generated method stub
        Log.i("zdy", "onActionStop arg0=" + arg0);
        StateLog.Log(" onActionStop " + getmState());
        setmState(RobotState.IDEL);
        StateLog.Log(" onActionStop " + getmState());

    }

    /**
     * The api for controlling robot.
     */
    public RobotHandle robotHandle = new RobotHandle() {

        @Override
        public void stop_TTS() {
            // TODO Auto-generated method stub
            mRobot.speech_StopTTS();
        }

        @Override
        public void stop_FreeAngle() {
            // TODO Auto-generated method stub

        }

        @Override
        public void stat_FreeAngle() {
            // TODO Auto-generated method stub

        }

        @Override
        public void start_TTS(String text, boolean isNeedAction) {
            // TODO Auto-generated method stub
            mRobot.speech_startTTS(LauguageType.LAU_CHINESE, text, "");
            if (isNeedAction) {
                int number = new Random().nextInt(10);
                String actionName = String.format("ACT%d", number);
                mRobot.action_PlayActionName(actionName);
            }
        }

        @Override
        public void start_Action(String action) {
            // TODO Auto-generated method stub
            mRobot.action_PlayActionName(action);
        }

        @Override
        public void onCompletion() {
            // TODO Auto-generated method stub
            StateLog.Log(" onCompletion " + getmState());
            setmState(RobotState.IDEL);
            StateLog.Log(" onCompletion " + getmState());
        }

        @Override
        public void stop_Recognized() {
            // TODO Auto-generated method stub
        }

        @Override
        public void start_Recognized() {
            // TODO Auto-generated method stub

        }

        @Override
        public void start_Music() {
            // TODO Auto-generated method stub
        }

        @Override
        public void setSelfInterrupt(boolean isInterrupt) {
            // It is only used when you specify the ASR language as LauguageType.LAU_CHINESE.
            mRobot.speech_setSelfInterrupt(isInterrupt);
        }

    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // TODO Auto-generated method stub
        super.onActivityResult(requestCode, resultCode, data);
        Log.i("zdy", "88888888888");

        robotHandle.onCompletion();
    }


    // version
    public String getVersionName(Context context) {
        return getPackageInfo(context).versionName;
    }

    // version code
    public int getVersionCode(Context context) {
        return getPackageInfo(context).versionCode;
    }

    private PackageInfo getPackageInfo(Context context) {
        PackageInfo pi = null;

        try {
            PackageManager pm = context.getPackageManager();
            pi = pm.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_CONFIGURATIONS);

            return pi;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return pi;
    }


}
