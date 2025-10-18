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

            // Оценим ожидаемые размеры для контроля доскролла
            Integer expectedFollowers = getExpectedCount(page, true);
            Integer expectedFollowing = getExpectedCount(page, false);

            // Open followers dialog
            Set<String> followers = openAndCollect(page, true, expectedFollowers);
            // Open following dialog
            Set<String> following = openAndCollect(page, false, expectedFollowing);

            context.close();
            browser.close();
            return new Pair(followers, following);
        }
    }

    private static Set<String> openAndCollect(Page page, boolean followers, Integer expectedTotal) {
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

        boolean slow = isSlow();
        java.util.Random rnd = slow ? new java.util.Random() : null;
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        int stable = 0; int lastCount = -1;
        for (int i = 0; i < (slow ? 3000 : 2000); i++) {
            // попытаться скроллить к последнему видимому элементу
            try {
                Locator items = list.locator("li");
                int cnt = items.count();
                if (cnt > 0) {
                    items.nth(Math.max(0, cnt - 1)).scrollIntoViewIfNeeded();
                } else {
                    // запасной вариант: прокрутка контейнера
                    scrollArea.first().evaluate("(el)=>{el.scrollTop = el.scrollHeight}");
                }
            } catch (Exception ignored) {}

            // Доп. прокрутка: реальная прокрутка колесом мыши над контейнером, затем scrollBy и PageDown как бэкап
            try { wheelScrollOver(page, scrollArea.first(), slow ? 4 : 3, slow ? 200 : 220); } catch (Exception ignored) {}
            for (int step = 0; step < (slow ? 4 : 3); step++) {
                try { scrollArea.first().hover(); } catch (Exception ignored) {}
                int dy = slow ? 520 + (rnd.nextInt(3) * 60) : 600;
                try { scrollArea.first().evaluate("(el,dy)=>{el.scrollBy(0, dy)}", dy); } catch (Exception ignored) {}
                page.waitForTimeout(slow ? 380 : 260);
            }
            try { page.keyboard().press("PageDown"); } catch (Exception ignored) {}

            // Если есть кнопки типа "Показать ещё" — кликнуть
            try {
                Locator more = dialog.locator("text=Показать ещё, text=Show more").first();
                if (more.count() > 0 && more.isVisible()) more.click();
            } catch (Exception ignored) {}

            // редкая синхронизация по сети
            if (!slow && i % 10 == 0) {
                try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(2000)); } catch (Exception ignored) {}
            }
            page.waitForTimeout(slow ? 1200 : 900);
            names.addAll(extractUsernames(dialog));
            int cur = names.size();
            // Лёгкий лог в stdout для диагностики
            try { System.out.println("[scrape] collected=" + cur); } catch (Exception ignored) {}
            if (expectedTotal != null && expectedTotal > 0 && cur >= expectedTotal) {
                break; // достигли ожидаемого размера
            }
            if (cur == lastCount) {
                stable++; if (stable >= (slow ? 12 : 8)) break;
            } else { stable = 0; lastCount = cur; }
        }
        // Close dialog with Escape
        page.keyboard().press("Escape");

        // Если прирост остановился слишком рано, пробуем fallback через отдельную страницу
        if (names.size() <= 6 || (expectedTotal != null && names.size() + 3 < expectedTotal)) {
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
                    for (int i = 0; i < (slow ? 2600 : 2000); i++) {
                        try { page.evaluate("(d)=>{(d.scrollingElement||d.documentElement).scrollBy(0,900)}", page.mainFrame().evaluateHandle("() => document")); } catch (Exception ignored) {}
                        try { page.keyboard().press("PageDown"); } catch (Exception ignored) {}
                        page.waitForTimeout(slow ? 900 : 600);
                        pageNames.addAll(extractUsernames(page.locator("main")));
                        int cur2 = pageNames.size();
                        if (expectedTotal != null && expectedTotal > 0 && cur2 >= expectedTotal) break;
                        if (cur2 == last2) { stable2++; if (stable2 >= (slow ? 14 : 10)) break; } else { stable2 = 0; last2 = cur2; }
                    }
                    names.addAll(pageNames);
                }
            } catch (Exception ignored) {}
        }
        // Мобильный фолбек: m.instagram.com, если по-прежнему недобор
        if (expectedTotal != null && names.size() + 3 < expectedTotal) {
            try {
                String suffix = followers ? "/followers/" : "/following/";
                String baseUrl = page.url();
                int ix = baseUrl.indexOf("instagram.com/");
                if (ix > 0) {
                    String tail = baseUrl.substring(ix + "instagram.com/".length());
                    int slash = tail.indexOf('/');
                    String user = slash >= 0 ? tail.substring(0, slash) : tail;
                    String href = "https://m.instagram.com/" + user + suffix;
                    page.navigate(href);
                    try { page.waitForLoadState(LoadState.DOMCONTENTLOADED); } catch (Exception ignored) {}
                    Set<String> pageNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    int stable3 = 0; int last3 = -1;
                    for (int i = 0; i < (slow ? 2800 : 2200); i++) {
                        try { page.evaluate("(d)=>{(d.scrollingElement||d.documentElement).scrollBy(0,800)}", page.mainFrame().evaluateHandle("() => document")); } catch (Exception ignored) {}
                        try { page.keyboard().press("PageDown"); } catch (Exception ignored) {}
                        try { page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(2000)); } catch (Exception ignored) {}
                        page.waitForTimeout(slow ? 1000 : 700);
                        pageNames.addAll(extractUsernames(page.locator("main")));
                        int cur3 = pageNames.size();
                        if (expectedTotal != null && expectedTotal > 0 && cur3 >= expectedTotal) break;
                        if (cur3 == last3) { stable3++; if (stable3 >= (slow ? 16 : 12)) break; } else { stable3 = 0; last3 = cur3; }
                    }
                    names.addAll(pageNames);
                }
            } catch (Exception ignored) {}
        }
        return names;
    }

    private static Set<String> extractUsernames(Locator root) {
        Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // 1) href patterns (absolute and relative)
        try {
            List<String> hrefs = root.locator("a[href]").all().stream()
                    .map(el -> el.getAttribute("href")).filter(Objects::nonNull).collect(Collectors.toList());
            for (String href : hrefs) {
                String tail = href;
                if (tail.startsWith("https://www.instagram.com/")) {
                    tail = tail.substring("https://www.instagram.com/".length());
                } else if (tail.startsWith("https://instagram.com/")) {
                    tail = tail.substring("https://instagram.com/".length());
                }
                if (tail.startsWith("/")) tail = tail.substring(1);
                int q = tail.indexOf('?');
                if (q >= 0) tail = tail.substring(0, q);
                int slash = tail.indexOf('/');
                if (slash >= 0) tail = tail.substring(0, slash);
                String user = tail.replaceAll("[^a-z0-9._]", "").toLowerCase(Locale.ROOT);
                if (!user.isEmpty() && user.length() <= 30 && !"p".equals(user) && !"accounts".equals(user)) out.add(user);
            }
        } catch (Exception ignored) {}
        // 2) data-username attributes
        try {
            List<String> attrs = root.locator("*[data-username]").all().stream()
                    .map(el -> el.getAttribute("data-username")).filter(Objects::nonNull).collect(Collectors.toList());
            for (String v : attrs) {
                String user = v.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._]", "");
                if (!user.isEmpty()) out.add(user);
            }
        } catch (Exception ignored) {}
        // 3) img alt contains username
        try {
            List<String> alts = root.locator("img[alt]").all().stream()
                    .map(el -> el.getAttribute("alt")).filter(Objects::nonNull).collect(Collectors.toList());
            for (String alt : alts) {
                String cand = alt.toLowerCase(Locale.ROOT).replace("@", " ");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-z0-9._]{2,30})").matcher(cand);
                if (m.find()) out.add(m.group(1));
            }
        } catch (Exception ignored) {}
        // 4) text nodes that look like usernames (with or without leading @)
        try {
            List<String> texts = root.allInnerTexts();
            for (String t : texts) {
                if (t == null) continue;
                String cand = t.trim();
                // quick filter to avoid huge text blobs
                if (cand.length() > 40) continue;
                cand = cand.toLowerCase(Locale.ROOT);
                if (cand.startsWith("@")) cand = cand.substring(1);
                cand = cand.replaceAll("[^a-z0-9._]", "");
                if (cand.matches("[a-z0-9._]{2,30}")) out.add(cand);
            }
        } catch (Exception ignored) {}
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

    private static boolean isSlow() {
        try {
            String v = System.getenv("IG_SLOW");
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

    // Estimate expected followers/following count from profile header
    private static Integer getExpectedCount(Page page, boolean followers) {
        try {
            String suffix = followers ? "/followers/" : "/following/";
            // try several header selectors
            Locator header = page.locator("header");
            Locator link = header.locator("a[href$='" + suffix + "']").first();
            String text = null;
            if (link.count() > 0) {
                // common case: span inside link holds number
                Locator num = link.locator("span, div").first();
                if (num.count() > 0) text = num.innerText();
                if (text == null || text.isBlank()) text = link.innerText();
            }
            if (text == null || text.isBlank()) {
                // fallback: any element near link
                Locator li = header.locator("li:has(a[href$='" + suffix + "'])").first();
                if (li.count() > 0) text = li.innerText();
            }
            if (text == null || text.isBlank()) return null;
            Integer v = parseCount(text);
            return v;
        } catch (Exception ignored) { return null; }
    }

    // Parse counts like "1 234", "12,345", "1.2k", "1,2 млн", "3 тыс."
    private static Integer parseCount(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // normalize spaces and thin spaces
        s = s.replace("\u00A0", " ").replace("\u2009", " ").replace("\u202F", " ");
        s = s.replace(" ", "");
        // ru words
        boolean millionRu = s.contains("млн");
        boolean thousandRu = s.contains("тыс");
        // en suffixes
        boolean hasK = s.endsWith("k") || s.contains("k");
        boolean hasM = s.endsWith("m") || s.contains("m");
        // extract number part with decimal comma/dot
        String num = s.replaceAll("[^0-9, .]", "");
        num = num.replace(',', '.');
        try {
            if (millionRu || hasM) {
                double v = Double.parseDouble(num);
                return (int) Math.round(v * 1_000_000d);
            }
            if (thousandRu || hasK) {
                double v = Double.parseDouble(num);
                return (int) Math.round(v * 1_000d);
            }
            // pure integer with separators already stripped
            String digits = s.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) return Integer.parseInt(digits);
        } catch (Exception ignored) {}
        return null;
    }
}
