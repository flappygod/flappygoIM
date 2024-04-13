package com.flappygo.flappyim.Handler;

import static com.flappygo.flappyim.Models.Request.Base.FlappyRequest.UPDATE_SESSION_MEMBER_DEL;
import static com.flappygo.flappyim.Models.Request.Base.FlappyRequest.UPDATE_SESSION_MEMBER_GET;
import static com.flappygo.flappyim.Models.Request.Base.FlappyRequest.UPDATE_SESSION_ALL;

import com.flappygo.flappyim.Models.Response.Base.FlappyResponse;
import com.flappygo.flappyim.Models.Request.Base.FlappyRequest;
import com.flappygo.flappyim.Callback.FlappySendCallback;
import com.flappygo.flappyim.Models.Server.ChatSession;
import com.flappygo.flappyim.Session.FlappySessionData;
import com.flappygo.flappyim.Models.Server.ChatMessage;
import com.flappygo.flappyim.Models.Server.ChatUser;
import com.flappygo.flappyim.Thread.NettyThreadListener;

import io.netty.channel.SimpleChannelInboundHandler;

import com.flappygo.flappyim.Models.Protoc.Flappy;
import com.flappygo.flappyim.Config.FlappyConfig;
import com.flappygo.flappyim.Tools.NettyAttrTool;
import com.flappygo.flappyim.DataBase.Database;
import com.flappygo.flappyim.Datas.DataManager;

import io.netty.handler.timeout.IdleStateEvent;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import com.flappygo.flappyim.Tools.StringTool;
import com.flappygo.flappyim.FlappyImService;

import io.netty.channel.ChannelFuture;

import java.util.Collections;
import java.math.BigDecimal;
import java.util.ArrayList;

import android.os.Message;

import java.util.HashMap;
import java.util.List;


//登录的handler
public class ChannelMsgHandler extends SimpleChannelInboundHandler<Flappy.FlappyResponse> {

    //登录的回调
    private HandlerLogin handlerLogin;

    //用户数据
    private final ChatUser user;

    //当前的channel
    private ChannelHandlerContext currentActiveContext;

    //心跳包
    private final Flappy.FlappyRequest heart;

    //回调
    private final NettyThreadListener deadCallback;

    //更新的sessions
    private final List<String> updateSessions = new ArrayList<>();

    //检查是否是active状态的
    public volatile boolean isActive = false;

    //回调
    public ChannelMsgHandler(HandlerLogin handler, NettyThreadListener deadCallback, ChatUser user) {
        //心跳
        this.heart = Flappy.FlappyRequest.newBuilder().setType(FlappyRequest.REQ_PING).build();
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
        //发送登录验证
        sendLoginRequest(ctx);
        super.channelActive(ctx);
    }

    //断开连接
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        closeChannel(context);
        super.channelInactive(context);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        sendHeartBeatRequest(ctx, evt);
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


