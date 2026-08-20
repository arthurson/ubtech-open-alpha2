package com.open.lynx;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ubtechinc.alpha2ctrlapp.network.action.ClientAuthorizeListener;
import com.ubtechinc.alpha2robot.Alpha2RobotApi;
import com.ubtechinc.alpha2robot.constant.UbxErrorCode;
import com.ubtechinc.alpha2serverlib.interfaces.AlphaActionClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2ActionListListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotClientListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2RobotTextUnderstandListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarInitListener;
import com.ubtechinc.alpha2serverlib.interfaces.IAlpha2SpeechGrammarListener;
import com.ubtechinc.alpha2serverlib.util.Alpha2SpeechMainServiceUtil;

import java.util.ArrayList;

/**
 * Minimal test panel for lynx-open-sdk
 * ({@code com.ubtechinc.alpha2serverlib.aidlinterface}, confirmed against
 * com.ubtechinc.alpha2services.apk).
 *
 * <p>One button per {@link Alpha2RobotApi} call, grouped by subsystem, with a shared
 * scrolling log at the bottom showing every request/callback. No HTTP/WebSocket server,
 * camera, or audio recording - this exists purely to exercise the SDK from the device
 * screen.
 */
public class MainActivity extends Activity {

    private static final String TAG = "OpenLynx";

    private Alpha2RobotApi robot;
    private TextView logView;
    private EditText actionNameInput;
    private EditText ttsTextInput;
    private EditText understandTextInput;
    private EditText grammarInput;
    private EditText customMsgAppIdInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        robot = new Alpha2RobotApi(getApplicationContext(), "OpenLynxTestPanel",
                new ClientAuthorizeListener() {
                    @Override
                    public void onResult(int code, String info) {
                        log("ClientAuthorizeListener.onResult code=" + code + " info=" + info);
                    }
                });

        ScrollView root = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        content.setPadding(pad, pad, pad, pad);
        root.addView(content);
        setContentView(root);

        content.addView(sectionLabel("Init"));
        addRow(content,
                button("initActionApi", v -> onInitActionApi()),
                button("initChestSerialApi", v -> onInitChestSerialApi()),
                button("initHeaderSerialApi", v -> onInitHeaderSerialApi()),
                button("initSpeechApi", v -> onInitSpeechApi()));

        content.addView(sectionLabel("Action"));
        actionNameInput = addLabeledInput(content, "Action name", "wave");
        addRow(content,
                button("Get action list", v -> onGetActionList()),
                button("Play action", v -> onPlayAction()),
                button("Stop action", v -> onStopAction()));

        content.addView(sectionLabel("Chest / Head motors"));
        addRow(content,
                button("chest_SendOneFreeAngle", v -> onChestSendOneFreeAngle()),
                button("head_SendOneFreeAngle", v -> onHeadSendOneFreeAngle()),
                button("chest_configureSonar", v -> onChestConfigureSonar()));

        content.addView(sectionLabel("LED"));
        addRow(content,
                button("Ear LED on", v -> onStartEarLed()),
                button("Ear LED off", v -> onStopEarLed()),
                button("Eye LED on (red)", v -> onStartEyeLed()),
                button("Eye LED off", v -> onStopEyeLed()));

        content.addView(sectionLabel("Speech"));
        ttsTextInput = addLabeledInput(content, "TTS text", "你好");
        addRow(content,
                button("Speak", v -> onSpeak()),
                button("Stop speak", v -> onStopSpeak()),
                button("Set wake (listen)", v -> onSetWake(true)),
                button("Set wake (idle)", v -> onSetWake(false)));
        addRow(content,
                button("Start no-wakeup ASR", v -> onStartSpeechNoWakeup()),
                button("Start recognized", v -> onStartRecognized()),
                button("Stop recognized", v -> onStopRecognized()));
        understandTextInput = addLabeledInput(content, "Text to understand", "打開燈");
        addRow(content, button("Understand text", v -> onUnderstandText()));
        grammarInput = addLabeledInput(content, "Grammar", "test_grammar");
        addRow(content,
                button("Init grammar", v -> onInitGrammar()),
                button("Start grammar", v -> onStartGrammar()),
                button("Stop grammar", v -> onStopGrammar()));

        content.addView(sectionLabel("Custom message (XMPP)"));
        customMsgAppIdInput = addLabeledInput(content, "App ID", "OpenLynxTestPanel");
        addRow(content,
                button("initCustomMessageApi", v -> onInitCustomMessageApi()),
                button("Send request", v -> onSendCustomMessageRequest()),
                button("Send response", v -> onSendCustomMessageResp()));

