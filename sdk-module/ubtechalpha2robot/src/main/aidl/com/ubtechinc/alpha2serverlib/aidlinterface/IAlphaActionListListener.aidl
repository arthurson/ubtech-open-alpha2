// AIDL callback delivering the robot's action list as a single encoded string.
// Wire contract with the on-robot service - do not rename or reorder.
package com.ubtechinc.alpha2serverlib.aidlinterface;

interface IAlphaActionListListener {
    void onGetActionList(String list);
}
