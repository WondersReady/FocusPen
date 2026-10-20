package com.focuspen.enhance.v2;

import android.content.Context;
import android.os.Bundle;
import android.view.InputDevice;

/** Display-only preference. It never changes the input configuration revision. */
final class UiPreferences {
    static String model(Context c) {
        try {
            Bundle b=c.getContentResolver().call(ConfigProvider.URI,"uiModel",null,null);
            return b==null?"auto":b.getString("model","auto");
        }catch(Exception e){return "auto";}
    }
    static void model(Context c,String value) {
        c.getContentResolver().call(ConfigProvider.URI,"uiModel",value,null);
    }
    static boolean pro(Context c) {
        String value=model(c);
        if(!"auto".equals(value))return "pro".equals(value);
        try {
            java.lang.reflect.Method method=InputDevice.class.getDeclaredMethod("isXiaomiStylus");method.setAccessible(true);
            for(int id:InputDevice.getDeviceIds()) {
                InputDevice d=InputDevice.getDevice(id);
                if(d!=null && Integer.valueOf(8).equals(method.invoke(d)))return true;
            }
        }catch(Exception ignored){}
        return false;
    }
    private UiPreferences(){}
}
