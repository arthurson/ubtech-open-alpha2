// AIDL for the top-level Alpha2 service registry/binder broker. Method declaration
// order defines the Binder transaction ids and must match the on-robot service
// exactly. Confirmed against com.ubtechinc.alpha2services_base.3.002.apk by
// decompiling IServiceFetcher$Stub.onTransact():
//   TRANSACTION_getService = 1
//   TRANSACTION_addService = 2
//   TRANSACTION_removeService = 3
//   TRANSACTION_registerAppIdKey = 4
package com.ubtechinc.alpha.serverlibutil.aidl;

interface IServiceFetcher {
    IBinder getService(String p0);
    void addService(String p0, IBinder p1);
    void removeService(String p0);
    void registerAppIdKey(String p0, String p1, String p2, IBinder p3);
}
