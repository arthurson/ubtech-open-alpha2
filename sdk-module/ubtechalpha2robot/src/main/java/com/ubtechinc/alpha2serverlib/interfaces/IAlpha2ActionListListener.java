package com.ubtechinc.alpha2serverlib.interfaces;

import java.util.ArrayList;

/**
 * Delivers the robot's action list. Each inner list is one action, with fields
 * [id, type, cn-name, en-name] as split from the robot's response.
 */
public interface IAlpha2ActionListListener {
   void onGetActionList(ArrayList<ArrayList<String>> list);
}
