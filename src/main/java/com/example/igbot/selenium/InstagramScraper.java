package com.example.igbot.selenium;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class InstagramScraper {
    public static class Pair {
        public final Set<String> followers;
        public final Set<String> following;
        public Pair(Set<String> followers, Set<String> following) {
            this.followers = followers; this.following = following;
        }
    }

    public static Pair fetchAll(String username, Set<Cookie> cookies) {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage");
        WebDriver driver = new ChromeDriver(options);
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
            driver.get("https://www.instagram.com/");
            driver.manage().deleteAllCookies();
            for (Cookie c : cookies) {
                try { driver.manage().addCookie(c); } catch (Exception ignored) {}
            }
            driver.get("https://www.instagram.com/" + username + "/");
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            closePopups(driver);

            Set<String> followers = openListAndCollect(driver, wait, username, true);
            Set<String> following = openListAndCollect(driver, wait, username, false);
            return new Pair(followers, following);
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    private static void closePopups(WebDriver driver) {
        try {
            List<By> selectors = Arrays.asList(
                    By.xpath("//button[contains(.,'Allow essential cookies') or contains(.,'Accept all')]"),
                    By.xpath("//button[contains(.,'Разрешить') or contains(.,'Принять все')]")
            );
            for (By by : selectors) {
                List<WebElement> els = driver.findElements(by);
                for (WebElement el : els) {
                    try { el.click(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private static Set<String> openListAndCollect(WebDriver driver, WebDriverWait wait, String username, boolean followers) {
        openList(driver, wait, followers);
        Set<String> names = collectFromDialog(driver);
        try { driver.navigate().back(); } catch (Exception ignored) {}
        return names;
    }

    private static void openList(WebDriver driver, WebDriverWait wait, boolean followers) {
        By linkSelectorA = followers ? By.partialLinkText("followers") : By.partialLinkText("following");
        By linkSelectorB = followers ? By.xpath("//a[contains(.,'подписчик') or contains(.,'followers')]") : By.xpath("//a[contains(.,'подписки') or contains(.,'following')]");
        WebElement link = null;
        try { link = wait.until(ExpectedConditions.elementToBeClickable(linkSelectorA)); } catch (Exception ignored) {}
        if (link == null) {
            try { link = wait.until(ExpectedConditions.elementToBeClickable(linkSelectorB)); } catch (Exception ignored) {}
        }
        if (link == null) throw new NoSuchElementException("Followers/Following link not found");
        try { link.click(); } catch (Exception e) {
            ((JavascriptExecutor)driver).executeScript("arguments[0].click();", link);
        }
        wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@role='dialog']")));
    }

    private static Set<String> collectFromDialog(WebDriver driver) {
        WebElement dialog = driver.findElement(By.xpath("//div[@role='dialog']"));
        WebElement scrollArea = dialog;
        try {
            List<WebElement> areas = dialog.findElements(By.xpath(".//div[contains(@style,'overflow') or contains(@class,'_aano') or contains(@class,'x1q0g3np')]"));
            if (!areas.isEmpty()) scrollArea = areas.get(areas.size()-1);
        } catch (Exception ignored) {}

        Set<String> usernames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        int stable = 0;
        long lastCount = -1;
        for (int i = 0; i < 200; i++) {
            ((JavascriptExecutor)driver).executeScript("arguments[0].scrollTop = arguments[0].scrollHeight;", scrollArea);
            try { Thread.sleep(800 + new Random().nextInt(600)); } catch (InterruptedException ignored) {}
            usernames.addAll(extractUsernamesFromDialog(dialog));
            if (usernames.size() == lastCount) {
                stable++;
                if (stable >= 3) break;
            } else {
                stable = 0;
                lastCount = usernames.size();
            }
        }
        return usernames;
    }

    private static Set<String> extractUsernamesFromDialog(WebElement dialog) {
        List<WebElement> links = dialog.findElements(By.xpath(".//a[@href and starts-with(@href,'/') and contains(@href,'/')]"));
        Pattern p = Pattern.compile("^/([A-Za-z0-9._]+)/$");
        return links.stream()
                .map(a -> a.getAttribute("href"))
                .filter(Objects::nonNull)
                .map(href -> {
                    try {
                        int idx = href.indexOf("instagram.com/");
                        if (idx >= 0) {
                            String tail = href.substring(idx + "instagram.com/".length());
                            int q = tail.indexOf('?');
                            if (q >= 0) tail = tail.substring(0, q);
                            if (tail.endsWith("/")) return tail.substring(0, tail.length()-1);
                            return tail;
                        }
                        return null;
                    } catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .filter(u -> !u.isBlank())
                .filter(u -> !u.startsWith("explore"))
                .filter(u -> !u.startsWith("reel"))
                .filter(u -> !u.startsWith("p/"))
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
    }
}
