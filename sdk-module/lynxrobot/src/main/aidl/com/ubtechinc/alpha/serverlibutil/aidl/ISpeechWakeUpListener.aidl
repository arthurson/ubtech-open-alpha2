// Confirmed against com.ubtechinc.alpha2services_base.3.002.apk.
// TRANSACTION order: onSuccess=1, onError=2
package com.ubtechinc.alpha.serverlibutil.aidl;

interface ISpeechWakeUpListener {
    void onSuccess();
    void onError(int p0, String p1);
}
