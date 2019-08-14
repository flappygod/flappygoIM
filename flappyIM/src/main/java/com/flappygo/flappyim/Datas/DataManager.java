package com.flappygo.flappyim.Datas;

import android.content.Context;
import android.content.SharedPreferences;

import com.flappygo.flappyim.ApiServer.Tools.GsonTool;
import com.flappygo.flappyim.FlappyImService;
import com.flappygo.flappyim.Models.Server.ChatUser;

public class DataManager {

    //单例模式
    private static DataManager instacne;

    // 首选项名称
    private final static String PREFERENCENAME = "com.flappygo.flappyim.data";

    // 用户信息保存
    private final static String KEY_FOR_USER = "com.flappygo.flappyim.data.KEY_FOR_USER";



    /********
     * 单例manager
     * @return
     */
    public static DataManager getInstance() {
        if (instacne == null) {
            synchronized (DataManager.class) {
                if (instacne == null) {
                    instacne = new DataManager();
                }
            }
        }
        return instacne;
    }

    //保存用户信息
    public void saveLoginUser(ChatUser user) {

        SharedPreferences mSharedPreferences = FlappyImService.getInstance().getAppContext().getSharedPreferences(
                PREFERENCENAME,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(KEY_FOR_USER, GsonTool.modelToString(user, ChatUser.class));
        editor.commit();
    }


    //获取保存的用户信息
    public ChatUser getLoginUser() {
        SharedPreferences mSharedPreferences = FlappyImService.getInstance().getAppContext().getSharedPreferences(
                PREFERENCENAME, Context.MODE_PRIVATE);
        //获取到设置信息
        String setting = mSharedPreferences.getString(KEY_FOR_USER, "");
        if (setting == null || setting.equals("")) {
            return null;
        }
        //转换为设置
        ChatUser model = GsonTool.jsonObjectToModel(setting, ChatUser.class);
        //返回配置信息
        return model;
    }

    //清空当前的用户信息，用户已经退出登录了
    public void clearUser(){
        SharedPreferences mSharedPreferences = FlappyImService.getInstance().getAppContext().getSharedPreferences(
                PREFERENCENAME,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(KEY_FOR_USER, "");
        editor.commit();
    }

}
