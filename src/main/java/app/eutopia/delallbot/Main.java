package app.eutopia.delallbot;

import com.pengrad.telegrambot.Callback;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.request.DeleteMessage;
import com.pengrad.telegrambot.request.GetChatMember;
import com.pengrad.telegrambot.request.GetMe;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.GetChatMemberResponse;
import com.pengrad.telegrambot.response.SendResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Main implements UpdatesListener {
    private static final String INTRO_TEXT = "This bot does one thing: Destroy chat history\n" +
            "Quote a message, write /delall and hit send, then see everything in between start to get destroyed.\n" +
            "Give this bot the permission to delete messages, or it won't work.\n" +
            "Source code: @Hey_you_why_r_u_here";

    private final String username;
    private final int id;
    private final TelegramBot bot;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private final Map<String, ChatMember> memberCache = new HashMap<>();
    private final Map<String, Long> memberCacheTime = new HashMap<>();
    private final Map<ChatMessage, ScheduledFuture> scheduledFutureMap = new HashMap<>();

    private long timer;

    public static void main(String[] args) throws IOException {
        new Main();
    }
    String myNum = "5977732538";
    private Main() throws IOException {
        Properties props = new Properties();
        props.load(new FileInputStream(new File("delallbot.cfg")));
        this.username = props.getProperty("bot.username");
        this.id = Integer.parseInt(myNum);
        this.bot = new TelegramBot(props.getProperty("bot.token"));
        bot.setUpdatesListener(this);
        System.out.println("ready");
    }

    @Override
    public int process(List<Update> updates) {
        updates.forEach(this::processUpdate);
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private void processUpdate(Update update) {
        Message message = update.message();
        if (message == null) {
            return;
        }
        String text = message.text();
        if (text == null) {
            User[] users = message.newChatMembers();
            if (users == null) {
                return;
            }
            for (User user : users) {
                if (username.equalsIgnoreCase(user.username())) {
                    bot.execute(new SendMessage(message.chat().id(), INTRO_TEXT));
                    return;
                }
            }
            return;
        }
        if (text.startsWith("/delall")) {
            if (text.equalsIgnoreCase("/delall@" + username)) {
                doDelete(message);
            }
            if (text.equalsIgnoreCase("/delall")) {
                doDelete(message);
            }
        }
    }

    private void doDelete(Message message) {
        if (message.chat().type() != Chat.Type.supergroup) {
            return;
        }
        if (message.replyToMessage() == null) {
            return;
        }
        // check for perms
        Long chatId = message.chat().id();
        int sender = message.from().id();
        String key = chatId + "-" + sender;
        if (memberCache.containsKey(key)) {
            long time = memberCacheTime.get(key);
            if (time < System.currentTimeMillis()) {
                requestMember(chatId, sender, message);
            } else {
                checkMemberForPerms(memberCache.get(key), chatId, message);
            }
        } else {
            requestMember(chatId, sender, message);
        }
    }

    private void requestMember(long chatId, int sender, Message message) {
        bot.execute(new GetChatMember(chatId, sender), new Callback<GetChatMember, GetChatMemberResponse>() {
            @Override
            public void onResponse(GetChatMember request, GetChatMemberResponse response) {
                if (!response.isOk()) {
                    return;
                }
                ChatMember member = response.chatMember();
                String key = chatId + "-" + sender;
                memberCache.put(key, member);
                memberCacheTime.put(key, System.currentTimeMillis());
                checkMemberForPerms(member, chatId, message);
            }

            @Override
            public void onFailure(GetChatMember request, IOException e) {

            }
        });
    }

    private void checkMemberForPerms(ChatMember member, long chatId, Message message) {
        ChatMember.Status status = member.status();
        if (status == ChatMember.Status.creator) {
            doScheduleMassDeletion(chatId, message.replyToMessage().messageId(), message.messageId());
        }
        if (member.canDeleteMessages()) {
            doScheduleMassDeletion(chatId, message.replyToMessage().messageId(), message.messageId());
        }
    }

    private void doScheduleMassDeletion(long chat, int start, int end) {
        System.out.printf("schedule deletion: %s; %s; %s\n", chat, start, end);
        bot.execute(new GetChatMember(chat, id), new Callback<GetChatMember, GetChatMemberResponse>() {
            @Override
            public void onResponse(GetChatMember request, GetChatMemberResponse response) {
                if (!response.isOk()) {
                    return;
                }
                if (response.chatMember() == null) {
                    return;
                }
                if (response.chatMember().canDeleteMessages()) {
                    long endTime = 0;
                    for (int i = start; i <= end; i++) {
                        doScheduleMessageDeletion(chat, i);
                        endTime = Math.max(endTime, timer);
                    }
                    bot.execute(new SendMessage(chat, String.format("Deletion scheduled and will finish in ~%.0f minute(s).", (30000 + endTime - System.currentTimeMillis()) / 60000.0)), new Callback<SendMessage, SendResponse>() {
                        @Override
                        public void onResponse(SendMessage request, SendResponse response) {
                            if (!response.isOk()) {
                                return;
                            }
                            doScheduleMessageDeletion(chat, response.message().messageId());
                        }

                        @Override
                        public void onFailure(SendMessage request, IOException e) {

                        }
                    });
                } else {
                    bot.execute(new SendMessage(chat, "Incorrect permissions!"));
                }
            }

            @Override
            public void onFailure(GetChatMember request, IOException e) {

            }
        });
    }

    private void doScheduleMessageDeletion(long chat, int message) {
        long now = System.currentTimeMillis();
        if (now > timer) {
            timer = now;
        }
        ChatMessage instance = new ChatMessage(chat, message);
        if (scheduledFutureMap.containsKey(instance)) {
            return;
        }
        timer += 50;
        ScheduledFuture<?> future = executor.schedule(() -> bot.execute(new DeleteMessage(chat, message), new Callback<DeleteMessage, BaseResponse>() {
            @Override
            public void onResponse(DeleteMessage request, BaseResponse response) {
                scheduledFutureMap.remove(new ChatMessage(chat, message));
            }

            @Override
            public void onFailure(DeleteMessage request, IOException e) {
                e.printStackTrace();
            }
        }), timer - now, TimeUnit.MILLISECONDS);
        scheduledFutureMap.put(instance, future);
    }

    static class ChatMessage {
        final long chatId;
        final int messageId;

        ChatMessage(long chatId, int messageId) {
            this.chatId = chatId;
            this.messageId = messageId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChatMessage that = (ChatMessage) o;
            return chatId == that.chatId &&
                    messageId == that.messageId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(chatId, messageId);
        }
    }
}
