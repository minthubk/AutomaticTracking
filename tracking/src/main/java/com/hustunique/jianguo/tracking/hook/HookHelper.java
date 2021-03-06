/*
 *
 * Copyright 2017 TedaLIEz
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.hustunique.jianguo.tracking.hook;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import com.hustunique.jianguo.tracking.Config;
import com.hustunique.jianguo.tracking.track.TrackInstrumentation;
import com.hustunique.jianguo.tracking.track.WatchDog;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by JianGuo on 11/25/16. Helper class for hook
 */

public class HookHelper {

  // TODO: add hook cache to reduce reflection cost
  private static Map<String, Class<?>> clzMap = new HashMap<>();
  private static Map<String, Method> methodMap = new HashMap<>();
  private static Map<String, Field> fieldMap = new HashMap<>();
  private static final String TAG = "HookHelper";

  static {
    try {
      clzMap.put("android.app.ActivityThread", Class.forName("android.app.ActivityThread"));
      clzMap.put("android.app.ActivityManagerNative",
          Class.forName("android.app.ActivityManagerNative"));
      clzMap.put("android.app.IActivityManager", Class.forName("android.app.IActivityManager"));
      clzMap.put("android.util.Singleton", Class.forName("android.util.Singleton"));
      clzMap.put("android.view.View$OnClickListener",
          Class.forName("android.view.View$OnClickListener"));
      fieldMap.put("sCurrentActivityThread", clzMap.get("android.app.ActivityThread")
          .getDeclaredField("sCurrentActivityThread"));
      fieldMap.put("mH", clzMap.get("android.app.ActivityThread")
          .getDeclaredField("mH"));
      fieldMap.put("mCallback", Handler.class.getDeclaredField("mCallback"));
      methodMap.put("getActivity",
          clzMap.get("android.app.ActivityThread").getMethod("getActivity", IBinder.class));
      methodMap
          .put("getListenerInfo", View.class.getDeclaredMethod("getListenerInfo", (Class[]) null));
    } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
      Log.wtf(TAG, e);
    }
  }

  public static void hookInstrumentation(WatchDog watchDog)
      throws IllegalAccessException, NoSuchFieldException {
    Class<?> activityThreadClass = clzMap.get("android.app.ActivityThread");
    Field currentActivityThreadField = fieldMap.get("sCurrentActivityThread");
    currentActivityThreadField.setAccessible(true);
    Object currentActivityThread = currentActivityThreadField.get(null);
    Field mInstrumentationField = activityThreadClass.getDeclaredField("mInstrumentation");
    mInstrumentationField.setAccessible(true);
    TrackInstrumentation trackInstrumentation = new TrackInstrumentation(watchDog);
    mInstrumentationField.set(currentActivityThread, trackInstrumentation);
  }

  public static void hookActivityManager(Config config) {
    try {
      Class<?> activityManagerNativeClass = clzMap.get("android.app.ActivityManagerNative");
      Field gDefaultField = activityManagerNativeClass.getDeclaredField("gDefault");
      gDefaultField.setAccessible(true);
      Class<?> iActivityManagerInterface = clzMap.get("android.app.IActivityManager");
      Object gDefault = gDefaultField.get(null);
      if (Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
        Class<?> singleton = clzMap.get("android.util.Singleton");
        Field mInstanceField = singleton.getDeclaredField("mInstance");
        mInstanceField.setAccessible(true);
        Object rawIActivityManager = mInstanceField.get(gDefault);

        Object proxy = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
            new Class<?>[]{iActivityManagerInterface},
            new IActivityManagerHandler(rawIActivityManager));
        mInstanceField.set(gDefault, proxy);
      } else {
        Object proxy = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
            new Class<?>[]{iActivityManagerInterface}, new IActivityManagerHandler(gDefault));
        gDefaultField.set(gDefault, proxy);
      }
    } catch (IllegalAccessException | NoSuchFieldException e) {
      e.printStackTrace();
    }
  }

  public static Activity hookActivity(IBinder token)
      throws ClassNotFoundException, NoSuchFieldException,
      IllegalAccessException, NoSuchMethodException, InvocationTargetException {
    Field currentActivityThreadField = fieldMap.get("sCurrentActivityThread");
    currentActivityThreadField.setAccessible(true);
    Object currentActivityThread = currentActivityThreadField.get(null);
    Method getActivityMethod = methodMap.get("getActivity");
    return (Activity) getActivityMethod.invoke(currentActivityThread, token);
  }


  private static boolean getCode(Class<?> activityThreadClass, int[] code)
      throws NoSuchFieldException, IllegalAccessException {
    Class<?>[] clz = activityThreadClass.getDeclaredClasses();
    if (clz.length == 0) {
      return false;
    }
    for (Class innerClass : clz) {
      if (innerClass.getSimpleName().equals("H")) {
        Field launchField = innerClass.getField("LAUNCH_ACTIVITY");
        code[0] = launchField.getInt(null);
        Field resumeField = innerClass.getField("RESUME_ACTIVITY");
        code[1] = resumeField.getInt(null);
        return true;
      }
    }
    return false;
  }



  public static void hookListener(View view, Config.Callback callback)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException,
      NoSuchFieldException, ClassNotFoundException {
    if (view.hasOnClickListeners()) {
      Method method = methodMap.get("getListenerInfo");
      if (null != method) {
        method.setAccessible(true);
        Object listenerInfo = method.invoke(view, (Object[]) null);
        if (null != listenerInfo) {
          Class<?> listenerInfoClz = listenerInfo.getClass();
          Field listenerField = listenerInfoClz.getDeclaredField("mOnClickListener");
          listenerField.setAccessible(true);
          View.OnClickListener listener = (View.OnClickListener) listenerField.get(listenerInfo);
          Class<?> onClickListenerClz = clzMap.get("android.view.View$OnClickListener");
          Object proxy = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
              new Class[]{onClickListenerClz}, new IClickHandler(listener, callback));
          listenerField.set(listenerInfo, proxy);
        }
      }
    }
  }
}
