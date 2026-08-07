// AIDL callback for IActionService.playAction/playActionFile/stopAction results.
// Method declaration order defines the Binder transaction ids and must match the
// on-robot service exactly (confirmed against com.ubtechinc.alpha2services_base.3.002.apk
// by decompiling IActionResultListener$Stub.onTransact()).
package com.ubtechinc.alpha.serverlibutil.aidl;

interface IActionResultListener {
    void onPlayActionResult(int p0, int p1);
    void onStopActionResult(int p0);
}
