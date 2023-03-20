package com.flappygo.flappyim.Handler;

import android.os.Message;

import com.flappygo.flappyim.Callback.FlappySendCallback;
import com.flappygo.flappyim.Config.FlappyConfig;
import com.flappygo.flappyim.DataBase.Database;
import com.flappygo.flappyim.Datas.DataManager;
import com.flappygo.flappyim.FlappyImService;
import com.flappygo.flappyim.Models.Protoc.Flappy;
import com.flappygo.flappyim.Models.Request.Base.FlappyRequest;
import com.flappygo.flappyim.Models.Response.Base.FlappyResponse;
import com.flappygo.flappyim.Models.Response.SessionData;
import com.flappygo.flappyim.Models.Server.ChatMessage;
import com.flappygo.flappyim.Models.Server.ChatUser;
import com.flappygo.flappyim.Thread.NettyThreadDead;
import com.flappygo.flappyim.Tools.NettyAttrUtil;
import com.flappygo.flappyim.Tools.StringTool;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;


//登录的handler
public class ChannelMsgHandler extends SimpleChannelInboundHandler<Flappy.FlappyResponse> {

    //回调
    private final ConcurrentHashMap<String, HandlerSendCall> sendHandlers = new ConcurrentHashMap<>();

    //登录的回调
    private HandlerLoginCallback handlerLogin;

    //消息的handler
    private final HandlerMessage handlerMessage;

    //会话的handler
    private final HandlerSession handlerSession;

    //用户数据
    private final ChatUser user;

    //当前的channel
    private ChannelHandlerContext channelHandlerContext;

    //心跳包
    private final Flappy.FlappyRequest heart;

    //回调
    private final NettyThreadDead deadCallback;

    //用于加锁
    private final Byte[] lock = new Byte[1];

    //更新的sessions
    private final List<String> updateSessions = new ArrayList<>();

    //回调
    public ChannelMsgHandler(HandlerLoginCallback handler, NettyThreadDead deadCallback, ChatUser user) {
        //心跳
        this.heart = Flappy.FlappyRequest.newBuilder().setType(FlappyRequest.REQ_PING).build();
        //消息接收监听
        this.handlerMessage = new HandlerMessage();
        //会话的handler
        this.handlerSession = new HandlerSession();
        //handler
        this.handlerLogin = handler;
        //回调
        this.deadCallback = deadCallback;
        //用户数据
        this.user = user;
    }


    //三次握手成功,发送登录验证
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {

        //创建builder
        Flappy.ReqLogin.Builder loginInfoBuilder = Flappy.ReqLogin.newBuilder()
                .setDevice(FlappyConfig.getInstance().device)
                .setUserID(this.user.getUserId())
                .setPushid(StringTool.getDeviceUnicNumber(FlappyImService.getInstance().getAppContext()));

        //获取最近latest
        ChatUser user = DataManager.getInstance().getLoginUser();
        //消息
        if (user != null && user.getLatest() != null) {
            loginInfoBuilder.setLatest(user.getLatest());
        }
        //登录信息创建
        Flappy.ReqLogin loginInfo = loginInfoBuilder.build();

        //创建登录请求消息
        Flappy.FlappyRequest.Builder builder = Flappy.FlappyRequest.newBuilder()
                .setLogin(loginInfo)
                .setType(FlappyRequest.REQ_LOGIN);

        //发送登录消息
        ctx.writeAndFlush(builder.build());
        //context
        channelHandlerContext = ctx;
        //消息
        super.channelActive(ctx);
    }

