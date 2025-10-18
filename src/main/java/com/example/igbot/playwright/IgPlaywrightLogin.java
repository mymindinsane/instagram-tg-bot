package com.example.igbot.playwright;

import com.microsoft.playwright.*;
import com.example.igbot.util.AppCookie;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;

import java.util.*;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.StandardOpenOption;

public class IgPlaywrightLogin {
    private static final String LOG_FILE = "playwright-log-" + System.currentTimeMillis() + ".log";
    public static class Handle {
        final Playwright pw;
        final Browser browser;
        final BrowserContext context;
        final Page page;
        Handle(Playwright pw, Browser browser, BrowserContext context, Page page) {
            this.pw = pw; this.browser = browser; this.context = context; this.page = page;
        }
    }

    private static void tryClick(Page page, String selector) {
        try {
            Locator loc = page.locator(selector).first();
            if (loc.count() > 0) {
                try {
                    loc.scrollIntoViewIfNeeded();
                } catch (Exception ignored) {}
                loc.click(new Locator.ClickOptions().setTimeout(2000));
            }
        } catch (Exception ignored) {
        }
    }

    public static class Result {
        public final Set<AppCookie> cookies;
        public final Handle handle; // not null if waiting for 2FA
        private Result(Set<AppCookie> cookies, Handle handle) {
            this.cookies = cookies; this.handle = handle;
        }
        public static Result cookies(Set<AppCookie> cookies) { return new Result(cookies, null); }
        public static Result wait2fa(Handle handle) { return new Result(null, handle); }
    }

