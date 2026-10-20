package com.focuspen.enhance.v2;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Path;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.WeakHashMap;

/** Immutable ROM artwork shared by lesson pages, scoped to the host resource configuration. */
final class GuideAssets {
    record Art(Path screen,Path border,Path button,Path longButton,Bitmap standardPen,Bitmap proPen){}
    private record Cached(Configuration configuration,Art art){}
    // Values must not retain Context or Resources: an old Activity can release its assets.
    private static final WeakHashMap<Resources,Cached> cache=new WeakHashMap<>();

    static synchronized Art get(Context context){
        Resources resources=context.getResources();Configuration configuration=resources.getConfiguration();
        Cached old=cache.get(resources);
        if(old!=null&&old.configuration.equals(configuration))return old.art;
        Art art=load(context);
        cache.put(resources,new Cached(new Configuration(configuration),art));return art;
    }
    private static JSONObject source(Context context,String name)throws Exception{
        try(java.io.InputStream in=context.getResources().openRawResource(GuideNative.resource(context,"raw",name))){
            return new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private static Art load(Context context){
        Path screen=null,border=null,button=null,longButton=null;Bitmap standard=null,pro=null;
        try{
            JSONArray assets=source(context,"laser").getJSONArray("assets");
            for(int i=0;i<assets.length();i++){
                JSONObject asset=assets.getJSONObject(i);
                if("image_1".equals(asset.optString("id"))){
                    String data=asset.getString("p");byte[] bytes=Base64.decode(data.substring(data.indexOf(',')+1),Base64.DEFAULT);
                    standard=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
                }
                JSONArray layers=asset.optJSONArray("layers");if(layers==null)continue;
                for(int j=0;j<layers.length();j++){
                    JSONObject layer=layers.getJSONObject(j);
                    switch(layer.optString("nm")){
                        case "Screen"->screen=shape(layer);
                        case "Border"->border=shape(layer);
                        case "Button_Short"->button=shape(layer);
                    }
                }
            }
        }catch(Exception failure){android.util.Log.w("FocusPenGuide","Official device components unavailable",failure);}
        try{
            JSONArray assets=source(context,"laser_page").getJSONArray("assets");
            for(int i=0;i<assets.length();i++){
                JSONArray layers=assets.getJSONObject(i).optJSONArray("layers");if(layers==null)continue;
                for(int j=0;j<layers.length();j++)if("Button_Long".equals(layers.getJSONObject(j).optString("nm")))longButton=shape(layers.getJSONObject(j));
            }
        }catch(Exception failure){android.util.Log.w("FocusPenGuide","Official button component unavailable",failure);}
        try{
            int id=GuideNative.resource(context,"raw","touch_film_stylus_guide_img_2");
            if(id!=0)pro=BitmapFactory.decodeResource(context.getResources(),id);
        }catch(Exception failure){android.util.Log.w("FocusPenGuide","Official Pro component unavailable",failure);}
        return new Art(screen,border,button,longButton,standard,pro);
    }
    private static Path shape(JSONObject layer)throws JSONException{
        JSONArray items=layer.getJSONArray("shapes").getJSONObject(0).getJSONArray("it");JSONObject data=null;
        for(int i=0;i<items.length();i++)if("sh".equals(items.getJSONObject(i).getString("ty")))data=items.getJSONObject(i).getJSONObject("ks").getJSONObject("k");
        if(data==null)return null;
        JSONArray vertices=data.getJSONArray("v"),in=data.getJSONArray("i"),out=data.getJSONArray("o");Path path=new Path();
        path.moveTo(n(vertices,0,0),n(vertices,0,1));
        for(int i=1;i<=vertices.length();i++){
            int a=i-1,z=i%vertices.length();
            path.cubicTo(n(vertices,a,0)+n(out,a,0),n(vertices,a,1)+n(out,a,1),n(vertices,z,0)+n(in,z,0),n(vertices,z,1)+n(in,z,1),n(vertices,z,0),n(vertices,z,1));
        }
        path.close();return path;
    }
    private static float n(JSONArray a,int i,int j)throws JSONException{return (float)a.getJSONArray(i).getDouble(j);}
    private GuideAssets(){}
}
