package com.example.igbot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Document;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.GetFile;
import com.pengrad.telegrambot.request.SendDocument;
import com.pengrad.telegrambot.request.SendMessage;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.example.igbot.selenium.CookieLoader;
import com.example.igbot.selenium.InstagramScraper;
import com.example.igbot.selenium.InstagramAutoLogin;
import org.openqa.selenium.Cookie;
import com.pengrad.telegrambot.request.DeleteMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TelegramBotService {
    private final TelegramBot bot;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static class Session {
        Set<String> followers; // usernames
        Set<String> following; // usernames
        Instant started = Instant.now();
        Stage stage = Stage.IDLE;
        String scrapeUsername;
        // Auto-login state (in-memory only)
        String loginUsername;
        Integer usernameMsgId;
        Integer passwordMsgId;
        InstagramAutoLogin.Handle pendingLogin;
        Set<Cookie> authCookies;
    }

    private enum Stage { IDLE, WAIT_FOLLOWERS, WAIT_FOLLOWING, WAIT_COOKIES, WAIT_LOGIN_USERNAME, WAIT_LOGIN_PASSWORD, AWAIT_2FA }

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    public TelegramBotService(String token) {
        this.bot = new TelegramBot(token);
    }

    public void start() {
        bot.setUpdatesListener(updates -> {
            for (Update u : updates) handleUpdate(u);
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
        System.out.println("Bot started");
    }

    public void shutdown() {
        try { bot.removeGetUpdatesListener(); } catch (Exception ignored) {}
        System.out.println("Bot stopped");
    }

    private void handleUpdate(Update update) {
        Message msg = update.message();
        if (msg == null) return;
        Long chatId = msg.chat().id();
        String text = msg.text();

        if (text != null && text.startsWith("/start")) {
            sessions.put(chatId, new Session());
            bot.execute(new SendMessage(chatId, startText()));
            return;
        }
        if (text != null && text.startsWith("/help")) {
            bot.execute(new SendMessage(chatId, helpText()));
            return;
        }
        if (text != null && text.startsWith("/login")) {
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            s.stage = Stage.WAIT_LOGIN_USERNAME;
            s.loginUsername = null;
            s.pendingLogin = null;
            s.authCookies = null;
            bot.execute(new SendMessage(chatId, "Введи username аккаунта Instagram. Затем введи пароль. Эти сообщения будут удалены."));
            return;
        }
        if (text != null && text.startsWith("/2fa")) {
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            if (s.stage != Stage.AWAIT_2FA || s.pendingLogin == null) {
                bot.execute(new SendMessage(chatId, "Сейчас 2FA не ожидается. Сначала выполни /login."));
                return;
            }
            String[] parts = text.trim().split("\\s+", 2);
            if (parts.length < 2) {
                bot.execute(new SendMessage(chatId, "Использование: /2fa <код>"));
                return;
            }
            String code = parts[1].trim();
            try {
                Set<Cookie> cookies = InstagramAutoLogin.submit2FA(s.pendingLogin, code);
                s.authCookies = cookies;
                s.pendingLogin = null;
                s.stage = Stage.IDLE;
                bot.execute(new SendMessage(chatId, "2FA пройден. Можно запускать /scrape <username>."));
            } catch (Exception e) {
                s.pendingLogin = null;
                s.stage = Stage.IDLE;
                bot.execute(new SendMessage(chatId, "Ошибка 2FA: " + (e.getMessage()==null? e.toString(): e.getMessage())));
            }
            return;
        }
        if (text != null && text.startsWith("/scrape")) {
            String[] parts = text.trim().split("\\s+");
            if (parts.length < 2) {
                bot.execute(new SendMessage(chatId, "Использование: /scrape <username>\nЗатем пришли файл cookies (Netscape или строки вида name=value), полученные из браузера для domain instagram.com."));
                return;
            }
            String username = normalizeUsername(parts[1]);
            if (username.isEmpty()) {
                bot.execute(new SendMessage(chatId, "Некорректный username."));
                return;
            }
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            s.scrapeUsername = username;
            if (s.authCookies != null && !s.authCookies.isEmpty()) {
                runScrape(chatId, s, s.authCookies);
            } else {
                s.stage = Stage.WAIT_COOKIES;
                bot.execute(new SendMessage(chatId, "Пришли файл cookies для instagram.com ИЛИ сначала выполни /login. Формат: Netscape Cookie File или строки \nname=value\n...\nВнимание: использование скрейпинга может нарушать правила Instagram."));
            }
            return;
        }
        if (text != null && text.startsWith("/check")) {
            Session s = sessions.computeIfAbsent(chatId, k -> new Session());
            s.stage = Stage.WAIT_FOLLOWERS;
            s.followers = null;
            s.following = null;
            bot.execute(new SendMessage(chatId, "Отправь файл со списком подписчиков (followers) — по одному username в строке. Затем отправь файл с подписками (following)."));
            return;
        }

        // If user sends document/file
        if (msg.document() != null) {
            handleDocument(chatId, msg.document());
            return;
        }

        // Accept plain text lists when waiting
        Session s = sessions.get(chatId);
        if (s != null && (s.stage == Stage.WAIT_FOLLOWERS || s.stage == Stage.WAIT_FOLLOWING) && text != null) {
            Set<String> usernames = parseUsernames(text.getBytes(StandardCharsets.UTF_8));
            applyListAndMaybeCompute(chatId, usernames);
            return;
        }
        if (s != null && s.stage == Stage.WAIT_LOGIN_USERNAME && text != null) {
            s.loginUsername = normalizeUsername(text.trim());
            s.usernameMsgId = msg.messageId();
            s.stage = Stage.WAIT_LOGIN_PASSWORD;
            bot.execute(new SendMessage(chatId, "Теперь введи пароль (сообщение будет удалено)."));
            return;
        }
        if (s != null && s.stage == Stage.WAIT_LOGIN_PASSWORD && text != null) {
            String password = text; // не сохраняем в полях
            s.passwordMsgId = msg.messageId();
            if (s.loginUsername == null || s.loginUsername.isBlank()) {
                bot.execute(new SendMessage(chatId, "Сначала введи username."));
                return;
            }
            // Пытаемся удалить сообщения с логином/паролем
            try { if (s.usernameMsgId != null) bot.execute(new DeleteMessage(chatId, s.usernameMsgId)); } catch (Exception ignored) {}
            try { if (s.passwordMsgId != null) bot.execute(new DeleteMessage(chatId, s.passwordMsgId)); } catch (Exception ignored) {}
            bot.execute(new SendMessage(chatId, "Выполняю вход, возможно потребуется 2FA."));
            try {
                InstagramAutoLogin.Result res = InstagramAutoLogin.startLogin(s.loginUsername, password);
                if (res.handle != null) {
                    s.pendingLogin = res.handle;
                    s.stage = Stage.AWAIT_2FA;
                    bot.execute(new SendMessage(chatId, "Введите код 2FA командой: /2fa 123456"));
                } else {
                    s.authCookies = res.cookies;
                    s.pendingLogin = null;
                    s.stage = Stage.IDLE;
                    bot.execute(new SendMessage(chatId, "Логин успешен. Теперь можно выполнять /scrape <username>."));
                }
            } catch (Exception e) {
                s.pendingLogin = null;
                s.stage = Stage.IDLE;
                bot.execute(new SendMessage(chatId, "Не удалось войти: " + (e.getMessage()==null? e.toString(): e.getMessage())));
            }
            return;
        }

        // default
        bot.execute(new SendMessage(chatId, "Не понял. Введи /check и следуй инструкции. /help для справки."));
    }

    private void handleDocument(Long chatId, Document doc) {
        Session s = sessions.computeIfAbsent(chatId, k -> new Session());
        if (s.stage == Stage.IDLE) {
            bot.execute(new SendMessage(chatId, "Сначала введи /check или /scrape, чтобы начать сессию."));
            return;
        }
        try {
            byte[] bytes = downloadTelegramFile(doc.fileId());
            if (s.stage == Stage.WAIT_COOKIES) {
                Set<Cookie> cookies = CookieLoader.parse(bytes);
                if (cookies.isEmpty()) {
                    bot.execute(new SendMessage(chatId, "Не удалось прочитать cookies. Убедись в корректном формате."));
                    return;
                }
                runScrape(chatId, s, cookies);
                return;
            }
            Set<String> usernames = parseUsernames(bytes);
            applyListAndMaybeCompute(chatId, usernames);
        } catch (Exception e) {
            bot.execute(new SendMessage(chatId, "Не удалось обработать файл: " + e.getMessage()));
        }
    }

    private void runScrape(Long chatId, Session s, Set<Cookie> cookies) {
        if (s.scrapeUsername == null || s.scrapeUsername.isBlank()) {
            bot.execute(new SendMessage(chatId, "Сначала укажи username: /scrape <username>."));
            return;
        }
        bot.execute(new SendMessage(chatId, "Начинаю сбор followers/following для @" + s.scrapeUsername + ". Это может занять несколько минут."));
        try {
            InstagramScraper.Pair pair = InstagramScraper.fetchAll(s.scrapeUsername, cookies);
            s.followers = pair.followers;
            s.following = pair.following;
            s.stage = Stage.IDLE;
            computeAndRespond(chatId, s);
        } catch (Exception ex) {
            bot.execute(new SendMessage(chatId, "Ошибка скрейпинга: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage())));
        }
    }

    private void applyListAndMaybeCompute(Long chatId, Set<String> usernames) {
        Session s = sessions.get(chatId);
        if (s == null) {
            bot.execute(new SendMessage(chatId, "Сессия не найдена. Введи /check."));
            return;
        }
        if (s.stage == Stage.WAIT_FOLLOWERS) {
            s.followers = usernames;
            s.stage = Stage.WAIT_FOLLOWING;
            bot.execute(new SendMessage(chatId, "Принял список подписчиков. Теперь пришли файл с подписками (following)."));
            return;
        }
        if (s.stage == Stage.WAIT_FOLLOWING) {
            s.following = usernames;
            s.stage = Stage.IDLE;
            computeAndRespond(chatId, s);
            return;
        }
        bot.execute(new SendMessage(chatId, "Неожиданный этап. Введи /check, чтобы начать заново."));
    }

    private void computeAndRespond(Long chatId, Session s) {
        if (s.followers == null || s.following == null) {
            bot.execute(new SendMessage(chatId, "Нужны оба списка: followers и following."));
            return;
        }
        MutualsService.Result r = MutualsService.compute(s.followers, s.following);
        String summary = String.format(Locale.ROOT,
                "Всего followers: %d\nВсего following: %d\nВзаимные: %d\nНе взаимные (ты подписан, они нет): %d\nНе взаимные (они подписаны, ты нет): %d",
                s.followers.size(), s.following.size(), r.mutuals.size(), r.notFollowingBack.size(), r.notFollowedByYou.size());
        bot.execute(new SendMessage(chatId, summary));

        try {
            // Prepare files if lists are big
            sendListAsFile(chatId, r.mutuals, "mutuals.txt");
            sendListAsFile(chatId, r.notFollowingBack, "not_following_back.txt");
            sendListAsFile(chatId, r.notFollowedByYou, "not_followed_by_you.txt");
        } catch (IOException e) {
            bot.execute(new SendMessage(chatId, "Ошибка при отправке файлов: " + e.getMessage()));
        }
    }

    private void sendListAsFile(Long chatId, List<String> list, String filename) throws IOException {
        if (list.isEmpty()) {
            bot.execute(new SendMessage(chatId, filename + ": пусто"));
            return;
        }
        Path tmp = Files.createTempFile(filename.replace('.','_'), ".txt");
        Files.writeString(tmp, String.join("\n", list), StandardCharsets.UTF_8);
        bot.execute(new SendDocument(chatId, tmp.toFile()).fileName(filename));
        try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
    }

    private byte[] downloadTelegramFile(String fileId) throws IOException, InterruptedException {
        GetFileResponse resp = bot.execute(new GetFile(fileId));
        if (!resp.isOk()) throw new IOException("GetFile failed: " + resp.errorCode() + " " + resp.description());
        String filePath = resp.file().filePath();
        String token = bot.getToken();
        String url = "https://api.telegram.org/file/bot" + token + "/" + filePath;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new IOException("Download failed: status " + response.statusCode());
        return response.body();
    }

    private static Set<String> parseUsernames(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        return Arrays.stream(s.replace("\r", "\n").split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(TelegramBotService::normalizeUsername)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
    }

    private static String normalizeUsername(String s) {
        String t = s;
        if (t.startsWith("@")) t = t.substring(1);
        t = t.toLowerCase(Locale.ROOT).replace(" ", "");
        // strip URL forms like https://instagram.com/username
        if (t.contains("instagram.com/")) {
            int i = t.indexOf("instagram.com/");
            t = t.substring(i + "instagram.com/".length());
            int q = t.indexOf('?');
            if (q >= 0) t = t.substring(0, q);
            t = t.replaceAll("[^a-z0-9._]", "");
        }
        // keep only allowed chars
        t = t.replaceAll("[^a-z0-9._]", "");
        return t;
    }

    private static String startText() {
        return "Привет! Я бот для сравнения списков Instagram.\n" +
               "1) Введи /check\n" +
               "2) Пришли файл с followers (по одному никнейму на строку)\n" +
               "3) Пришли файл с following\n" +
               "Я верну взаимных и расхождения в отдельных файлах.";
    }

    private static String helpText() {
        return "Команды:\n" +
               "/check — начать новую проверку\n" +
               "/help — помощь\n\n" +
               "Можно присылать .txt/.csv файлы или просто текстом списки по одному нику в строке.";
    }
}