    public static Result startLogin(String username, String password) {
        Playwright pw = Playwright.create();
        boolean debug = isDebug();
        // Всегда запускаем headful (реальный браузер). Замедление по IG_DEBUG
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(false);
        String proxy = getenvSafe("IG_PROXY");
        if (proxy != null && !proxy.isEmpty()) {
            try { launchOptions.setProxy(new Proxy(proxy)); } catch (Exception ignored) {}
        }
        if (debug) launchOptions.setSlowMo(500.0);
        Browser browser = pw.chromium().launch(launchOptions);

        Browser.NewContextOptions ctxOptions = new Browser.NewContextOptions()
                .setLocale("ru-RU")
                .setTimezoneId("Europe/Moscow")
                .setViewportSize(1280, 900)
                .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 13_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36");
        // Загрузка сохраненного состояния (если есть)
        try {
            if (Files.exists(Paths.get("storageState.json"))) {
                ctxOptions.setStorageStatePath(Paths.get("storageState.json"));
            }
        } catch (Exception ignored) {}
        BrowserContext context = browser.newContext(ctxOptions);
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
            headers.put("Referer", "https://www.instagram.com/");
            // Важно: не добавляем Upgrade-Insecure-Requests и Sec-Fetch-* во избежание CORS preflight
            context.setExtraHTTPHeaders(headers);
        } catch (Exception ignored) {}
        Page page = context.newPage();
        try {
            // Mask webdriver flag
            try {
                page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});");
                page.addInitScript("Object.defineProperty(navigator, 'languages', {get: () => ['ru-RU','ru','en-US']});");
                page.addInitScript("Object.defineProperty(navigator, 'platform', {get: () => 'MacIntel'});");
            } catch (Exception ignored) {}

            // Логирование сети и консоли только в режиме отладки
            if (debug) {
                setupLogging(page);
            }

            if (debug) {
                try {
                    context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true).setSources(true));
                } catch (Exception ignored) {}
            }
            String desktopLoginUrl = "https://www.instagram.com/accounts/login/";
            try {
                navigateWithRetry(page, desktopLoginUrl, 4, 1500);
            } catch (RuntimeException navErr) {
                // Retry with mobile site and UA in a fresh context
                try { page.close(); } catch (Exception ignored) {}
                try { context.close(); } catch (Exception ignored) {}
                Browser.NewContextOptions mobileCtx = new Browser.NewContextOptions()
                        .setLocale("ru-RU")
                        .setTimezoneId("Europe/Moscow")
                        .setViewportSize(390, 800)
                        .setUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 16_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1");
                context = browser.newContext(mobileCtx);
                page = context.newPage();
                try {
                    page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});");
                } catch (Exception ignored) {}
                String mobileLoginUrl = "https://m.instagram.com/accounts/login/";
                navigateWithRetry(page, mobileLoginUrl, 4, 1500);
            }
            if (debug) saveArtifacts(page, "after-navigate");
            waitForStableUrl(page, 20000, 3000);

            // Dismiss cookie banners (multi-language)
            dismissCookieBanners(page);
            if (debug) saveArtifacts(page, "after-consent");
            waitForStableUrl(page, 20000, 3000);

            // Try multiple selector variants and frames for resilience
            String userSel = "input[name='username'], input[aria-label='Phone number, username, or email'], input[aria-label='Номер телефона, имя пользователя или эл. адрес']";
            String passSel = "input[name='password'], input[type='password']";

            Locator userInput = page.locator(userSel).first();
            Locator passInput = page.locator(passSel).first();

            if (userInput.count() == 0 || !userInput.isVisible()) {
                for (Frame f : page.frames()) {
                    Locator uf = f.locator(userSel).first();
                    if (uf.count() > 0) {
                        userInput = uf;
                        passInput = f.locator(passSel).first();
                        break;
                    }
                }
            }

            // На всякий случай ещё раз скрыть баннеры куки
            dismissCookieBanners(page);

            // Wait explicitly for username field to be visible
            if (userInput.count() == 0) {
                // capture artifacts to help debugging
                if (debug) saveArtifacts(page, "login-debug");
                throw new RuntimeException("Поле логина не найдено на странице (см. playwright-login-debug.png)");
            }
            userInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(60000));

            // Имитируем реальные действия пользователя: move -> click -> focus -> type с задержками
            moveMouseTo(page, userInput);
            userInput.click();
            userInput.focus();
            typeHuman(userInput, username);
            if (passInput.count() == 0) {
                // Some variants only render password after entering username
                passInput = userInput.page().locator(passSel).first();
                if (passInput.count() == 0) {
                    for (Frame f : page.frames()) {
                        Locator pf = f.locator(passSel).first();
                        if (pf.count() > 0) { passInput = pf; break; }
                    }
                }
            }
            moveMouseTo(page, passInput);
            passInput.click();
            passInput.focus();
            typeHuman(passInput, password);

            // Явно ищем и кликаем кнопку "Войти"/"Log in"
            boolean clicked = false;
            try {
                Locator btn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log in")).first();
                if (btn.count() == 0) {
                    btn = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Войти")).first();
                }
                if (btn.count() == 0) {
                    btn = page.locator("form button[type='submit']").first();
                }
                if (btn.count() > 0) {
                    btn.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.ATTACHED).setTimeout(10000));
                    long t0 = System.currentTimeMillis();
                    while (System.currentTimeMillis() - t0 < 5000 && !btn.isEnabled()) {
                        page.waitForTimeout(100);
                    }
                    if (btn.isVisible()) {
                        moveMouseTo(page, btn);
                        btn.click();
                        clicked = true;
                    }
                }
            } catch (Exception ignored) {}
            if (!clicked) {
                // Фолбек: перейти табом к кнопке и нажать Enter
                page.keyboard().press("Tab");
                page.waitForTimeout(150);
                page.keyboard().press("Enter");
            }

            // wait up to 60s for either home or 2FA input
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 60000) {
                // Post-login modals: "Save your login info?" and "Turn on notifications"
                try {
                    Locator notNow = page.locator("text=Не сейчас, text=Not now, text=Not Now").first();
                    if (notNow.count() > 0 && notNow.isVisible()) {
                        moveMouseTo(page, notNow);
                        notNow.click();
                        page.waitForTimeout(300);
                    }
                } catch (Exception ignored) {}
                try {
                    Locator saveInfo = page.locator("text=Сохранить данные, text=Save info, text=Save login info").first();
                    Locator notNow2 = page.locator("text=Не сейчас, text=Not now, text=Not Now").first();
                    if (saveInfo.count() > 0 && notNow2.count() > 0 && notNow2.isVisible()) {
                        moveMouseTo(page, notNow2);
                        notNow2.click();
                        page.waitForTimeout(300);
                    }
                } catch (Exception ignored) {}

                // Wrong password detection (scoped to alert/error elements; do NOT trigger on "Забыли пароль?")
                try {
                    boolean wrong = false;
                    // Common IG error containers
                    Locator alerts = page.locator("div[role='alert'], [aria-live='polite'], [aria-live='assertive'], form div:has-text('Неверный пароль'), form div:has-text('incorrect password')");
                    if (alerts.count() > 0) {
                        if (alerts.locator("text=The password you entered is incorrect").count() > 0) wrong = true;
                        if (alerts.locator("text=incorrect password").count() > 0) wrong = true;
                        if (alerts.locator("text=Неверный пароль").count() > 0) wrong = true;
                        if (alerts.locator("text=Пароль введен неверно").count() > 0) wrong = true;
                        if (alerts.locator("text=К сожалению, вы ввели неправильный пароль. Проверьте свой пароль еще раз.").count() > 0) wrong = true;
                    }
                    if (!wrong) {
                        // узкоспециализированные блоки ошибки под полем
                        Locator underInputs = page.locator("form [id*='error'], form [class*='error'], form div:has([role='alert'])");
                        if (underInputs.locator("text=Неверный пароль").count() > 0 || underInputs.locator("text=incorrect password").count() > 0) {
                            wrong = true;
                        }
                    }
                    if (wrong) throw new IllegalArgumentException("WRONG_PASSWORD");
                } catch (RuntimeException re) { throw re; } catch (Exception ignored) {}

                // 2FA field candidates
                if (page.locator("input[name='verificationCode']").count() > 0 ||
                        page.locator("input[aria-label='Security code']").count() > 0) {
                    return Result.wait2fa(new Handle(pw, browser, context, page));
                }
                // Logged-in heuristic: presence of nav or redirect to /
                if (page.url().contains("instagram.com") && page.locator("nav").count() > 0) {
                    // Сохраняем состояние для будущих запусков
                    try { context.storageState(new BrowserContext.StorageStateOptions().setPath(Paths.get("storageState.json"))); } catch (Exception ignored) {}
                    Set<AppCookie> cookies = collectCookies(context);
                    safeClose(pw, browser, context);
                    return Result.cookies(cookies);
                }
                page.waitForTimeout(1000);
            }
            // timeout -> try getting cookies anyway
            Set<AppCookie> cookies = collectCookies(context);
            safeClose(pw, browser, context);
            return Result.cookies(cookies);
        } catch (RuntimeException e) {
            if (debug) {
                try { context.tracing().stop(new Tracing.StopOptions().setPath(Paths.get("trace.zip"))); } catch (Exception ignored) {}
            }
            safeClose(pw, browser, context);
            throw e;
        }
    }

    private static boolean isDebug() {
        try {
            String v = System.getenv("IG_DEBUG");
            return v != null && v.equalsIgnoreCase("true");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static Set<AppCookie> submit2FA(Handle handle, String code) {
        Page page = handle.page;
        try {
            Locator codeInput = page.locator("input[name='verificationCode'], input[aria-label='Security code']").first();
            if (codeInput.count() == 0) throw new IllegalStateException("Поле 2FA не найдено");
            codeInput.fill(code);
            codeInput.press("Enter");

            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 60000) {
                if (page.url().contains("instagram.com") && page.locator("nav").count() > 0) {
                    Set<AppCookie> cookies = collectCookies(handle.context);
                    safeClose(handle.pw, handle.browser, handle.context);
                    return cookies;
                }
                // simple invalid code detection
                if (page.locator("text=incorrect").count() > 0 || page.locator("text=Неверный").count() > 0) {
                    throw new IllegalArgumentException("Неверный код 2FA");
                }
                page.waitForTimeout(1000);
            }
            throw new RuntimeException("Таймаут ожидания после ввода кода");
        } catch (RuntimeException e) {
            safeClose(handle.pw, handle.browser, handle.context);
            throw e;
        }
    }

    private static void navigateWithRetry(Page page, String url, int attempts, int backoffMs) {
        RuntimeException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                Response r = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                if (r != null) {
                    int status = r.status();
                    if (status == 429 || status >= 500) {
                        page.waitForTimeout((i + 1L) * backoffMs);
                        continue;
                    }
                }
                return;
            } catch (RuntimeException e) {
                last = e;
                page.waitForTimeout((i + 1L) * backoffMs);
            }
        }
        if (last != null) throw last;
        throw new RuntimeException("Навигация не удалась: " + url);
    }

    private static String getenvSafe(String name) {
        try {
            return System.getenv(name);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void saveArtifacts(Page page, String tag) {
        try {
            long ts = System.currentTimeMillis();
            String base = "playwright-" + tag + "-" + ts;
            // Screenshot
            page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(Paths.get(base + ".png")));
            // HTML content
            String html = page.content();
            Files.write(Paths.get(base + ".html"), html.getBytes(StandardCharsets.UTF_8));
            // URL and title
            String meta = "URL: " + page.url() + "\nTITLE: " + safeTitle(page) + "\n";
            Files.write(Paths.get(base + ".txt"), meta.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static String safeTitle(Page page) {
        try { return page.title(); } catch (Exception e) { return ""; }
    }

    private static void setupLogging(Page page) {
        page.onConsoleMessage(msg -> {
            String line = ts() + " [console] " + msg.type() + ": " + msg.text() + "\n";
            writeLog(line);
        });
        page.onRequest(req -> {
            String line = ts() + " [request] " + req.method() + " " + req.url() + "\n";
            writeLog(line);
        });
        page.onResponse(res -> {
            try {
                String line = ts() + " [response] " + res.status() + " " + res.url() + "\n";
                writeLog(line);
            } catch (Exception ignored) {}
        });
        page.onRequestFailed(req -> {
            String line = ts() + " [request-failed] " + req.method() + " " + req.url() + " error=" + req.failure() + "\n";
            writeLog(line);
        });
        page.onFrameNavigated(frame -> {
            if (frame == page.mainFrame()) {
                String line = ts() + " [navigated] " + frame.url() + "\n";
                writeLog(line);
            }
        });
    }

    private static void writeLog(String text) {
        try {
            Files.write(Paths.get(LOG_FILE), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static String ts() {
        try {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now());
        } catch (Exception ignored) { return Long.toString(System.currentTimeMillis()); }
    }

    private static void waitForStableUrl(Page page, long totalTimeoutMs, long stableForMs) {
        long start = System.currentTimeMillis();
        String last = page.url();
        long lastChange = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < totalTimeoutMs) {
            String cur = page.url();
            if (!Objects.equals(cur, last)) {
                writeLog(ts() + " [url-change] " + last + " -> " + cur + "\n");
                last = cur;
                lastChange = System.currentTimeMillis();
            }
            if (System.currentTimeMillis() - lastChange >= stableForMs) return;
            page.waitForTimeout(300);
        }
        writeLog(ts() + " [url-unstable-timeout] url=" + page.url() + "\n");
    }

    private static void moveMouseTo(Page page, Locator loc) {
        try {
            loc.hover();
        } catch (Exception ignored) {
            // ignore move errors
        }
    }

    private static void typeHuman(Locator loc, String text) {
        try {
            loc.type(text, new Locator.TypeOptions().setDelay(80.0));
        } catch (Exception e) {
            // fallback
            loc.fill(text);
        }
    }

    private static void dismissCookieBanners(Page page) {
        String[] selectors = new String[] {
                "text=Only allow essential cookies",
                "text=Only allow essential",
                "text=Allow all cookies",
                "text=Accept all",
                "text=Разрешить все куки",
                "text=Разрешить все cookie",
                "text=Только необходимые cookie",
                "text=Принять все",
                "button:has-text('Разрешить все')",
                "button:has-text('Принять')",
                "button:has-text('Accept')",
                "button:has-text('Allow all')",
        };
        try {
            for (String sel : selectors) {
                tryClick(page, sel);
            }
            // иногда помогает Escape
            try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static Set<AppCookie> collectCookies(BrowserContext context) {
        java.util.List<com.microsoft.playwright.options.Cookie> pcs = context.cookies();
        Set<AppCookie> out = new LinkedHashSet<>();
        for (com.microsoft.playwright.options.Cookie c : pcs) {
            Long exp = c.expires == null ? null : (long) Math.floor(c.expires);
            out.add(new AppCookie(c.name, c.value, c.domain, c.path, exp, Boolean.TRUE.equals(c.httpOnly), Boolean.TRUE.equals(c.secure)));
        }
        return out;
    }

    private static void safeClose(Playwright pw, Browser browser, BrowserContext context) {
        try { context.close(); } catch (Exception ignored) {}
        try { browser.close(); } catch (Exception ignored) {}
        try { pw.close(); } catch (Exception ignored) {}
    }
}