        content.addView(sectionLabel("Misc"));
        addRow(content,
                button("requestRobotUUID", v -> onRequestRobotUUID()),
                button("isChestAvailable", v -> onIsChestAvailable()),
                button("isHeaderAvailable", v -> onIsHeaderAvailable()));

        content.addView(sectionLabel("Log"));
        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setMinHeight(dp(200));
        content.addView(logView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        robot.releaseApi();
    }

    // ------------------------------------------------------------------
    // Init
    // ------------------------------------------------------------------

    private void onInitActionApi() {
        boolean ok = robot.initActionApi(new AlphaActionClientListener() {
            @Override
            public void onActionStop(String strActionFileName) {
                log("onActionStop: " + strActionFileName);
            }
        });
        log("initActionApi -> " + ok);
    }

    private void onInitChestSerialApi() {
        boolean ok = robot.initChestSerialApi();
        log("initChestSerialApi -> " + ok);
    }

    private void onInitHeaderSerialApi() {
        boolean ok = robot.initHeaderSerialApi();
        log("initHeaderSerialApi -> " + ok);
    }

    private void onInitSpeechApi() {
        boolean ok = robot.initSpeechApi(new IAlpha2RobotClientListener() {
            @Override
            public void onServerCallBack(String text) {
                log("speech onServerCallBack: " + text);
            }

            @Override
            public void onServerPlayEnd(boolean isEnd) {
                log("speech onServerPlayEnd: " + isEnd);
            }
        }, new Alpha2SpeechMainServiceUtil.ISpeechInitInterface() {
            @Override
            public void initOver() {
                log("initSpeechApi -> initOver()");
            }
        });
        log("initSpeechApi -> " + ok);
    }

    // ------------------------------------------------------------------
    // Action
    // ------------------------------------------------------------------

    private void onGetActionList() {
        UbxErrorCode.API_ERROR_CODE result = robot.action_getActionList(new IAlpha2ActionListListener() {
            @Override
            public void onGetActionList(ArrayList<ArrayList<String>> list) {
                StringBuilder sb = new StringBuilder("onGetActionList total=" + (list == null ? 0 : list.size()));
                if (list != null) {
                    for (ArrayList<String> action : list) {
                        sb.append("\n  - ").append(action);
                    }
                }
                log(sb.toString());
            }
        });
        log("action_getActionList -> " + result);
    }

    private void onPlayAction() {
        String name = actionNameInput.getText().toString();
        UbxErrorCode.API_ERROR_CODE result = robot.action_PlayActionName(name);
        log("action_PlayActionName(" + name + ") -> " + result);
    }

    private void onStopAction() {
        UbxErrorCode.API_ERROR_CODE result = robot.action_StopAction();
        log("action_StopAction -> " + result);
    }

    // ------------------------------------------------------------------
    // Chest / Head motors
    // ------------------------------------------------------------------

    private void onChestSendOneFreeAngle() {
        UbxErrorCode.API_ERROR_CODE result = robot.chest_SendOneFreeAngle((byte) 1, 90, (short) 1000);
        log("chest_SendOneFreeAngle(id=1, angle=90, time=1000) -> " + result);
    }

    private void onHeadSendOneFreeAngle() {
        UbxErrorCode.API_ERROR_CODE result = robot.head_SendOneFreeAngle((byte) 1, 90, (short) 1000);
        log("head_SendOneFreeAngle(id=1, angle=90, time=1000) -> " + result);
    }

    private void onChestConfigureSonar() {
        UbxErrorCode.API_ERROR_CODE result = robot.chest_configureSonar(50);
        log("chest_configureSonar(50) -> " + result);
    }

    // ------------------------------------------------------------------
    // LED
    // ------------------------------------------------------------------

    private void onStartEarLed() {
        UbxErrorCode.API_ERROR_CODE result = robot.header_startEarLED((short) 500, (short) 0, (short) 5000);
        log("header_startEarLED -> " + result);
    }

    private void onStopEarLed() {
        UbxErrorCode.API_ERROR_CODE result = robot.header_stopEarLED();
        log("header_stopEarLED -> " + result);
    }

    private void onStartEyeLed() {
        UbxErrorCode.API_ERROR_CODE result = robot.header_startEyeLED(1, (short) 500, (short) 0, (short) 5000);
        log("header_startEyeLED(RED) -> " + result);
    }

    private void onStopEyeLed() {
        UbxErrorCode.API_ERROR_CODE result = robot.header_stopEyeLED();
        log("header_stopEyeLED -> " + result);
    }

    // ------------------------------------------------------------------
    // Speech
    // ------------------------------------------------------------------

    private void onSpeak() {
        String text = ttsTextInput.getText().toString();
        UbxErrorCode.API_ERROR_CODE result = robot.speech_StartTTS(text);
        log("speech_StartTTS(" + text + ") -> " + result);
    }

