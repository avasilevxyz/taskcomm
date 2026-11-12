package xyz.avasilev.taskcomm.ui.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Раздача статики интерфейса (docs/adr/0149-web-interface-module.md): экраны получают {@code index.html}
 * с путями, ассеты — файл с долгим кешем, пути REST и небезопасные пути — {@code 404}.
 */
class UiControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        this.mvc = MockMvcBuilders.standaloneSetup(
                        new UiController("/tc", "/tc/api", UiControllerTest.class.getClassLoader()))
                .build();
    }

    @Test
    void screensGetIndexWithPaths() throws Exception {
        for (final String screen : new String[] {"/tc", "/tc/", "/tc/tasks/TASK/0193", "/tc/index.html"}) {
            this.mvc
                    .perform(get(screen))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andExpect(content().string(Matchers.containsString("<base href=\"/tc/\" />")))
                    .andExpect(content().string(Matchers.containsString("name=\"taskcomm-api\" content=\"/tc/api\"")));
        }
    }

    @Test
    void contextPathGoesIntoBothPaths() throws Exception {
        this.mvc
                .perform(get("/app/tc/executions").contextPath("/app"))
                .andExpect(content().string(Matchers.containsString("<base href=\"/app/tc/\" />")))
                .andExpect(content().string(Matchers.containsString("content=\"/app/tc/api\"")));
    }

    @Test
    void assetsAreServedWithLongCache() throws Exception {
        final String script = builtScript();

        this.mvc
                .perform(get("/tc/" + script))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.containsString("javascript")))
                .andExpect(header().string("Cache-Control", Matchers.containsString("immutable")));
        this.mvc.perform(get("/tc/assets/missing.js")).andExpect(status().isNotFound());
    }

    @Test
    void restPathsAndUnsafePathsAreNotFound() throws Exception {
        for (final String path : new String[] {"/tc/api", "/tc/api/unknown", "/tc/.env", "/tc/a/.hidden/x.js"}) {
            this.mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        this.mvc.perform(get("/tc/a%5Cb.js")).andExpect(status().isNotFound());
        this.mvc.perform(get("/tc/{path}", "../META-INF/MANIFEST.MF")).andExpect(status().isNotFound());
    }

    @Test
    void missingIndexFailsStartup() {
        final ClassLoader empty = new ClassLoader(null) {

            @Override
            public URL getResource(final String name) {
                return null;
            }

            @Override
            public Enumeration<URL> getResources(final String name) {
                return Collections.emptyEnumeration();
            }
        };

        assertThatThrownBy(() -> new UiController("/tc", "/tc/api", empty))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("index.html");
    }

    /** Имя собранного скрипта — его хеш меняется с каждой правкой фронтенда. */
    private static String builtScript() throws IOException {
        final String html;
        try (InputStream in =
                UiControllerTest.class.getClassLoader().getResourceAsStream(UiController.ROOT + "/index.html")) {
            assertThat(in).isNotNull();
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        final Matcher script = Pattern.compile("assets/[^\"]+\\.js").matcher(html);
        assertThat(script.find()).isTrue();
        return script.group();
    }
}
