package com.example.igbot.selenium;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public class InstagramAutoLogin {
    public static class Handle {
        public final WebDriver driver;
        public final WebDriverWait wait;
        public boolean awaiting2FA;
        public Handle(WebDriver driver, WebDriverWait wait, boolean awaiting2FA) {
            this.driver = driver; this.wait = wait; this.awaiting2FA = awaiting2FA;
        }
    }

    public static class Result {
        public final Set<org.openqa.selenium.Cookie> cookies;
        public final Handle handle;
        private Result(Set<org.openqa.selenium.Cookie> cookies, Handle handle) {
            this.cookies = cookies; this.handle = handle;
        }
        public static Result cookies(Set<org.openqa.selenium.Cookie> cookies) { return new Result(cookies, null); }
        public static Result wait2fa(Handle handle) { return new Result(null, handle); }
    }

    public static Result startLogin(String username, String password) {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--no-sandbox", "--disable-dev-shm-usage");
        // НЕ headless: повышает шанс пройти защиту. Можно включить headless при необходимости.
        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(40));
        try {
            driver.get("https://www.instagram.com/accounts/login/");
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));
            // Заполняем форму
            By userSel = By.name("username");
            By passSel = By.name("password");
            wait.until(ExpectedConditions.elementToBeClickable(userSel)).sendKeys(username);
            driver.findElement(passSel).sendKeys(password);
            driver.findElement(passSel).sendKeys(Keys.ENTER);

            // Ждем один из исходов: 2FA, главная страница, ошибка логина
            boolean need2fa = waitForTwoFaOrHome(wait, driver);
            if (need2fa) {
                return Result.wait2fa(new Handle(driver, wait, true));
            }
            // Успешный логин -> cookies
            Set<org.openqa.selenium.Cookie> cookies = new LinkedHashSet<>(driver.manage().getCookies());
            safeQuit(driver);
            return Result.cookies(cookies);
        } catch (Exception e) {
            safeQuit(driver);
            throw e;
        }
    }

    private static boolean waitForTwoFaOrHome(WebDriverWait wait, WebDriver driver) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 60000) {
            try {
                // Проверка на поле ввода кода (обычно input[name=verificationCode] или аналог)
                if (!driver.findElements(By.name("verificationCode")).isEmpty()) return true;
                if (!driver.findElements(By.xpath("//input[@aria-label='Security code' or @name='verificationCode']")).isEmpty()) return true;
                // Проверка на домашнюю ленту/страницу профиля
                if (driver.getCurrentUrl().contains("instagram.com") &&
                        driver.findElements(By.tagName("nav")).size() > 0) return false;
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        // По таймауту считаем, что 2FA не требуется, но это может быть ошибкой
        return false;
    }

    public static Set<org.openqa.selenium.Cookie> submit2FA(Handle handle, String code) {
        WebDriver driver = handle.driver;
        WebDriverWait wait = handle.wait;
        try {
            Optional<WebElement> codeInput = findCodeInput(driver);
            if (codeInput.isEmpty()) {
                throw new IllegalStateException("Поле 2FA не найдено — возможно, сессия устарела");
            }
            WebElement input = codeInput.get();
            input.clear();
            input.sendKeys(code);
            input.sendKeys(Keys.ENTER);

            // Ожидаем вход
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 60000) {
                if (driver.getCurrentUrl().contains("instagram.com") && driver.findElements(By.tagName("nav")).size() > 0) {
                    Set<org.openqa.selenium.Cookie> cookies = new LinkedHashSet<>(driver.manage().getCookies());
                    safeQuit(driver);
                    return cookies;
                }
                // Ошибка кода
                if (!driver.findElements(By.xpath("//*[contains(text(),'code') and contains(text(),'incorrect') or contains(text(),'Неверный')]")).isEmpty()) {
                    throw new IllegalArgumentException("Неверный код 2FA");
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
            throw new RuntimeException("Таймаут ожидания после ввода кода");
        } catch (RuntimeException e) {
            safeQuit(driver);
            throw e;
        }
    }

    private static Optional<WebElement> findCodeInput(WebDriver driver) {
        try {
            if (!driver.findElements(By.name("verificationCode")).isEmpty()) return Optional.of(driver.findElement(By.name("verificationCode")));
        } catch (Exception ignored) {}
        try {
            if (!driver.findElements(By.xpath("//input[@aria-label='Security code' or @name='verificationCode']")).isEmpty())
                return Optional.of(driver.findElement(By.xpath("//input[@aria-label='Security code' or @name='verificationCode']")));
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static void safeQuit(WebDriver driver) {
        try { driver.quit(); } catch (Exception ignored) {}
    }
}