    /******
     * 发送心跳并检查心跳
     * @param ctx ctx
     * @param evt evt
     */
    private void sendHeartBeatRequest(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            //发送心跳包
            ctx.writeAndFlush(heart).addListeners((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    NettyAttrTool.updateReaderTime(ctx.channel(), System.currentTimeMillis());
                }
            });
            //检查心跳，出现异常关闭socket
            Long formerTime = NettyAttrTool.getReaderTime(ctx.channel());
            if (formerTime != null && System.currentTimeMillis() - formerTime > 30 * 1000) {
                closeChannel(ctx);
            }
        }
    }

    /******
     * 发送登录请求
     * @param ctx ctx
     */
    private void sendLoginRequest(ChannelHandlerContext ctx) {
        //创建builder
        Flappy.ReqLogin.Builder loginInfoBuilder = Flappy.ReqLogin.newBuilder()
                .setDevice(FlappyConfig.getInstance().device)
                .setUserID(this.user.getUserId())
                .setPushId(StringTool.getDeviceIDNumber(FlappyImService.getInstance().getAppContext()));

        //设置最近的消息偏移量作为请求消息数据
        ChatUser user = DataManager.getInstance().getLoginUser();
        if (user != null && user.getLatest() != null) {
            loginInfoBuilder.setLatest(user.getLatest());
        }

        //创建登录请求消息
        Flappy.FlappyRequest.Builder builder = Flappy.FlappyRequest.newBuilder()
                .setLogin(loginInfoBuilder.build())
                .setType(FlappyRequest.REQ_LOGIN);

        //发送登录消息
        ctx.writeAndFlush(builder.build());
    }

    /******
     * 登录成功返回处理
     * @param ctx ctx
     * @param response 回复
     */
    private void receiveLogin(ChannelHandlerContext ctx, Flappy.FlappyResponse response) {

        //保存用户成功的登录信息
        synchronized (this) {

            //活跃状态设置为true
            isActive = true;

            //设置当前的context
            currentActiveContext = ctx;

            //设置登录成功，并保存
            user.setLogin(1);

            //保存数据
            DataManager.getInstance().saveLoginUser(user);

            //遍历消息进行通知
            Database database = Database.getInstance().open();
            if (handlerLogin.getLoginResponse().getSessions() != null &&
                    handlerLogin.getLoginResponse().getSessions().size() != 0) {
                database.insertSessions(handlerLogin.getLoginResponse().getSessions());
            }

            //消息转换为我们的message
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

            //处理消息
            for (int s = 0; s < messages.size(); s++) {
                ChatMessage chatMessage = messages.get(s);
                //通知接收成功或者发送成功
                ChatMessage former = database.getMessageByID(chatMessage.getMessageId(), true);
                //消息状态更改
                messageArrivedState(chatMessage, former);
                //插入消息
                database.insertMessage(chatMessage);
                //消息发送回调
                MessageNotifyManager.getInstance().messageSendSuccess(chatMessage);
                //通知监听变化
                MessageNotifyManager.getInstance().notifyMessageReceive(chatMessage, former);
                //通知监听变化
                MessageNotifyManager.getInstance().notifyMessageAction(chatMessage);
                //保存最后的offset
                if (s == (messages.size() - 1)) {
                    messageArrivedReceipt(ctx, chatMessage, former);
                }
            }

            //关闭数据库
            database.close();

            //登录成功
            handlerLogin.loginSuccess();

            //检查是否更新
            checkSessionNeedUpdate(ctx);

            //如果说之前有消息不是在active状态发送的，那么链接成功后就触发发送
            checkFormerMessagesToSend();
        }
    }

    /******
     * 收到新的消息
     * @param ctx ctx
     * @param response 回复
     */
    private void receiveMessage(ChannelHandlerContext ctx, Flappy.FlappyResponse response) {
        Database database = Database.getInstance().open();
        //设置
        for (int s = 0; s < response.getMsgCount(); s++) {
            //得到真正的消息对象
            ChatMessage chatMessage = new ChatMessage(response.getMsgList().get(s));
            //判断数据库是否存在
            ChatMessage former = database.getMessageByID(chatMessage.getMessageId(), true);
            //消息到达后的状态改变
            messageArrivedState(chatMessage, former);
            //插入消息
            database.insertMessage(chatMessage);
            //发送成功
            MessageNotifyManager.getInstance().messageSendSuccess(chatMessage);
            //新消息到达
            MessageNotifyManager.getInstance().notifyMessageReceive(chatMessage, former);
            //新消息到达
            MessageNotifyManager.getInstance().notifyMessageAction(chatMessage);
            //消息回执
            messageArrivedReceipt(ctx, chatMessage, former);
        }
        database.close();
        //检查会话是否需要更新
        checkSessionNeedUpdate(ctx);
    }

    /******
     * 收到会话更新的消息
     * @param ctx ctx
     * @param response 回复
     */
    private void receiveUpdate(ChannelHandlerContext ctx, Flappy.FlappyResponse response) {
        //进行会话更新
        List<Flappy.Session> session = response.getSessionsList();
        //设置
        Database database = Database.getInstance().open();
        //数据开始
        for (int s = 0; s < session.size(); s++) {
            //更新数据
            FlappySessionData data = new FlappySessionData(session.get(s));
            //插入数据
            database.insertSession(data, MessageNotifyManager.getInstance().getHandlerSession());
            //消息标记为已经处理
            List<ChatMessage> messages = database.getNotActionSystemMessageBySession(data.getSessionId());
            //将系统消息标记成为已经处理，不再需要重复处理
            for (ChatMessage message : messages) {
                //更新消息
                if (data.getSessionStamp().longValue() >= StringTool.strToDecimal(message.getChatSystem().getSysTime()).longValue()) {
                    //设置阅读状态
                    message.setMessageReadState(new BigDecimal(1));
                    //插入消息
                    database.insertMessage(message);
                }
            }
            //移除正在更新
            updateSessions.remove(data.getSessionId());
        }
        database.close();
    }

    /******
     * 用户下线
     * @param context 上下文
     */
    private void closeChannel(ChannelHandlerContext context) {

        //活跃状态设置为true
        isActive = false;

        //消息通道关闭，回调
        MessageNotifyManager.getInstance().messageSendFailureAll();

        //如果这里还没有回调成功，那么就是登录失败
        if (this.handlerLogin != null) {
            //创建消息
            Message message = new Message();
            //失败
            message.what = HandlerLogin.LOGIN_FAILURE;
            //错误
            message.obj = new Exception("channel closed");
            //发送消息
            this.handlerLogin.sendMessage(message);
            //清空引用
            this.handlerLogin = null;
        }
        //线程非正常退出
        if (deadCallback != null) {
            deadCallback.disconnected();
        }
        //关闭与服务器的连接
        context.close();
    }

    /******
     * 检查会话是否需要更新
     * @param ctx ctx
     */
    private void checkSessionNeedUpdate(ChannelHandlerContext ctx) {

        //获取所有数据
        Database database = Database.getInstance().open();
        //获取系统消息没有被执行的
        List<ChatMessage> latestMessages = database.getNotActionSystemMessage();
        //开始处理
        database.close();

        //区分更新
        List<ChatMessage> actionUpdateSessionAll = new ArrayList<>();
        List<ChatMessage> actionUpdateSessionMember = new ArrayList<>();
        List<ChatMessage> actionUpdateSessionMemberDel = new ArrayList<>();
        for (ChatMessage item : latestMessages) {
            if (item.getChatSystem().getSysAction() == UPDATE_SESSION_ALL) {
                actionUpdateSessionAll.add(item);
            }
            if (item.getChatSystem().getSysAction() == UPDATE_SESSION_MEMBER_GET) {
                actionUpdateSessionMember.add(item);
            }
            if (item.getChatSystem().getSysAction() == UPDATE_SESSION_MEMBER_DEL) {
                actionUpdateSessionMemberDel.add(item);
            }
        }
        //全量更新
        if (!actionUpdateSessionAll.isEmpty()) {
            updateSessionAll(ctx, actionUpdateSessionAll);
        }
        //用户更新
        if (!actionUpdateSessionMember.isEmpty()) {
            updateSessionMember(ctx, actionUpdateSessionMember);
        }
        //用户更新
        if (!actionUpdateSessionMemberDel.isEmpty()) {
            updateSessionMemberDel(actionUpdateSessionMemberDel);
        }
    }


    //更新所有会话
    private void updateSessionAll(ChannelHandlerContext ctx, List<ChatMessage> messages) {
        List<String> updateSessions = new ArrayList<>();
        for (int s = 0; s < messages.size(); s++) {
            if (!updateSessions.contains(messages.get(s).getMessageSession())) {
                updateSessions.add(messages.get(s).getMessageSession());
            }
        }
        //遍历
        for (String key : updateSessions) {
            //包含
            if (!updateSessions.contains(key)) {
                //添加sessions
                updateSessions.add(key);
                //更新消息
                Flappy.ReqUpdate reqUpdate = Flappy.ReqUpdate.newBuilder()
                        .setUpdateID(key)
                        .setUpdateType(UPDATE_SESSION_ALL)
                        .build();

                //创建登录请求消息
                Flappy.FlappyRequest.Builder builder = Flappy.FlappyRequest.newBuilder()
                        .setUpdate(reqUpdate)
                        .setType(FlappyRequest.REQ_UPDATE);

                //发送需要更新的消息
                ctx.writeAndFlush(builder.build());
            }
        }
    }

    //更新用户数据
    private void updateSessionMember(ChannelHandlerContext ctx, List<ChatMessage> messages) {
        List<String> updateIds = new ArrayList<>();
        for (int s = 0; s < messages.size(); s++) {
            if (!updateIds.contains(messages.get(s).getMessageSession())) {
                String item = messages.get(s).getMessageSession() + "," + messages.get(s).getChatSystem().getSysData();
                updateIds.add(item);
            }
        }
        //遍历
        for (String key : updateIds) {
            //包含
            if (!updateIds.contains(key)) {
                //添加sessions
                updateIds.add(key);
                //更新消息
                Flappy.ReqUpdate reqUpdate = Flappy.ReqUpdate.newBuilder()
                        .setUpdateID(key)
                        .setUpdateType(UPDATE_SESSION_MEMBER_GET)
                        .build();

                //创建登录请求消息
                Flappy.FlappyRequest.Builder builder = Flappy.FlappyRequest.newBuilder()
                        .setUpdate(reqUpdate)
                        .setType(FlappyRequest.REQ_UPDATE);

                //发送需要更新的消息
                ctx.writeAndFlush(builder.build());
            }
        }
    }

    /******
     * 删除用户
     * @param messages 消息
     */
    private void updateSessionMemberDel(List<ChatMessage> messages) {
        //数据库开启
        Database database = Database.getInstance().open();
        //遍历消息
        for (ChatMessage item : messages) {
            //找到会话
            FlappySessionData session = database.getUserSessionByID(item.getMessageSession());

            //找到用户并移除
            List<ChatUser> users = session.getUsers();
            for (int s = 0; s < users.size(); s++) {
                if (users.get(s).getUserId().equals(item.getChatSystem().getSysData())) {
                    users.remove(s);
                    s--;
                }
            }

            //会话更新
            database.insertSession(session, MessageNotifyManager.getInstance().getHandlerSession());
        }
        //关闭数据库
        database.close();
    }


    /******
     * 修改收到的状态
     * @param msg    消息
     * @param former 之前的消息
     */
    private void messageArrivedState(ChatMessage msg, ChatMessage former) {
        ChatUser chatUser = DataManager.getInstance().getLoginUser();
        //如果是自己发送的，代表发送成功
        if (chatUser.getUserId().equals(msg.getMessageSendId())) {
            msg.setMessageSendState(new BigDecimal(ChatMessage.SEND_STATE_SENT));
        }
        //如果是别人发送的，代表到达目的设备
        else {
            msg.setMessageSendState(new BigDecimal(ChatMessage.SEND_STATE_REACHED));
        }
        //保留之前的已读状态
        if (former != null) {
            msg.setMessageReadState(former.getMessageReadState());
        }
    }

    /******
     * 消息已经到达
     * @param cxt  上下文
     * @param chatMessage 消息
     * @param former 之前存在的同一个消息
     */
    private void messageArrivedReceipt(ChannelHandlerContext cxt, ChatMessage chatMessage, ChatMessage former) {

        //保存最近一条的偏移量
        ChatUser user = DataManager.getInstance().getLoginUser();
        //最近一条为空
        if (user.getLatest() == null) {
            user.setLatest(StringTool.decimalToStr(chatMessage.getMessageTableSeq()));
        }
        //设置最大的那个值
        else {
            user.setLatest(Long.toString(Math.max(chatMessage.getMessageTableSeq().longValue(), StringTool.strToLong(user.getLatest()))));
        }
        //保存用户信息
        DataManager.getInstance().saveLoginUser(user);

        //不是自己，而且确实是最新的消息
        if (!chatMessage.getMessageSendId().equals(DataManager.getInstance().getLoginUser().getUserId()) && former == null) {

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
            cxt.writeAndFlush(builder.build());

        }
    }

    /******
     * 检查旧消息进行发送
     */
    private void checkFormerMessagesToSend() {
        List<ChatMessage> formerMessageList = MessageNotifyManager.getInstance().getAllUnSendMessages();
        for (ChatMessage message : formerMessageList) {
            sendMessageIfActive(message);
        }
    }

    /******
     * 发送消息
     * @param chatMessage  消息
     * @param callback     发送消息的回调
     */
    public void sendMessage(final ChatMessage chatMessage, final FlappySendCallback<ChatMessage> callback) {

        //多次发送，之前的发送全部直接给他失败
        MessageNotifyManager.getInstance().messageSendFailure(
                chatMessage,
                new Exception("消息重新发送")
        );

        //添加进入到消息发送请求
        MessageNotifyManager.getInstance().addHandlerSendCall(
                chatMessage.getMessageId(),
                new HandlerSendCall(callback, chatMessage)
        );

        //如果当前的Handler处于存货状态，则进行发送
        this.sendMessageIfActive(chatMessage);
    }


    /******
     * 发送消息
     * @param chatMessage 消息
     */
    public void sendMessageIfActive(final ChatMessage chatMessage) {
        try {
            synchronized (this) {
                if (!isActive) {
                    return;
                }
                Flappy.Message message = chatMessage.toProtocMessage(Flappy.Message.newBuilder());
                Flappy.FlappyRequest.Builder builder = Flappy.FlappyRequest.newBuilder()
                        .setMsg(message)
                        .setType(FlappyRequest.REQ_MSG);
                ChannelFuture future = currentActiveContext.writeAndFlush(builder.build());
                future.addListener((ChannelFutureListener) channelFuture -> {
                    if (!channelFuture.isSuccess()) {
                        MessageNotifyManager.getInstance().messageSendFailure(
                                chatMessage,
                                new Exception("连接已经断开")
                        );
                    }
                });
            }
        } catch (Exception ex) {
            MessageNotifyManager.getInstance().messageSendFailure(chatMessage, ex);
        }
    }


}