package com.example.igbot.playwright;

import com.microsoft.playwright.*;
import com.example.igbot.util.AppCookie;
import com.microsoft.playwright.options.LoadState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class IgPlaywrightScraper {
    public static class Pair {
        public final Set<String> followers;
        public final Set<String> following;
        public Pair(Set<String> followers, Set<String> following) {
            this.followers = followers; this.following = following;
        }
    }

    public static Pair fetchAll(String username, Set<AppCookie> cookies) {
        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));
            Browser.NewContextOptions ctxOptions = new Browser.NewContextOptions()
                    .setLocale("ru-RU")
                    .setTimezoneId("Europe/Moscow")
                    .setViewportSize(1280, 900)
                    .setUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 13_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36");
            BrowserContext context = browser.newContext(ctxOptions);
            // Apply cookies
            if (cookies != null && !cookies.isEmpty()) {
                List<com.microsoft.playwright.options.Cookie> list = new ArrayList<>();
                for (AppCookie c : cookies) {
                    com.microsoft.playwright.options.Cookie pc = new com.microsoft.playwright.options.Cookie(c.name, c.value)
                            .setDomain(c.domain == null ? ".instagram.com" : c.domain)
                            .setPath(c.path == null ? "/" : c.path);
                    if (c.expiresEpochSeconds != null) pc.setExpires(c.expiresEpochSeconds.doubleValue());
                    list.add(pc);
                }
                context.addCookies(list);
            }
            Page page = context.newPage();
            // минимальная маскировка
            try { page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"); } catch (Exception ignored) {}

            String url = "https://www.instagram.com/" + username + "/";
            page.navigate(url);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            if (isDebug()) saveArtifacts(page, "profile-navigate");

            // Проверка приватности/ошибок
            if (page.locator("text=This account is private").count() > 0 ||
                page.locator("text=Закрытый аккаунт").count() > 0) {
                throw new RuntimeException("Профиль приватный. Нужна подписка/подтверждение доступа.");
            }
            // Убедиться, что хедер профиля прогрузился
            page.waitForSelector("header", new Page.WaitForSelectorOptions().setTimeout(15000));

            // Open followers dialog
            Set<String> followers = openAndCollect(page, true);
            // Open following dialog
            Set<String> following = openAndCollect(page, false);

            context.close();
            browser.close();
            return new Pair(followers, following);
        }
    }

    private static Set<String> openAndCollect(Page page, boolean followers) {
        // Click followers/following link
        String linkSelectorFollowers = "a[href$='/followers/'], a:has-text('followers'), a:has-text('подписчик')";
        String linkSelectorFollowing = "a[href$='/following/'], a:has-text('following'), a:has-text('подписки')";
        String selector = followers ? linkSelectorFollowers : linkSelectorFollowing;
        Locator link = page.locator(selector).first();
        link.waitFor();
        link.click();
        page.waitForTimeout(500);
        // Dialog
        Locator dialog = page.locator("div[role='dialog']").first();
        dialog.waitFor(new Locator.WaitForOptions().setTimeout(15000));
        // На практике у Instagram часто скроллится контейнер с классом _aano
        Locator scrollArea = dialog.locator("div._aano, div[style*='overflow'], ul, div[role='dialog']");
        if (scrollArea.count() == 0) scrollArea = dialog;
        Locator list = dialog.locator("ul").first();
        if (list.count() == 0) list = scrollArea.first();
        // дождаться появления первых элементов списка
        try { list.locator("li, a[href]").first().waitFor(new Locator.WaitForOptions().setTimeout(10000)); } catch (Exception ignored) {}
        // Фокус и наведение на область прокрутки
        try { scrollArea.first().click(); } catch (Exception ignored) {}
        try { moveMouseToCenter(page, scrollArea.first()); } catch (Exception ignored) {}

        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        int stable = 0; int lastCount = -1;
        for (int i = 0; i < 1000; i++) {
            // попытаться скроллить к последнему видимому элементу
            try {
                Locator items = list.locator("li");
                int cnt = items.count();
                if (cnt > 0) {
                    items.nth(Math.max(0, cnt - 1)).scrollIntoViewIfNeeded();
                } else {
                    // запасной вариант: прокрутка контейнера
                    page.evaluate("(el)=>{el.scrollTop = el.scrollHeight}", scrollArea.first().elementHandle());
                }
            } catch (Exception ignored) {}

            // Доп. прокрутка: реальная прокрутка колесом мыши над контейнером, затем scrollBy и PageDown как бэкап
            try { wheelScrollOver(page, scrollArea.first(), 5, 300); } catch (Exception ignored) {}
            for (int step = 0; step < 5; step++) {
                try { scrollArea.first().hover(); } catch (Exception ignored) {}
                try { page.evaluate("(el)=>{el.scrollBy(0, 800)}", scrollArea.first().elementHandle()); } catch (Exception ignored) {}
                page.waitForTimeout(180);
            }
            try { page.keyboard().press("PageDown"); } catch (Exception ignored) {}

            // Если есть кнопки типа "Показать ещё" — кликнуть
            try {
                Locator more = dialog.locator("text=Показать ещё, text=Show more").first();
                if (more.count() > 0 && more.isVisible()) more.click();
            } catch (Exception ignored) {}

            page.waitForTimeout(700);
            names.addAll(extractUsernames(dialog));
            int cur = names.size();
            // Лёгкий лог в stdout для диагностики
            try { System.out.println("[scrape] collected=" + cur); } catch (Exception ignored) {}
            if (cur == lastCount) {
                stable++; if (stable >= 5) break;
            } else { stable = 0; lastCount = cur; }
        }
        // Close dialog with Escape
        page.keyboard().press("Escape");

        // Если прирост остановился слишком рано, пробуем fallback через отдельную страницу
        if (names.size() <= 6) {
            try {
                String suffix = followers ? "/followers/" : "/following/";
                String href = null;
                try {
                    Locator lnk = page.locator("a[href$='" + suffix + "']").first();
                    if (lnk.count() > 0) href = lnk.getAttribute("href");
                } catch (Exception ignored) {}
                String baseUrl = page.url();
                int ix = baseUrl.indexOf("instagram.com/");
                if (ix > 0) {
                    String tail = baseUrl.substring(ix + "instagram.com/".length());
                    int slash = tail.indexOf('/');
                    String user = slash >= 0 ? tail.substring(0, slash) : tail;
                    if (href == null || href.isBlank()) {
                        href = "https://www.instagram.com/" + user + suffix;
                    } else if (href.startsWith("/")) {
                        href = "https://www.instagram.com" + href;
                    }
                    page.navigate(href);
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
                    // Скроллим страницу целиком
                    Set<String> pageNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    int stable2 = 0; int last2 = -1;
                    for (int i = 0; i < 1200; i++) {
                        try { page.evaluate("(d)=>{(d.scrollingElement||d.documentElement).scrollBy(0,1200)}", page.mainFrame().evaluateHandle("() => document")); } catch (Exception ignored) {}
                        try { page.keyboard().press("PageDown"); } catch (Exception ignored) {}
                        page.waitForTimeout(400);
                        pageNames.addAll(extractUsernames(page.locator("main")));
                        int cur2 = pageNames.size();
                        if (cur2 == last2) { stable2++; if (stable2 >= 8) break; } else { stable2 = 0; last2 = cur2; }
                    }
                    names.addAll(pageNames);
                }
            } catch (Exception ignored) {}
        }
        return names;
    }

    private static Set<String> extractUsernames(Locator dialog) {
        List<String> hrefs = dialog.locator("a[href]").all().stream()
                .map(el -> el.getAttribute("href")).filter(Objects::nonNull).collect(Collectors.toList());
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String href : hrefs) {
            String tail = href;
            // Normalize absolute instagram links
            if (tail.startsWith("https://www.instagram.com/") || tail.startsWith("http://www.instagram.com/") || tail.startsWith("https://instagram.com/")) {
                int idx = tail.indexOf("instagram.com/");
                tail = tail.substring(idx + "instagram.com/".length());
            }
            // Handle relative links like "/username/"
            if (tail.startsWith("/")) tail = tail.substring(1);
            int q = tail.indexOf('?'); if (q >= 0) tail = tail.substring(0, q);
            if (tail.endsWith("/")) tail = tail.substring(0, tail.length()-1);
            if (tail.isBlank()) continue;
            // Skip non-profile paths
            String low = tail.toLowerCase(Locale.ROOT);
            if (low.startsWith("explore") || low.startsWith("reel") || low.startsWith("p/") || low.startsWith("stories") ||
                low.startsWith("accounts") || low.startsWith("direct") || low.startsWith("challenge") || low.startsWith("about") || low.startsWith("press") || low.startsWith("developer")) {
                continue;
            }
            // Username is first path segment
            int slash = tail.indexOf('/');
            String user = slash >= 0 ? tail.substring(0, slash) : tail;
            if (user.matches("[A-Za-z0-9._]{1,30}")) {
                out.add(user.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static void saveArtifacts(Page page, String tag) {
        try {
            long ts = System.currentTimeMillis();
            String base = "scrape-" + tag + "-" + ts;
            page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setPath(Paths.get(base + ".png")));
            String html = page.content();
            Files.write(Paths.get(base + ".html"), html.getBytes(StandardCharsets.UTF_8));
            String meta = "URL: " + page.url() + "\nTITLE: " + safeTitle(page) + "\n";
            Files.write(Paths.get(base + ".txt"), meta.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static String safeTitle(Page page) {
        try { return page.title(); } catch (Exception e) { return ""; }
    }

    private static boolean isDebug() {
        try {
            String v = System.getenv("IG_DEBUG");
            return v != null && v.equalsIgnoreCase("true");
        } catch (Exception ignored) {
            return false;
        }
    }

    // Наведение мыши в центр элемента без использования BoundingBox API (совместимо со старыми версиями)
    private static void moveMouseToCenter(Page page, Locator loc) {
        try {
            loc.scrollIntoViewIfNeeded();
            Object res = loc.evaluate("el => { const r = el.getBoundingClientRect(); return [r.x + r.width/2, r.y + r.height/2]; }");
            if (res instanceof java.util.List) {
                java.util.List<?> arr = (java.util.List<?>) res;
                double x = ((Number) arr.get(0)).doubleValue();
                double y = ((Number) arr.get(1)).doubleValue();
                page.mouse().move(x, y);
            } else {
                loc.hover();
            }
        } catch (Exception ignored) {}
    }

    // Эмуляция прокрутки колесом мыши над указанным контейнером
    private static void wheelScrollOver(Page page, Locator container, int steps, int deltaY) {
        try {
            moveMouseToCenter(page, container);
            for (int i = 0; i < steps; i++) {
                page.mouse().wheel(0, deltaY);
                page.waitForTimeout(120);
            }
        } catch (Exception ignored) {}
    }
}
