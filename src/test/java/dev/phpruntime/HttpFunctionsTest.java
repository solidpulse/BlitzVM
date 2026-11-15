package dev.phpruntime;

import dev.phpruntime.host.PhpEngine;
import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cookies, response headers and request superglobals. php-cli does not track headers, so the
 * expected Set-Cookie lines were taken from PHP 8.5's built-in web server.
 */
class HttpFunctionsTest {

    private static PhpEngine engine;

    @BeforeAll
    static void setUp() {
        engine = new PhpEngine(null);
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    private static PhpEngine.Result run(Path dir, String code, PhpRequest req) throws Exception {
        Path script = Files.writeString(dir.resolve("t.php"), code);
        return engine.execute(script, req);
    }

    private static List<String> setCookies(PhpEngine.Result res) {
        return res.request().responseHeaders.stream()
                .filter(h -> h[0].equalsIgnoreCase("Set-Cookie")).map(h -> h[1]).collect(Collectors.toList());
    }

    @Test
    void setcookieMatchesPhp(@TempDir Path dir) throws Exception {
        PhpEngine.Result res = run(dir, """
                <?php
                setcookie('plain', 'v w+x;é');
                setcookie('opts', 'val', ['expires' => 1700000000, 'path' => '/p', 'domain' => 'ex.com', 'secure' => true, 'httponly' => true, 'samesite' => 'Lax']);
                setcookie('legacy', 'abc', 1700000000, '/l', 'd.com', false, true);
                setcookie('gone', '');
                setrawcookie('raw', 'a%20b', 0, '/');
                """, new PhpRequest());
        assertEquals(200, res.status(), res.bodyText());
        assertEquals(List.of(
                "plain=v%20w%2Bx%3B%C3%A9",
                "opts=val; expires=Tue, 14 Nov 2023 22:13:20 GMT; Max-Age=0; path=/p; domain=ex.com; secure; HttpOnly; SameSite=Lax",
                "legacy=abc; expires=Tue, 14 Nov 2023 22:13:20 GMT; Max-Age=0; path=/l; domain=d.com; HttpOnly",
                "gone=deleted; expires=Thu, 01 Jan 1970 00:00:01 GMT; Max-Age=0",
                "raw=a%20b; path=/"), setCookies(res));
    }

    @Test
    void futureCookieHasMaxAge(@TempDir Path dir) throws Exception {
        PhpEngine.Result res = run(dir, "<?php setcookie('f', 'v', time() + 3600);", new PhpRequest());
        String c = setCookies(res).get(0);
        assertEquals(true, c.matches("f=v; expires=\\w{3}, \\d\\d \\w{3} \\d{4} \\d\\d:\\d\\d:\\d\\d GMT; Max-Age=(3600|3599)"), c);
    }

    @Test
    void headersListAndRemove(@TempDir Path dir) throws Exception {
        PhpEngine.Result res = run(dir, """
                <?php
                header('X-Test: 1');
                header('X-Keep: yes');
                setcookie('c', 'v');
                header_remove('x-test');
                $before = headers_sent();
                echo json_encode(headers_list()), "\\n";
                var_dump($before, headers_sent());
                """, new PhpRequest());
        assertEquals("[\"X-Keep: yes\",\"Set-Cookie: c=v\"]\nbool(false)\nbool(true)\n", res.bodyText());
        PhpEngine.Result all = run(dir, "<?php header('A: 1'); setcookie('x', 'y'); header_remove(); echo count(headers_list());",
                new PhpRequest());
        assertEquals("0", all.bodyText());
        assertEquals(0, all.request().responseHeaders.size());
    }

    @Test
    void superglobalsAreParsedLikePhp(@TempDir Path dir) throws Exception {
        PhpRequest req = new PhpRequest();
        req.method = "POST";
        req.queryString = "a[]=1&a[]=2&b[k]=v&c=x+y";
        req.body = "p[x][]=1&p[x][]=2&q=%E2%82%AC&c=fromPost";
        req.headers.put("content-type", "application/x-www-form-urlencoded; charset=UTF-8");
        req.headers.put("Cookie", "a=1; b%20c=x%20y+z; arr[k]=v; arr[]=w; dot.name=1; e=; a=second");
        PhpEngine.Result res = run(dir, """
                <?php
                echo json_encode($_GET), "\\n", json_encode($_POST), "\\n", json_encode($_REQUEST), "\\n", json_encode($_COOKIE);
                """, req);
        assertEquals("""
                {"a":["1","2"],"b":{"k":"v"},"c":"x y"}
                {"p":{"x":["1","2"]},"q":"\\u20ac","c":"fromPost"}
                {"a":["1","2"],"b":{"k":"v"},"c":"fromPost","p":{"x":["1","2"]},"q":"\\u20ac"}
                {"a":"1","b%20c":"x y+z","arr":{"k":"v","0":"w"},"dot_name":"1","e":""}""", res.bodyText());
    }
}
