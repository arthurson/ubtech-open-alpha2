package com.ubtechinc.alpha2serverlib.interfaces;

/** Notified when a running action finishes; the argument is the action file name. */
public interface AlphaActionClientListener {
   void onActionStop(String strActionFileName);
}
