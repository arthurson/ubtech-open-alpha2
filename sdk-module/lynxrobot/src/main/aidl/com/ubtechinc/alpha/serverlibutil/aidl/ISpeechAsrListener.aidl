// Confirmed against com.ubtechinc.alpha2services_base.3.002.apk.
// TRANSACTION order: onBegin=1, onEnd=2, onResult=3, onError=4
package com.ubtechinc.alpha.serverlibutil.aidl;

interface ISpeechAsrListener {
    void onBegin();
    void onEnd();
    void onResult(String p0);
    void onError(int p0);
}
