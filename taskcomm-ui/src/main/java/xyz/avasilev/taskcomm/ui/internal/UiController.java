package xyz.avasilev.taskcomm.ui.internal;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.util.HtmlUtils;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Раздача SPA интерфейса из {@code META-INF/taskcomm/ui} jar (docs/adr/0149-web-interface-module.md).
 *
 * <ul>
 *   <li>Путь с расширением в последнем сегменте — файл: есть — отдаётся, нет — {@code 404}. Ассеты под
 *       {@code /assets/} с хешем в имени кешируются на год, остальное — без кеша.
 *   <li>Любой другой путь — экран SPA: отдаётся {@code index.html} с {@code <base href>} на путь интерфейса
 *       и путём REST в {@code <meta name="taskcomm-api">}, оба — с учётом context path приложения.
 *   <li>Путь REST внутри пути интерфейса — {@code 404}: неизвестный путь REST не должен получать страницу
 *       интерфейса (docs/adr/0151-default-paths-under-tc.md). Известные пути REST сюда не попадают — их
 *       шаблоны точнее.
 *   <li>Путь не из безопасных символов — {@code 404}: в ресурсы jar не попадает ни {@code ..}, ни обратная
 *       косая черта, ни кодированные символы.
 * </ul>
 */
@Controller
@RequestMapping("${taskcomm.ui.base-path:/tc}")
public class UiController {

    /** Каталог статики в jar. */
    static final String ROOT = "META-INF/taskcomm/ui";

    /** Метка в собранном {@code index.html}, на место которой встают пути. */
    static final String MARKER = "<meta name=\"taskcomm-api\" content=\"\" />";

    /** Путь внутри интерфейса: сегменты из безопасных символов, сегмент не начинается с точки. */
    private static final Pattern SAFE_PATH = Pattern.compile("(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*/?");

    private static final CacheControl IMMUTABLE =
            CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final String uiBase;

    private final String restBase;

    private final ClassLoader classLoader;

    private final String index;

    /**
     * Собирает контроллер и читает {@code index.html}.
     *
     * @param uiBase путь интерфейса
     * @param restBase путь REST
     * @param classLoader загрузчик, которым читается статика
     * @throws TaskcommConfigurationException если в jar нет собранного {@code index.html} или в нём нет метки
     */
    public UiController(final String uiBase, final String restBase, final ClassLoader classLoader) {
        this.uiBase = uiBase;
        this.restBase = restBase;
        this.classLoader = classLoader;
        this.index = readIndex(classLoader);
    }

    /**
     * Экран или файл интерфейса.
     *
     * @param path путь внутри интерфейса, с ведущей косой чертой или пустой
     * @param request запрос — ради context path
     * @return {@code index.html}, файл или {@code 404}
     */
    @GetMapping("/{*path}")
    public ResponseEntity<Object> get(@PathVariable("path") final String path, final HttpServletRequest request) {
        final ResponseEntity<Object> response;
        if (this.underRest(path) || !SAFE_PATH.matcher(path).matches()) {
            response = ResponseEntity.notFound().build();
        } else if (isFile(path)) {
            response = this.file(path);
        } else {
            response = this.index(request.getContextPath());
        }
        return response;
    }

    private boolean underRest(final String path) {
        final String full = this.uiBase + path;
        return full.equals(this.restBase) || full.startsWith(this.restBase + "/");
    }

    /** Файл — расширение в последнем сегменте; {@code index.html} — экран, ему нужны пути. */
    private static boolean isFile(final String path) {
        return !"/index.html".equals(path)
                && path.substring(path.lastIndexOf('/') + 1).contains(".");
    }

    private ResponseEntity<Object> file(final String path) {
        final ResponseEntity<Object> response;
        final Resource resource = new ClassPathResource(ROOT + path, this.classLoader);
        if (!resource.isReadable()) {
            response = ResponseEntity.notFound().build();
        } else {
            response = ResponseEntity.ok()
                    .cacheControl(path.startsWith("/assets/") ? IMMUTABLE : CacheControl.noCache())
                    .contentType(MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM))
                    .body(resource);
        }
        return response;
    }

    private ResponseEntity<Object> index(final String contextPath) {
        final String base = HtmlUtils.htmlEscape(contextPath + this.uiBase + "/");
        final String api = HtmlUtils.htmlEscape(contextPath + this.restBase);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(this.index.replace(
                        MARKER,
                        "<base href=\"" + base + "\" />\n    <meta name=\"taskcomm-api\" content=\"" + api + "\" />"));
    }

    private static String readIndex(final ClassLoader classLoader) {
        final Resource resource = new ClassPathResource(ROOT + "/index.html", classLoader);
        if (!resource.isReadable()) {
            throw new TaskcommConfigurationException(
                    "В jar taskcomm-ui нет " + ROOT + "/index.html: модуль собран без фронтенда");
        }
        final String html;
        try (InputStream in = resource.getInputStream()) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new TaskcommConfigurationException("Не прочитать " + ROOT + "/index.html", e);
        }
        if (!html.contains(MARKER)) {
            throw new TaskcommConfigurationException("В " + ROOT + "/index.html нет метки " + MARKER);
        }
        return html;
    }
}