    //断开连接
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        //下线
        closeChannel(context);
        super.channelInactive(context);
    }

    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            System.out.println("heat beat");
            //发送心跳包用于检测
            ctx.writeAndFlush(heart).addListeners((ChannelFutureListener) future -> {
                //如果发送给服务器是成功的
                if (future.isSuccess()) {
                    //更新时间
                    NettyAttrUtil.updateReaderTime(ctx.channel(), System.currentTimeMillis());
                }
            });
            Long formerTime = NettyAttrUtil.getReaderTime(ctx.channel());
            //30秒没有收到信息了，关闭吧
            if (formerTime != null && System.currentTimeMillis() - formerTime > 30 * 1000) {
                //连接不通，下线
                closeChannel(ctx);
            }
        }
        super.userEventTriggered(ctx, evt);
    }


    @Override
    protected void messageReceived(ChannelHandlerContext ctx, Flappy.FlappyResponse response) {
        //登录成功,现在才代表真正的登录成功
        if (response.getType() == FlappyResponse.RES_LOGIN) {
            receiveLogin(ctx, response);
        }
        //发送消息
        else if (response.getType() == FlappyResponse.RES_MSG) {
            receiveMessage(ctx, response);
        }
        //更新数据
        else if (response.getType() == FlappyResponse.RES_UPDATE) {
            receiveUpdate(ctx, response);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        closeChannel(ctx);
        super.exceptionCaught(ctx, cause);
    }

    //登录成功返回处理
    private void receiveLogin(ChannelHandlerContext ctx, Flappy.FlappyResponse response) {
        //登录
        if (this.handlerLogin == null) {
            return;
        }

        //保存用户成功的登录信息
        synchronized (this) {
            user.setLogin(1);
            DataManager.getInstance().saveLoginUser(user);

            //消息转发
            List<ChatMessage> messages = new ArrayList<>();
            for (int s = 0; s < response.getMsgCount(); s++) {
                ChatMessage chatMessage = new ChatMessage(response.getMsgList().get(s));
                messages.add(chatMessage);
            }

            //对消息进行排序，然后在插入数据库
            Collections.sort(messages, (chatMessage, t1) -> {
                if (chatMessage.getMessageTableSeq().intValue() > t1.getMessageTableSeq().intValue()) {
                    return 1;
                } else if (chatMessage.getMessageTableSeq().intValue() < t1.getMessageTableSeq().intValue()) {
                    return -1;
                }
                return 0;
            });

            //遍历消息进行通知
            Database database = new Database();
            for (int s = 0; s < messages.size(); s++) {
                ChatMessage chatMessage = messages.get(s);
                messageArrivedState(chatMessage);
                messageSendSuccess(chatMessage);
                //通知接收成功或者发送成功
                ChatMessage former = database.getMessageByID(chatMessage.getMessageId());
                Message msg = new Message();
                if (former == null) {
                    msg.what = HandlerMessage.MSG_RECEIVE;
                } else {
                    chatMessage.setMessageReadState(former.getMessageReadState());
                    msg.what = HandlerMessage.MSG_UPDATE;
                }
                msg.obj = chatMessage;
                this.handlerMessage.sendMessage(msg);
            }

            //所有消息更新状态等
            if (messages.size() != 0) {
                database.insertMessages(messages);
            }

            //保存最后的offset
            if (messages.size() > 0) {
                ChatUser user = DataManager.getInstance().getLoginUser();
                user.setLatest(StringTool.decimalToStr(messages.get(messages.size() - 1).getMessageTableSeq()));
                DataManager.getInstance().saveLoginUser(user);
                messageArrivedReceipt(messages.get(messages.size() - 1));
            }

            //更新所有会话
            if (handlerLogin.getLoginResponse().getSessions() != null && handlerLogin.getLoginResponse().getSessions().size() != 0) {
                database.insertSessions(handlerLogin.getLoginResponse().getSessions(), handlerSession);
            }

            //关闭数据库
            database.close();

            //发送成功消息
            Message msg = handlerLogin.obtainMessage(HandlerLoginCallback.LOGIN_SUCCESS);
            msg.obj = response.getMsgList();
            handlerLogin.sendMessage(msg);

            //检查是否更新
            checkSessionNeedUpdate();
        }
    }

    //收到新的消息
    private void receiveMessage(ChannelHandlerContext ctx, Flappy.FlappyResponse response) {
        Database database = new Database();
        //设置
        for (int s = 0; s < response.getMsgCount(); s++) {
            //得到真正的消息对象
            ChatMessage chatMessage = new ChatMessage(response.getMsgList().get(s));
            messageSendSuccess(chatMessage);
            messageArrivedState(chatMessage);

            //判断数据库是否存在
            ChatMessage former = database.getMessageByID(chatMessage.getMessageId());

            //更新最近一条信息
            ChatUser user = DataManager.getInstance().getLoginUser();
            user.setLatest(StringTool.decimalToStr(chatMessage.getMessageTableSeq()));
            DataManager.getInstance().saveLoginUser(user);

            //插入成功
            Message msg = new Message();

            //接收成功，插入消息，返回回执
            if (former == null) {
                database.insertMessage(chatMessage);
                msg.what = HandlerMessage.MSG_RECEIVE;
                msg.obj = chatMessage;
                this.handlerMessage.sendMessage(msg);
                messageArrivedReceipt(chatMessage);
            }
            //更新消息
            else {
                chatMessage.setMessageReadState(former.getMessageReadState());
                database.insertMessage(chatMessage);
                msg.what = HandlerMessage.MSG_UPDATE;
                msg.obj = chatMessage;
                this.handlerMessage.sendMessage(msg);
            }
        }
        database.close();
        //检查会话是否需要更新
        checkSessionNeedUpdate();
    }


    //收到会话更新的消息
    private void receiveUpdate(ChannelHandlerContext ctx, Flappy.FlappyResponse response) {
        //进行会话更新
        List<Flappy.Session> session = response.getSessionsList();
        //设置
        Database database = new Database();
        //数据开始
        for (int s = 0; s < session.size(); s++) {
            //更新数据
            SessionData data = new SessionData(session.get(s));
            //插入数据
            database.insertSession(data, handlerSession);
            //消息标记为已经处理
            List<ChatMessage> messages = database.getNotActionSystemMessage(data.getSessionId());
            //将系统消息标记成为已经处理，不再需要重复处理
            for (ChatMessage message : messages) {
                //更新消息
                if (data.getSessionStamp().longValue() >= StringTool.strToDecimal(message.getChatSystem().getSysTime()).longValue()) {
                    message.setMessageReadState(new BigDecimal(1));
                    database.insertMessage(message);
                }
            }
            //移除正在更新
            updateSessions.remove(data.getSessionId());
        }
        database.close();
    }

    //用户下线
    private void closeChannel(ChannelHandlerContext context) {

        synchronized (lock) {
            for (String key : sendHandlers.keySet()) {
                //获取
                HandlerSendCall call = sendHandlers.get(key);
                //不为空
                if (call != null) {
                    //发送失败的消息
                    Message message = call.obtainMessage(HandlerSendCall.SEND_FAILURE);
                    message.obj = new Exception("消息通道已关闭");
                    call.sendMessage(message);
                    //移除这个消息
                    sendHandlers.remove(call.getChatMessage().getMessageId());
                }
            }
            //清空
            sendHandlers.clear();
        }

        //如果这里还没有回调成功，那么就是登录失败
        if (this.handlerLogin != null) {
            //创建消息
            Message message = new Message();
            //失败
            message.what = HandlerLoginCallback.LOGIN_FAILURE;
            //错误
            message.obj = new Exception("channel closed");
            //发送消息
            this.handlerLogin.sendMessage(message);
            //清空引用
            this.handlerLogin = null;
        }
        //线程非正常退出
        if (deadCallback != null) {
            //死亡
            deadCallback.dead();
        }
        //关闭与服务器的连接
        context.close();
    }

    //检查会话是否需要更新
    private void checkSessionNeedUpdate() {

        //获取所有数据
        Database database = new Database();
        //获取系统消息
        List<ChatMessage> latestMessages = database.getNotActionSystemMessage();
        //开始处理
        database.close();

        HashMap<String, String> needUpdate = new HashMap<>();
        //遍历
        for (int s = 0; s < latestMessages.size(); s++) {
            String former = needUpdate.get(latestMessages.get(s).getMessageSession());
            if (former == null) {
                needUpdate.put(latestMessages.get(s).getMessageSession(), latestMessages.get(s).getChatSystem().getSysTime());
            } else {
                //获取会话
                long stamp = StringTool.strToDecimal(former).longValue();
                long newStamp = StringTool.strToDecimal(latestMessages.get(s).getChatSystem().getSysTime()).longValue();
                if (newStamp > stamp) {
                    needUpdate.put(latestMessages.get(s).getMessageSession(), latestMessages.get(s).getChatSystem().getSysTime());
                }
            }
        }
        //遍历
        for (String key : needUpdate.keySet()) {
            //包含
            if (!updateSessions.contains(key)) {
                //添加sessions
                updateSessions.add(key);
                //更新消息
                Flappy.ReqUpdate reqUpdate = Flappy.ReqUpdate.newBuilder()
                        .setUpdateID(key)
                        .setUpdateType(FlappyRequest.UPDATE_SESSION_SINGLE)
                        .build();

                //创建登录请求消息
                Flappy.FlappyRequest.Builder builder = Flappy.FlappyRequest.newBuilder()
                        .setUpdate(reqUpdate)
                        .setType(FlappyRequest.REQ_UPDATE);

                //发送需要更新的消息
                channelHandlerContext.writeAndFlush(builder.build());
            }
        }
    }


    //修改收到的状态
    private void messageArrivedState(ChatMessage msg) {
        ChatUser chatUser = DataManager.getInstance().getLoginUser();
        if (chatUser.getUserId().equals(msg.getMessageSendId())) {
            msg.setMessageSendState(new BigDecimal(ChatMessage.SEND_STATE_SENT));
        } else {
            msg.setMessageSendState(new BigDecimal(ChatMessage.SEND_STATE_REACHED));
        }
    }

    //消息已经到达
    private void messageArrivedReceipt(ChatMessage chatMessage) {
        //返回
        if (!chatMessage.getMessageSendId().equals(DataManager.getInstance().getLoginUser().getUserId())) {

            //创建消息到达的回执
            Flappy.ReqReceipt receipt = Flappy.ReqReceipt.newBuilder()
                    .setReceiptID(chatMessage.getMessageTableSeq().toString())
                    .setReceiptType(FlappyRequest.RECEIPT_MSG_ARRIVE)
                    .build();

            //创建回执消息
            Flappy.FlappyRequest.Builder builder = Flappy.FlappyRequest.newBuilder()
                    .setReceipt(receipt)
                    .setType(FlappyRequest.REQ_RECEIPT);

            //发送回执，发送回执后，所有之前的消息都会被列为已经收到，因为端口是阻塞的
            channelHandlerContext.writeAndFlush(builder.build());
        }
    }

    //发送消息
    public void sendMessage(final ChatMessage chatMessage, final FlappySendCallback<ChatMessage> callback) {


        //消息被创建进行发送
        Message msg = new Message();
        msg.what = HandlerMessage.MSG_CREATE;
        msg.obj = chatMessage;
        this.handlerMessage.sendMessage(msg);


        //之前是否存在
        HandlerSendCall former = getHandlerSendCall(chatMessage.getMessageId());
        if (former != null) {
            messageSendFailure(chatMessage, new Exception("消息重新发送"));
        }
        //发送
        try {
            HandlerSendCall handlerSendCall = new HandlerSendCall(callback, chatMessage);
            addHandlerSendCall(chatMessage.getMessageId(), handlerSendCall);
            Flappy.Message message = chatMessage.toProtocMessage(Flappy.Message.newBuilder());
            Flappy.FlappyRequest.Builder builder = Flappy.FlappyRequest.newBuilder()
                    .setMsg(message)
                    .setType(FlappyRequest.REQ_MSG);
            ChannelFuture future = channelHandlerContext.channel().writeAndFlush(builder.build());
            future.addListener((ChannelFutureListener) channelFuture -> {
                if (!channelFuture.isSuccess()) {
                    messageSendFailure(chatMessage, new Exception("连接已经断开"));
                }
            });
        } catch (Exception ex) {
            messageSendFailure(chatMessage, ex);
        }
    }


    //获取某个回调
    private synchronized HandlerSendCall getHandlerSendCall(String messageID) {
        synchronized (lock) {
            return sendHandlers.get(messageID);
        }
    }

    //添加回调
    private void addHandlerSendCall(String messageID, HandlerSendCall call) {
        synchronized (lock) {
            sendHandlers.put(messageID, call);
        }
    }

    //发送成功
    private synchronized void messageSendSuccess(ChatMessage chatMessage) {
        synchronized (lock) {
            HandlerSendCall call = sendHandlers.get(chatMessage.getMessageId());
            if (call != null) {
                Message message = call.obtainMessage(HandlerSendCall.SEND_SUCCESS);
                message.obj = chatMessage;
                call.sendMessage(message);
                sendHandlers.remove(chatMessage.getMessageId());
            }
        }
    }

    //发送失败
    private void messageSendFailure(ChatMessage chatMessage, Exception ex) {
        synchronized (lock) {
            HandlerSendCall call = sendHandlers.get(chatMessage.getMessageId());
            if (call != null) {
                Message message = call.obtainMessage(HandlerSendCall.SEND_FAILURE);
                message.obj = ex;
                call.sendMessage(message);
                sendHandlers.remove(chatMessage.getMessageId());
            }
        }
    }


}