    private void onStopSpeak() {
        UbxErrorCode.API_ERROR_CODE result = robot.speech_StopTTS();
        log("speech_StopTTS -> " + result);
    }

    private void onSetWake(boolean isWake) {
        boolean ok = robot.speech_SetMIC(isWake);
        log("speech_SetMIC(" + isWake + ") -> " + ok);
    }

    private void onStartSpeechNoWakeup() {
        UbxErrorCode.API_ERROR_CODE result = robot.speech_startSpeechNoWakeup();
        log("speech_startSpeechNoWakeup -> " + result);
    }

    private void onStartRecognized() {
        String text = understandTextInput.getText().toString();
        UbxErrorCode.API_ERROR_CODE result = robot.speech_startRecognized(text);
        log("speech_startRecognized(" + text + ") -> " + result);
    }

    private void onStopRecognized() {
        UbxErrorCode.API_ERROR_CODE result = robot.speech_stopRecognized();
        log("speech_stopRecognized -> " + result);
    }

    private void onUnderstandText() {
        String text = understandTextInput.getText().toString();
        UbxErrorCode.API_ERROR_CODE result = robot.speech_understandText(text,
                new IAlpha2RobotTextUnderstandListener() {
                    @Override
                    public void onAlpha2UnderStandError(int errorCode) {
                        log("onAlpha2UnderStandError: " + errorCode);
                    }

                    @Override
                    public void onAlpha2UnderStandTextResult(String result) {
                        log("onAlpha2UnderStandTextResult: " + result);
                    }
                });
        log("speech_understandText(" + text + ") -> " + result);
    }

    private void onInitGrammar() {
        String grammar = grammarInput.getText().toString();
        UbxErrorCode.API_ERROR_CODE result = robot.speech_initGrammar(grammar,
                new IAlpha2SpeechGrammarInitListener() {
                    @Override
                    public void speechGrammarInitCallback(String grammarId, int errorCode) {
                        log("speechGrammarInitCallback id=" + grammarId + " errorCode=" + errorCode);
                    }
                });
        log("speech_initGrammar(" + grammar + ") -> " + result);
    }

    private void onStartGrammar() {
        UbxErrorCode.API_ERROR_CODE result = robot.speech_startGrammar(new IAlpha2SpeechGrammarListener() {
            @Override
            public void onSpeechGrammarResult(int type, String grammarResult) {
                log("onSpeechGrammarResult type=" + type + " result=" + grammarResult);
            }

            @Override
            public void onSpeechGrammarError(int errorCode) {
                log("onSpeechGrammarError: " + errorCode);
            }
        });
        log("speech_startGrammar -> " + result);
    }

    private void onStopGrammar() {
        UbxErrorCode.API_ERROR_CODE result = robot.speech_stopGrammar();
        log("speech_stopGrammar -> " + result);
    }

    // ------------------------------------------------------------------
    // Custom message (XMPP)
    // ------------------------------------------------------------------

    private void onInitCustomMessageApi() {
        boolean ok = robot.initCustomMessageApi(message ->
                log("onReceiveMessage: " + new String(message)));
        log("initCustomMessageApi -> " + ok);
    }

    private void onSendCustomMessageRequest() {
        String appId = customMsgAppIdInput.getText().toString();
        UbxErrorCode.API_ERROR_CODE result = robot.sendCustomMessageRequest(appId, "ping".getBytes());
        log("sendCustomMessageRequest(" + appId + ") -> " + result);
    }

    private void onSendCustomMessageResp() {
        String appId = customMsgAppIdInput.getText().toString();
        UbxErrorCode.API_ERROR_CODE result = robot.sendCustomMessageResp(appId, "pong".getBytes());
        log("sendCustomMessageResp(" + appId + ") -> " + result);
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private void onRequestRobotUUID() {
        robot.requestRobotUUID();
        log("requestRobotUUID() sent (broadcast com.ubtechinc.robot_uuid.request)");
    }

    private void onIsChestAvailable() {
        log("isChestAvailable -> " + robot.isChestAvailable());
    }

    private void onIsHeaderAvailable() {
        log("isHeaderAvailable -> " + robot.isHeaderAvailable());
    }

    // ------------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------------

    private void log(final String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> {
            if (logView != null) {
                logView.append(message + "\n\n");
            }
        });
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(16);
        label.setPadding(0, dp(16), 0, dp(4));
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        return label;
    }

    private EditText addLabeledInput(LinearLayout parent, String hint, String defaultValue) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(defaultValue);
        parent.addView(input);
        return input;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private void addRow(LinearLayout parent, Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (Button b : buttons) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(b, lp);
        }
        parent.addView(row);
    }
}
