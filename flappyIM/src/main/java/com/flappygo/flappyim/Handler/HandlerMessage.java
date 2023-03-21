package com.flappygo.flappyim.Handler;

import static com.flappygo.flappyim.Holder.HolderMessageSession.globalMsgTag;

import com.flappygo.flappyim.Holder.HolderMessageSession;
import com.flappygo.flappyim.Models.Server.ChatMessage;
import com.flappygo.flappyim.Listener.MessageListener;

import android.os.Handler;
import android.os.Message;

import java.util.List;

public class HandlerMessage extends Handler {

    //收到新的消息了
    public static final int MSG_CREATE = 0;

    //收到新的消息了
    public static final int MSG_RECEIVE = 1;

    //消息的状态更新
    public static final int MSG_UPDATE = 2;

    //执行消息
    public void handleMessage(Message message) {
        //消息被创建
        if (message.what == MSG_CREATE) {
            ChatMessage chatMessage = (ChatMessage) message.obj;
            for (String key : HolderMessageSession.getInstance().getMsgListeners().keySet()) {
                if (chatMessage.getMessageSession().equals(key)) {
                    List<MessageListener> messageListeners = HolderMessageSession.getInstance().getMsgListeners().get(key);
                    for (int x = 0; messageListeners != null && x < messageListeners.size(); x++) {
                        messageListeners.get(x).messageSend(chatMessage);
                    }
                }
                if (key.equals(globalMsgTag)) {
                    List<MessageListener> messageListeners = HolderMessageSession.getInstance().getMsgListeners().get(key);
                    for (int x = 0; messageListeners != null && x < messageListeners.size(); x++) {
                        messageListeners.get(x).messageSend(chatMessage);
                    }
                }
            }
        }
        //消息更新
        if (message.what == MSG_UPDATE) {
            ChatMessage chatMessage = (ChatMessage) message.obj;
            for (String key : HolderMessageSession.getInstance().getMsgListeners().keySet()) {
                if (chatMessage.getMessageSession().equals(key)) {
                    List<MessageListener> messageListeners = HolderMessageSession.getInstance().getMsgListeners().get(key);
                    for (int x = 0; messageListeners != null && x < messageListeners.size(); x++) {
                        messageListeners.get(x).messageUpdate(chatMessage);
                    }
                }
                if (key.equals(globalMsgTag)) {
                    List<MessageListener> messageListeners = HolderMessageSession.getInstance().getMsgListeners().get(key);
                    for (int x = 0; messageListeners != null && x < messageListeners.size(); x++) {
                        messageListeners.get(x).messageUpdate(chatMessage);
                    }
                }
            }
        }
        //消息接收
        if (message.what == MSG_RECEIVE) {
            ChatMessage chatMessage = (ChatMessage) message.obj;
            for (String key : HolderMessageSession.getInstance().getMsgListeners().keySet()) {
                if (chatMessage.getMessageSession().equals(key)) {
                    List<MessageListener> messageListeners = HolderMessageSession.getInstance().getMsgListeners().get(key);
                    for (int x = 0; messageListeners != null && x < messageListeners.size(); x++) {
                        messageListeners.get(x).messageReceived(chatMessage);
                    }
                }
                if (key.equals(globalMsgTag)) {
                    List<MessageListener> messageListeners = HolderMessageSession.getInstance().getMsgListeners().get(key);
                    for (int x = 0; messageListeners != null && x < messageListeners.size(); x++) {
                        messageListeners.get(x).messageReceived(chatMessage);
                    }
                }
            }
        }
    }

}
