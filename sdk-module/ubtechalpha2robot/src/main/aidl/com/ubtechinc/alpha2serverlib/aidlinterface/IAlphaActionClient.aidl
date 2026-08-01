// AIDL callback the robot's action service invokes when a running action stops.
// Package, interface name and method order are the Binder wire contract with the
// on-robot service and must not change.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaActionClient {
    void onActionStop(String strActionFileName);
}
